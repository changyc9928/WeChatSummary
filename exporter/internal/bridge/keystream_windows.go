//go:build windows

package bridge

import (
	"encoding/binary"
	"encoding/hex"
	"fmt"
	"runtime"
	"sort"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"wechatsummary/exporter/internal/scan"
	"wechatsummary/exporter/internal/sqlcipher"
)

// maxModuleBytes caps how much of weixin.dll we read from the process. The
// image can exceed 128 MB on 4.1.x (191,959,040 bytes observed) and the
// resident mem_data may live in the tail, so the cap must comfortably cover
// the whole image.
const maxModuleBytes = 512 << 20

// keyScanDeadline bounds the whole weixin.dll de-obfuscation attempt end to
// end so a miss costs the user a bounded few minutes, not the 44 observed
// when the raw plaintext sweep chases an impossible ModeRaw match afterwards.
const keyScanDeadline = 4 * time.Minute

// markerWindowSize is the ±window around each anchor-string occurrence
// ChatShadow sweeps for the resident 32-byte mem_data blob.
const markerWindowSize = 2048

// probeJob is one candidate verification unit for runProbeJobs.
type probeJob struct {
	raw  bool // raw page-key check instead of passphrase KDF
	data []byte
	src  string
}

// scanDBKeyViaWeixinDll implements the wkt/ChatShadow de-obfuscation for the
// user's running WeChat: locate weixin.dll in the Weixin.exe process(es),
// extract the hardcoded internal key (mov-imm sequence and/or the wkt
// signature markers), read the resident 32-byte mem_data candidates (caller
// references, marker windows, wkt imm64 data addresses, whole-module filtered
// fallback), XOR each pair into a passphrase, PBKDF2-derive and verify against
// the DB page-1 probe. Returns the verified 64-hex key, the verification
// label, and whether a weixin.dll module was found at all (the caller uses
// that to skip the provably-useless ModeRaw plaintext sweep on 4.x).
//
// Stage order follows proven value on 4.1.x: the memory-wide runtime-marker
// scan is the highest-value candidate source (it found the live key on this
// machine's WeChat 4.1.12.26, where the mem_data blob sits near a heap copy
// of the anchor strings). It must get the full budget — previously the
// module-targeted scanDllDirect ran first and its ~14k derived probes burned
// 2m53s of the 4-minute deadline, leaving the runtime-marker scan only ~1m
// (3868 probes of 75k candidates) so the key's window fell past the
// truncation. Order now: runtime markers first, then module-targeted direct
// stages, then the tiny whole-module fallback last.
func scanDBKeyViaWeixinDll(dbPath string, probe keyProbeFunc, logf func(string)) (keyHex, label string, moduleFound bool) {
	if probe == nil {
		return "", "", false
	}
	salt, err := readDbSalt(dbPath)
	if err != nil {
		bridgeLogDebug("db key scan: weixin.dll recovery skipped: %v", err)
		return "", "", false
	}
	procs, err := scan.FindWeChatProcesses()
	if err != nil {
		bridgeLogDebug("db key scan: weixin.dll recovery: %v", err)
		return "", "", false
	}
	sort.SliceStable(procs, func(i, j int) bool { return nameRank(procs[i].Name) < nameRank(procs[j].Name) })
	deadline := time.Now().Add(keyScanDeadline)

	var weixinProcs []scan.ProcessInfo
	var lastMod []byte
	var lastBase uintptr
	for _, p := range procs {
		mi := scan.FindModule(p.PID, "weixin.dll")
		logf(fmt.Sprintf("db key scan: pid %d (%s) -> module weixin.dll found=%v base=0x%x size=%d",
			p.PID, p.Name, mi.Found, mi.Base, mi.Size))
		if !mi.Found || mi.Base == 0 || mi.Size == 0 {
			continue
		}
		moduleFound = true
		weixinProcs = append(weixinProcs, p)
		if mi.Size > maxModuleBytes {
			bridgeLogDebug("db key scan: weixin.dll size %d exceeds cap %d, truncating", mi.Size, maxModuleBytes)
			mi.Size = maxModuleBytes
		}
		mod, rerr := scan.ReadMemory(p.PID, mi.Base, int(mi.Size))
		if rerr != nil {
			bridgeLogDebug("db key scan: weixin.dll read failed: %v", rerr)
		}
		if len(mod) < len(weixinKeySignature)+0x100 {
			bridgeLogDebug("db key scan: weixin.dll read too short (%d bytes), skipping pid %d", len(mod), p.PID)
			continue
		}
		logf(fmt.Sprintf("db key scan: weixin.dll %d bytes read (pid %d, %s)", len(mod), p.PID, mi.Path))
		lastMod, lastBase = mod, mi.Base
	}

	// 1) Runtime-marker scan FIRST with the full remaining budget minus a
	//    small reserve for the module-targeted stages below: the mem_data
	//    blob lives near runtime copies of the ChatShadow anchor strings
	//    (heap, other modules), which this stage scans memory-wide. It is the
	//    only stage that has ever produced a key on 4.1.x.
	markerDeadline := deadline
	if reserve := 45 * time.Second; time.Until(deadline) > reserve {
		markerDeadline = deadline.Add(-reserve)
	}
	if time.Now().Before(markerDeadline) && len(weixinProcs) > 0 {
		if found := scanProcessMarkers(weixinProcs, salt, probe, logf, markerDeadline); found != "" {
			return found, "weixin-dll-obfuscated", true
		}
	}

	// 2) Module-targeted stages (internal keys themselves, the wkt signature
	//    function's data references, call sites, 64-hex strings, and the
	//    anchor windows inside the image) with whatever budget remains.
	if time.Now().Before(deadline) && len(lastMod) > 0 {
		if found := scanDllDirect(lastMod, lastBase, salt, probe, logf, deadline); found != "" {
			return found, "weixin-dll-obfuscated", true
		}
	}

	// 3) Last resort: a tiny budgeted whole-module filtered sweep on the first
	//    module image we read (covers a mem_data global the anchors never touch).
	if time.Now().Before(deadline) && len(lastMod) > 0 {
		if found := moduleFallback(lastMod, lastBase, salt, probe, logf, deadline); found != "" {
			return found, "weixin-dll-obfuscated", true
		}
	}
	return "", "", moduleFound
}

// readDbSalt is in keystream_core.go (cross-platform).

// scanDllDirect runs the targeted stages over one module image: the internal
// keys themselves (+ reversed + pairwise XOR), the wkt signature function's
// own data references, the call sites of the internal-key functions (where
// the callers touch the key material), 64-hex string candidates, and the
// ChatShadow anchor windows inside the image. No whole-module sweep — that is
// moduleFallback, run last.
func scanDllDirect(mod []byte, base uintptr, salt []byte, probe keyProbeFunc, logf func(string), deadline time.Time) string {
	matches := collectInternalKeyMatches(mod)
	if len(matches) == 0 {
		logf("db key scan: no internal key extracted (mov-imm sequence and wkt signature both absent)")
		return ""
	}
	var internalKeys [][]byte
	for _, m := range matches {
		internalKeys = append(internalKeys, m.Key)
	}
	logf(fmt.Sprintf("db key scan: internal key candidates=%d: %s",
		len(internalKeys), strings.Join(internalKeysHex(internalKeys), ", ")))

	var jobs []probeJob
	seenJob := map[string]bool{}
	addJob := func(raw bool, data []byte, src string) {
		if len(data) != 32 {
			return
		}
		h := hex.EncodeToString(data)
		if seenJob[h] {
			return
		}
		seenJob[h] = true
		jobs = append(jobs, probeJob{raw: raw, data: append([]byte(nil), data...), src: src})
	}
	// blobAt registers one 32-byte blob at a module offset for every probe
	// flavor (raw, derived as passphrase, derived XOR-ed with each internal key).
	blobAt := func(t uintptr, tag string) {
		for _, off := range dllOffsets(t, base) {
			if off < 0 || off+32 > len(mod) {
				continue
			}
			blob := mod[off : off+32]
			addJob(true, blob, tag)
			addJob(false, blob, tag)
			for _, ik := range internalKeys {
				addJob(false, xorBytes32(ik, blob), tag)
			}
			return
		}
	}

	// 0) The extracted internal keys themselves (raw + derived), reversed
	//    interpretations, and pairwise XORs.
	for _, ik := range internalKeys {
		addJob(true, ik, "internal-key")
		addJob(false, ik, "internal-key")
		addJob(true, reverseBytes32(ik), "internal-key-rev")
		addJob(false, reverseBytes32(ik), "internal-key-rev")
	}
	for i := 0; i < len(internalKeys); i++ {
		for j := i + 1; j < len(internalKeys); j++ {
			x := xorBytes32(internalKeys[i], internalKeys[j])
			addJob(true, x, "internal-key-pair")
			addJob(false, x, "internal-key-pair")
		}
	}

	// 1) The wkt signature: its trailing movabs immediates and the matched
	//    function's RIP-relative references reach the resident globals.
	sigIdx, sigTargets := signatureMatch(mod)
	if sigIdx < 0 {
		logf("db key scan: wkt keystream signature not found in module")
	} else {
		logf(fmt.Sprintf("db key scan: keystream signature matched at module offset 0x%x (VA 0x%x)",
			sigIdx, base+uintptr(sigIdx)))
		logf(fmt.Sprintf("db key scan: signature region hex: %s",
			hex.EncodeToString(mod[sigIdx:min(sigIdx+0x40, len(mod))])))
		for _, va := range sigTargets {
			logf(fmt.Sprintf("db key scan: signature imm64 VA 0x%x", va))
			blobAt(va, fmt.Sprintf("sig-imm 0x%x", va))
			blobAt(reverseBytes(va), fmt.Sprintf("sig-imm-rev 0x%x", va))
		}
		ral := ripRelativeTargets(mod, sigIdx, min(sigIdx+0x600, len(mod)), base)
		logf(fmt.Sprintf("db key scan: rip-relative targets in signature function=%d", len(ral)))
		for _, t := range ral {
			blobAt(t, fmt.Sprintf("rip-rel 0x%x", t))
		}
	}

	// 1b) Call sites of the internal-key functions: the callers reference the
	//     key globals right around their call — trace them like the sig path.
	targetSet := map[int]bool{}
	for _, m := range matches {
		targetSet[m.At] = true
	}
	sites := keyCallSites(mod, targetSet)
	logf(fmt.Sprintf("db key scan: internal-key function call sites=%d", len(sites)))
	for _, s := range sites {
		from := s - 0x20
		if from < 0 {
			from = 0
		}
		for _, t := range ripRelativeTargets(mod, from, min(s+0x400, len(mod)), base) {
			blobAt(t, fmt.Sprintf("key-caller 0x%x", t))
		}
	}

	// 2) 64-hex ASCII windows inside the module.
	hexCap := 0
	for _, sp := range hexSpans(mod) {
		for i := 0; i+64 <= len(sp); i++ {
			raw, derr := hex.DecodeString(string(sp[i : i+64]))
			if derr != nil || len(raw) != 32 {
				continue
			}
			addJob(true, raw, "module-hex")
			addJob(false, raw, "module-hex")
			hexCap++
			if hexCap >= 2000 {
				break
			}
		}
		if hexCap >= 2000 {
			break
		}
	}
	if hexCap > 0 {
		logf(fmt.Sprintf("db key scan: module hex-string candidates=%d", hexCap))
	}

	// 3) ChatShadow anchor strings inside the module image.
	markers := hasMarkers(mod)
	logf(fmt.Sprintf("db key scan: chatshadow markers present=%v", markers))
	if len(markers) > 0 {
		windows := markerWindows(mod, markerWindowSize)
		mems := memDataCandidates(mod, windows, 20000)
		logf(fmt.Sprintf("db key scan: marker-window mem_data candidates=%d (filtered), windows=%d",
			len(mems), len(windows)))
		for _, m := range mems {
			addJob(true, m, "marker-window")
			addJob(false, m, "marker-window")
			for _, ik := range internalKeys {
				addJob(false, xorBytes32(ik, m), "marker-window")
			}
		}
		// The dup filter rejects ~25% of random 32-byte blobs; probe a capped
		// unfiltered subset around the anchors so a tripled-byte mem_data is
		// still caught.
		addUnfilteredWindows(windows, mod, internalKeys, addJob, 4000, "marker-window-uf")
	}

	logf(fmt.Sprintf("db key scan: direct jobs=%d", len(jobs)))
	return runProbeJobs(jobs, salt, probe, logf, deadline)
}

// addUnfilteredWindows probes every 32-byte window of the given [from,to)
// ranges (no dup filter), capped by budgetJobs, in each flavor.
func addUnfilteredWindows(windows [][2]int, mod []byte, internalKeys [][]byte, addJob func(bool, []byte, string), budget int, tag string) {
	added := 0
	for _, w := range windows {
		if added >= budget {
			break
		}
		lo, hi := w[0], w[1]
		if lo < 0 {
			lo = 0
		}
		if hi > len(mod) {
			hi = len(mod)
		}
		for i := lo; i+32 <= hi; i++ {
			blob := mod[i : i+32]
			addJob(true, blob, tag)
			for _, ik := range internalKeys {
				addJob(false, xorBytes32(ik, blob), tag)
			}
			added++
			if added >= budget {
				break
			}
		}
	}
	if added > 0 {
		bridgeLogDebug("db key scan: unfiltered anchor windows added=%d", added)
	}
}

// moduleFallback is the tiny whole-module filtered sweep (last resort).
func moduleFallback(mod []byte, base uintptr, salt []byte, probe keyProbeFunc, logf func(string), deadline time.Time) string {
	matches := collectInternalKeyMatches(mod)
	if len(matches) == 0 {
		return ""
	}
	var internalKeys [][]byte
	for _, m := range matches {
		internalKeys = append(internalKeys, m.Key)
	}
	memsAll := memDataCandidatesAll(mod, 10000)
	logf(fmt.Sprintf("db key scan: whole-module filtered mem_data candidates=%d", len(memsAll)))
	var jobs []probeJob
	seen := map[string]bool{}
	addJob2 := func(raw bool, data []byte, src string) {
		if len(data) != 32 {
			return
		}
		h := hex.EncodeToString(data)
		if seen[h] {
			return
		}
		seen[h] = true
		jobs = append(jobs, probeJob{raw: raw, data: append([]byte(nil), data...), src: src})
	}
	for _, m := range memsAll {
		addJob2(true, m, "module-window")
		for _, ik := range internalKeys {
			addJob2(false, xorBytes32(ik, m), "module-window")
		}
	}
	return runProbeJobs(jobsWithinBudget(jobs, deadline, logf), salt, probe, logf, deadline)
}

// keyCallSites returns module offsets of `E8 rel32` call instructions whose
// target is one of the given internal-key function offsets.
func keyCallSites(mod []byte, targets map[int]bool) []int {
	var out []int
	for i := 0; i+5 <= len(mod); i++ {
		if mod[i] != 0xE8 {
			continue
		}
		rel := int32(binary.LittleEndian.Uint32(mod[i+1 : i+5]))
		tgt := i + 5 + int(rel)
		if targets[tgt] {
			out = append(out, i)
		}
	}
	return out
}

// scanProcessMarkers streams the readable regions of the given Weixin.exe
// processes looking for the ChatShadow anchor strings in heap/other-module
// copies, and probes ±window blobs XOR-ed with each internal key.
func scanProcessMarkers(procs []scan.ProcessInfo, salt []byte, probe keyProbeFunc, logf func(string), deadline time.Time) string {
	type occ struct {
		pid uint32
		at  uintptr
	}
	var occs []occ
	// Cap is generous: the mem_data blob can sit near any copy of the anchor
	// strings, and a long-lived WeChat 4.x session accumulates many copies
	// (observed 1024+ with the key's window still uncollected). Past runs
	// capped at 1024 and the key's window fell beyond the collected set.
	maxOcc := 4096
	for _, p := range procs {
		if !strings.Contains(strings.ToLower(p.Name), "weixin") {
			continue
		}
		_, _ = scan.ForEachReadableRegion(p.PID, 1<<30, func(addr uintptr, data []byte) error {
			for _, mk := range chatShadowMarkers {
				off := 0
				for {
					i := indexBytes(data[off:], []byte(mk))
					if i < 0 {
						break
					}
					occs = append(occs, occ{pid: p.PID, at: addr + uintptr(off+i)})
					if len(occs) >= maxOcc {
						return scan.ErrStopScanning
					}
					off += i + len(mk)
				}
			}
			if len(occs) >= maxOcc {
				return scan.ErrStopScanning
			}
			return nil
		})
		if len(occs) >= maxOcc {
			break
		}
	}
	logf(fmt.Sprintf("db key scan: runtime marker occurrences=%d", len(occs)))
	if len(occs) == 0 {
		return ""
	}

	var jobs []probeJob
	seen := map[string]bool{}
	addJob := func(raw bool, data []byte, src string) {
		if len(data) != 32 {
			return
		}
		h := hex.EncodeToString(data)
		if seen[h] {
			return
		}
		seen[h] = true
		jobs = append(jobs, probeJob{raw: raw, data: append([]byte(nil), data...), src: src})
	}
	internalKeys := collectInternalKeysAt(procs, logf, deadline) // may enrich too
	win := markerWindowSize
	for _, o := range occs {
		if time.Now().After(deadline) {
			break
		}
		from := o.at - uintptr(win)
		if from > o.at {
			from = 0
		}
		blob, rerr := scan.ReadMemory(o.pid, from, 2*win+32)
		if rerr != nil || len(blob) < 64 {
			continue
		}
		// filtered windows first (higher signal), then unfiltered (defeats
		// the dup-filter miss), capped below.
		filtered := 0
		unfiltered := 0
		for i := 0; i+32 <= len(blob); i++ {
			w := blob[i : i+32]
			if !memDataFilter(w) {
				continue
			}
			addJob(true, w, "runtime-marker")
			for _, ik := range internalKeys {
				addJob(false, xorBytes32(ik, w), "runtime-marker")
			}
			filtered++
			if filtered >= 40 {
				break
			}
		}
		for i := 0; i+32 <= len(blob) && unfiltered < 24; i++ {
			w := blob[i : i+32]
			addJob(true, w, "runtime-marker-uf")
			for _, ik := range internalKeys {
				addJob(false, xorBytes32(ik, w), "runtime-marker-uf")
			}
			unfiltered++
		}
	}
	logf(fmt.Sprintf("db key scan: runtime-marker jobs=%d", len(jobs)))
	return runProbeJobs(jobsWithinBudget(jobs, deadline, logf), salt, probe, logf, deadline)
}

// collectInternalKeysAt extracts every mov-imm internal key from every
// weixin.dll module of the given processes (used by the runtime-marker stage
// to also pick up keys that may only exist in a sibling module).
func collectInternalKeysAt(procs []scan.ProcessInfo, logf func(string), deadline time.Time) [][]byte {
	var out [][]byte
	seen := map[string]bool{}
	add := func(k []byte) {
		h := hex.EncodeToString(k)
		if !seen[h] {
			seen[h] = true
			out = append(out, k)
		}
	}
	for _, p := range procs {
		if time.Now().After(deadline) {
			break
		}
		mi := scan.FindModule(p.PID, "weixin.dll")
		if !mi.Found || mi.Size == 0 {
			continue
		}
		if mi.Size > maxModuleBytes {
			mi.Size = maxModuleBytes
		}
		mod, rerr := scan.ReadMemory(p.PID, mi.Base, int(mi.Size))
		if rerr != nil {
			continue
		}
		from := 0
		for {
			m := extractInternalKeyMovImm(mod, from)
			if m == nil {
				break
			}
			from = m.At + 1
			add(m.Key)
		}
	}
	return out
}

// reverseBytes returns the reversed-byte reinterpretation of a 64-bit value
// (some builds store key chunks reversed inside movabs immediates).
func reverseBytes(v uintptr) uintptr {
	var out uintptr
	for i := 0; i < 8; i++ {
		out = out<<8 | uintptr(byte(v>>(8*uint(i))))
	}
	return out
}

// jobsWithinBudget truncates a candidate list to what fits before deadline
// (the probe is ~0.1-0.35 s on typical machines, so ~60/s is conservative).
func jobsWithinBudget(jobs []probeJob, deadline time.Time, logf func(string)) []probeJob {
	remaining := time.Until(deadline)
	if remaining <= 0 {
		logf("db key scan: deadline reached, skipping remaining candidates")
		return nil
	}
	maxJobs := int(remaining.Seconds() * 60)
	if len(jobs) <= maxJobs {
		return jobs
	}
	logf(fmt.Sprintf("db key scan: truncating %d candidates to budget %d (deadline in %s)",
		len(jobs), maxJobs, remaining.Round(time.Second)))
	return jobs[:maxJobs]
}

// runProbeJobs verifies a candidate list in parallel; raw candidates bypass
// the expensive 256000-iteration KDF (raw key mode), derived candidates run
// DerivePageKey first. Returns the verified 64-hex passphrase or "".
func runProbeJobs(jobs []probeJob, salt []byte, probe keyProbeFunc, logf func(string), deadline time.Time) string {
	if len(jobs) == 0 {
		return ""
	}
	workers := runtime.NumCPU()
	if workers > 32 {
		workers = 32
	}
	if workers < 2 {
		workers = 2
	}
	var found atomic.Value
	stopAll := make(chan struct{})
	deadlineHit := false
	var wg sync.WaitGroup
	work := make(chan probeJob)
	for w := 0; w < workers; w++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			for j := range work {
				if found.Load() != nil {
					return
				}
				key := j.data
				if !j.raw {
					key = sqlcipher.DerivePageKey(j.data, salt)
				}
				if lab := probe(key); lab != "" {
					found.Store(hex.EncodeToString(j.data))
					close(stopAll)
					logf(fmt.Sprintf("db key scan: verified (%s, %s)", lab, j.src))
					return
				}
			}
		}()
	}
	attempted := 0
feed:
	for _, j := range jobs {
		if time.Now().After(deadline) {
			deadlineHit = true
			break feed
		}
		select {
		case <-stopAll:
			break feed
		case work <- j:
			attempted++
			if attempted%1000 == 0 {
				logf(fmt.Sprintf("db key scan: probes=%d", attempted))
			}
		}
	}
	close(work)
	wg.Wait()
	if v := found.Load(); v != nil {
		return v.(string)
	}
	if deadlineHit {
		logf(fmt.Sprintf("db key scan: deadline reached after %d probes", attempted))
	} else {
		logf(fmt.Sprintf("db key scan: %d probes, none verified", attempted))
	}
	return ""
}

// collectInternalKeyMatches extracts every mov-imm internal key constant from
// a weixin.dll image (deduplicated by key value, keeping the first offset).
func collectInternalKeyMatches(mod []byte) []movImmMatch {
	var out []movImmMatch
	seen := map[string]bool{}
	from := 0
	for {
		m := extractInternalKeyMovImm(mod, from)
		if m == nil {
			break
		}
		from = m.At + 1
		hk := hex.EncodeToString(m.Key)
		if seen[hk] {
			continue
		}
		seen[hk] = true
		out = append(out, *m)
	}
	return out
}

// collectInternalKeys extracts the hardcoded internal key constants from a
// weixin.dll image via the mov-imm layout (deduplicated), logging each.
func collectInternalKeys(mod []byte, logf func(string)) [][]byte {
	var out [][]byte
	for _, m := range collectInternalKeyMatches(mod) {
		out = append(out, m.Key)
		logf(fmt.Sprintf("db key scan: internal key from mov-imm at module offset 0x%x (%s)", m.At, hex.EncodeToString(m.Key)))
	}
	if len(out) == 0 {
		logf(fmt.Sprintf("db key scan: wkt signature present=%v", indexBytes(mod, weixinKeySignature) >= 0))
	}
	return out
}

func internalKeysHex(keys [][]byte) []string {
	out := make([]string, 0, len(keys))
	for _, k := range keys {
		out = append(out, hex.EncodeToString(k))
	}
	return out
}
