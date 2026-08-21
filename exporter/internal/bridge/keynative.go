package bridge

import (
	"bytes"
	"encoding/hex"
	"errors"
	"fmt"
	"runtime"
	"sort"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"wechatsummary/exporter/internal/datdecrypt"
	"wechatsummary/exporter/internal/scan"
	"wechatsummary/exporter/internal/sqlcipher"
)

// Native (no-DLL) key recovery.
//
// CipherTalk's wechat_key_tool.dll is closed-source, so instead of depending
// on it we re-implement the two memory scans it performs, driven by the same
// cryptographic probes the DLL uses:
//
//   - database key: the 32-byte SQLCipher page key is materialized somewhere
//     in the running WeChat's memory (4.1.x: inside the global_config
//     structure; 4.0.x: near the salt scan). We extract every 64-hex
//     printable window (and, anchored on the account id, raw 32-byte
//     windows) and verify each one against the real database — a page-1
//     decrypt/MAC probe, so a wrong candidate can never produce a false key.
//   - image AES key: the 32-character key string sits in WeChat memory once
//     images have been loaded. We test every 16-character printable window by
//     decrypting the V2 template ciphertext we already have from the on-disk
//     _t.dat templates (block magic) and confirm with a full template
//     decrypt — again, verification makes false positives impossible.
//
// Both scans are probe-driven, so they need no WeChat-version offsets and no
// external binaries; the memory walk itself is Windows-only (the bridge runs
// on the same machine as WeChat).

// Limits for in-memory key scans.
const (
	// maxScanBytes caps how much of each WeChat process is read (same 1 GiB
	// budget as the pattern scanner).
	maxScanBytes = int64(1 << 30)
	// maxKeyAttempts caps candidate verifications for the DB-key hex spans
	// (raw 32-byte windows have their own maxRawCandidates cap).
	maxKeyAttempts = 4_000_000
	// aesMaxKeyAttempts caps V2 media AES-key probes. WeChatAppEx heaps are
	// dense with printable JS/config runs, so a small budget (4M) is burned in
	// the first ~128 MB of memory — the actual key region is never reached.
	// 40M covers ~1.3 GB of printable-run windows at ~2.5M probes/sec.
	aesMaxKeyAttempts = 40_000_000
	// aesScanDeadline bounds the AES-key scan end to end (streaming + probe).
	aesScanDeadline = 120 * time.Second
	// aesWxidAnchorChunks caps the wxid-anchored AES probe (the account-struct
	// region is the most likely home of the image key).
	aesWxidAnchorChunks = 24
	// maxWxidChunks caps the wxid-anchored raw 32-byte sweep (fallback).
	maxWxidChunks = 8
	// dbRawDeadline bounds the full-region raw 32-byte sweep (the primary raw
	// source; the wxid-anchored pass is the fallback when it expires early).
	dbRawDeadline = 12 * time.Minute
	// maxRawCandidates caps total raw 32-byte verifications (each ~25µs HMAC;
	// 200M covers ~6.4 GiB of sliding windows, i.e. all Weixin.exe regions
	// even at the 1 GiB per-process cap).
	maxRawCandidates = 200_000_000
	// rawWorkers bounds the raw-sweep verification goroutines.
	rawWorkers = 8
)

// memorySource feeds raw process-memory chunks to a key scan. fn is invoked
// per chunk with the owning process's pid and name, the absolute address, and
// the bytes. Implemented by the Windows driver (reads the running WeChat
// processes); tests substitute synthetic chunk streams.
type memorySource func(fn func(pid uint32, name string, addr uintptr, data []byte) error) (int64, []error)

// memChunk is one piece of another process's address space.
type memChunk struct {
	addr uintptr
	data []byte
}

// asciiRun is a maximal printable-ASCII run (0x20..0x7e) with the absolute
// address of its first byte.
type asciiRun struct {
	addr uintptr
	data []byte
}

// runCollector assembles printable-ASCII runs across chunk boundaries; runs
// are emitted as soon as they terminate inside a fed chunk.
type runCollector struct {
	pending   []byte
	pendingAt uintptr
}

func (c *runCollector) feed(addr uintptr, data []byte) []asciiRun {
	var out []asciiRun
	for i := 0; i < len(data); i++ {
		b := data[i]
		if b >= 0x20 && b < 0x7f {
			if len(c.pending) == 0 {
				c.pendingAt = addr + uintptr(i)
			}
			c.pending = append(c.pending, b)
			continue
		}
		if len(c.pending) > 0 {
			out = append(out, asciiRun{addr: c.pendingAt, data: c.pending})
			c.pending = nil
		}
	}
	return out
}

func (c *runCollector) flush() []asciiRun {
	var out []asciiRun
	if len(c.pending) > 0 {
		out = append(out, asciiRun{addr: c.pendingAt, data: c.pending})
		c.pending = nil
	}
	return out
}

func isHexDigit(b byte) bool {
	return b >= '0' && b <= '9' || b >= 'a' && b <= 'f' || b >= 'A' && b <= 'F'
}

// hexSpans splits a run into maximal contiguous hex-only spans of at least 64
// characters (the shortest span that can hold one 64-hex key window).
func hexSpans(b []byte) [][]byte {
	var out [][]byte
	start := -1
	for i, c := range b {
		if isHexDigit(c) {
			if start < 0 {
				start = i
			}
			continue
		}
		if start >= 0 {
			if i-start >= 64 {
				out = append(out, b[start:i])
			}
			start = -1
		}
	}
	if start >= 0 && len(b)-start >= 64 {
		out = append(out, b[start:])
	}
	return out
}

// scanDBKeyMemory streams memory and returns the first 64-hex raw page key
// probe() accepts, together with its verification label (probe returns "" for
// a miss). Candidate sources:
//
//  1. every raw 32-byte window of every readable region of the Weixin.exe
//     processes (the WeChatAppEx JS runtimes are hex-scanned only), verified
//     in parallel by a small worker pool so the whole client heap is covered
//     within the deadline;
//  2. every 64-hex window inside the hex spans of printable runs (streamed);
//  3. every 64-hex window inside UTF-16LE hex runs ("3\x000\x00f\x00...");
//  4. fallback: raw windows of the (rare) chunks containing wxidToken text,
//     used when source 1 expires before reaching that region.
func scanDBKeyMemory(src memorySource, probe func(pageKey []byte) string, wxidToken string, logf func(string)) (keyHex, label string, hexCandidates, rawCandidates int) {
	if logf == nil {
		logf = func(string) {}
	}
	col := &runCollector{}
	var wxidChunks []memChunk

	// Found state shared between the streaming callback and raw workers.
	var found, foundLabel, foundMsg atomic.Value
	var foundOnce sync.Once
	setFound := func(key []byte, lab, msg string) {
		foundOnce.Do(func() {
			found.Store(append([]byte(nil), key...))
			foundLabel.Store(lab)
			foundMsg.Store(msg)
			logf(msg)
		})
	}

	// Parallel raw sweep over the main client's processes.
	workers := rawWorkers
	if g := runtime.GOMAXPROCS(0); g < workers {
		workers = g
	}
	if workers < 1 {
		workers = 1
	}
	var rawN atomic.Int64
	jobs := make(chan memChunk, 64)
	stopAll := make(chan struct{})
	var stopOnce sync.Once
	stop := func() { stopOnce.Do(func() { close(stopAll) }) }
	deadline := time.AfterFunc(dbRawDeadline, stop)
	defer deadline.Stop()

	var wg sync.WaitGroup
	for w := 0; w < workers; w++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			for job := range jobs {
				select {
				case <-stopAll:
					return
				default:
				}
				data := job.data
				for i := 0; i+32 <= len(data); i++ {
					if i&0x7FFFF == 0 {
						select {
						case <-stopAll:
							return
						default:
						}
					}
					if rawN.Load() >= maxRawCandidates {
						stop()
						return
					}
					rawN.Add(1)
					if lab := probe(data[i : i+32]); lab != "" {
						setFound(data[i:i+32], lab,
							fmt.Sprintf("db key: raw 32-byte window verified (%s, offset 0x%x)", lab, i))
						stop()
						return
					}
				}
			}
		}()
	}

	handleRuns := func(runs []asciiRun) bool {
		for _, r := range runs {
			for _, sp := range hexSpans(r.data) {
				if hexCandidates > maxKeyAttempts {
					return true
				}
				for i := 0; i+64 <= len(sp); i++ {
					raw, derr := hex.DecodeString(string(sp[i : i+64]))
					if derr != nil || len(raw) != 32 {
						continue
					}
					hexCandidates++
					if lab := probe(raw); lab != "" {
						setFound(raw, lab, fmt.Sprintf("db key: 64-hex window verified (%s)", lab))
						return true
					}
				}
			}
		}
		return false
	}
	// probeWideHex covers UTF-16LE hex text ("3\x000\x00f\x00..."), the shape
	// a config string takes inside the 4.x client; ASCII hexSpans can't see it
	// because the 0x00 interleave breaks printable runs.
	probeWideHex := func(data []byte) bool {
		i, n := 0, len(data)
		for i+1 < n {
			if !isHexDigit(data[i]) || data[i+1] != 0x00 {
				i++
				continue
			}
			j := i
			for j+1 < n && isHexDigit(data[j]) && data[j+1] == 0x00 {
				j += 2
			}
			if pairs := (j - i) / 2; pairs >= 64 {
				sp := make([]byte, 0, pairs)
				for k := i; k < j; k += 2 {
					sp = append(sp, data[k])
				}
				for w := 0; w+64 <= len(sp); w++ {
					if hexCandidates > maxKeyAttempts {
						return true
					}
					raw, derr := hex.DecodeString(string(sp[w : w+64]))
					if derr != nil || len(raw) != 32 {
						continue
					}
					hexCandidates++
					if lab := probe(raw); lab != "" {
						setFound(raw, lab, fmt.Sprintf("db key: 64-wide-hex window verified (%s)", lab))
						return true
					}
				}
			}
			i = j
		}
		return false
	}

	var scanned, lastProgMB int64
	_, _ = src(func(pid uint32, pname string, addr uintptr, data []byte) error {
		if found.Load() != nil {
			return scan.ErrStopScanning
		}
		scanned += int64(len(data))
		if !strings.Contains(strings.ToLower(pname), "wechatappex") {
			select {
			case jobs <- memChunk{addr: addr, data: data}:
			case <-stopAll:
				return scan.ErrStopScanning
			}
		}
		if found.Load() != nil {
			return scan.ErrStopScanning
		}
		if handleRuns(col.feed(addr, data)) {
			stop()
			return scan.ErrStopScanning
		}
		if probeWideHex(data) {
			stop()
			return scan.ErrStopScanning
		}
		if wxidToken != "" && len(wxidChunks) < maxWxidChunks && bytes.Contains(data, []byte(wxidToken)) {
			wxidChunks = append(wxidChunks, memChunk{addr: addr, data: data})
		}
		if scanned-lastProgMB >= 512<<20 {
			logf(fmt.Sprintf("db key scan: %d MB scanned, %d raw + %d hex candidates so far",
				scanned>>20, rawN.Load(), hexCandidates))
			lastProgMB = scanned
		}
		return nil
	})
	close(jobs)
	wg.Wait()

	if found.Load() == nil {
		handleRuns(col.flush())
	}
	if found.Load() == nil {
		// Fallback (source 3): the raw deadline may have fired before the
		// wxid region; still probe the anchor chunks so a key that only ever
		// sits near the account text is not missed.
		for _, c := range wxidChunks {
			for i := 0; i+32 <= len(c.data); i++ {
				if rawN.Load() >= maxRawCandidates {
					break
				}
				rawN.Add(1)
				if lab := probe(c.data[i : i+32]); lab != "" {
					setFound(c.data[i:i+32], lab,
						fmt.Sprintf("db key: raw 32-byte window near wxid verified (%s)", lab))
					break
				}
			}
			if found.Load() != nil {
				break
			}
		}
	}
	if v := found.Load(); v != nil {
		keyHex = hex.EncodeToString(v.([]byte))
		label, _ = foundLabel.Load().(string)
	}
	return keyHex, label, hexCandidates, int(rawN.Load())
}

// scanAesKeyMemory finds the first printable-run 16-character window whose
// probe() accepts (probe = decrypt the V2 template block, check the image
// magic, then confirm with a full template decrypt). Returns the run text
// starting at the matching window (up to 32 chars) and the attempt count.
//
// Runs are probed inline as the memory walk streams them, so memory use is
// O(chunk) regardless of how printable-dense the process heaps are — a
// collect-then-probe design previously buffered every printable run
// (WeChatAppEx heaps emit millions of 1-4-byte runs) and OOM'd the bridge.
// Bounded by the attempt/deadline caps passed by the caller (the deadline is
// shared across retries in findImageAesKeyInMemory), and anchored to the
// account text when wxidToken is given:
//
//  1. Anchor pass: probe printable runs only inside chunks that contain the
//     wxid token (bounded by aesWxidAnchorChunks). The image AES key sits in
//     the same account-keyring region as the wxid/DB-key material, so this
//     finds it in a few hundred probes instead of millions.
//  2. Full sweep: every printable run across every region, up to
//     maxAttempts / deadline, catching keys stored away from the account text.
//
// Returns the verified key text (up to 32 chars) or "".
func scanAesKeyMemory(src memorySource, probe func(key16 []byte) string, wxidToken string, maxAttempts int, deadline time.Time, logf func(string)) (key string, attempts int, err error) {
	if logf == nil {
		logf = func(string) {}
	}
	col := &runCollector{}
	var foundKey string
	var nRead int64
	var lastLogMB int64
	anchorChunks := 0
	anchored := false

	// probeRun slides a 16-byte window over one printable run, calling probe
	// for each; returns the verified key text (up to 32 chars) or "".
	probeRun := func(r asciiRun) bool {
		if len(r.data) < 16 {
			return false
		}
		for i := 0; i+16 <= len(r.data); i++ {
			if attempts >= maxAttempts {
				return true // budget exhausted sentinel
			}
			attempts++
			if lab := probe(r.data[i : i+16]); lab != "" {
				upTo := min(i+32, len(r.data))
				s := string(r.data[i:upTo])
				if len(s) < 16 {
					s = string(r.data[i:])
				}
				logf(fmt.Sprintf("aes key: window verified (%s)", lab))
				foundKey = s
				return true
			}
		}
		return false
	}

	handleRuns := func(runs []asciiRun) bool {
		for _, r := range runs {
			if probeRun(r) {
				return true
			}
		}
		return false
	}

	// Anchor pass: only chunks containing the account token. The keyring blob
	// is small; probing it first spends almost none of the budget and usually
	// ends the scan immediately.
	if wxidToken != "" {
		tok := []byte(wxidToken)
		_, _ = src(func(_ uint32, _ string, addr uintptr, data []byte) error {
			if time.Now().After(deadline) || foundKey != "" {
				return scan.ErrStopScanning
			}
			if anchorChunks >= aesWxidAnchorChunks || !bytes.Contains(data, tok) {
				return nil
			}
			anchorChunks++
			anchored = true
			if handleRuns(col.feed(addr, data)) {
				return scan.ErrStopScanning
			}
			return nil
		})
		if foundKey == "" {
			for _, r := range col.flush() {
				if probeRun(r) {
					break
				}
			}
		}
		if foundKey != "" {
			logf(fmt.Sprintf("aes key: found in wxid-anchored chunk %d near %q", anchorChunks, wxidToken))
			return foundKey, attempts, nil
		}
		if attempts >= maxAttempts || time.Now().After(deadline) {
			return foundKey, attempts, nil
		}
	}

	// Full sweep: every printable run across every region.
	_, _ = src(func(_ uint32, _ string, addr uintptr, data []byte) error {
		if time.Now().After(deadline) || foundKey != "" {
			return scan.ErrStopScanning
		}
		for _, r := range col.feed(addr, data) {
			if probeRun(r) {
				return scan.ErrStopScanning
			}
		}
		nRead += int64(len(data))
		if mb := nRead >> 20; mb-lastLogMB >= 128 {
			lastLogMB = mb
			logf(fmt.Sprintf("scanning WeChat memory: %d MB read so far", mb))
		}
		return nil
	})
	if foundKey == "" {
		for _, r := range col.flush() {
			if probeRun(r) {
				break
			}
		}
	}
	if foundKey != "" && anchored {
		logf(fmt.Sprintf("aes key: found via full sweep (%d wxid-anchored chunk(s) probed first)", anchorChunks))
	}
	return foundKey, attempts, nil
}

// nativeMemorySource reads the running WeChat-family processes chunk by chunk
// (Windows only; on other platforms the scan helpers return
// ErrUnsupportedPlatform and this surfaces it via the returned error).
// nameRank orders scan targets so the main Weixin.exe client is walked before
// the WeChatAppEx helper processes (which are numerous and mostly JS runtime).
func nameRank(name string) int {
	n := strings.ToLower(name)
	if strings.Contains(n, "weixin") {
		return 0
	}
	if strings.Contains(n, "wechat") {
		return 1
	}
	return 2
}

func nativeMemorySource() (memorySource, error) {
	procs, err := scan.FindWeChatProcesses()
	if err != nil {
		return nil, err
	}
	if len(procs) == 0 {
		return nil, errors.New("no running WeChat process found (Weixin.exe). Start WeChat and log in, then retry")
	}
	// Scan the main client first: the raw DB-key sweep is time-budgeted, and
	// the key's heap is far more likely inside Weixin.exe than the
	// WeChatAppEx helper processes. Stable sort keeps toolhelp ordering
	// within the same name.
	sort.SliceStable(procs, func(i, j int) bool {
		return nameRank(procs[i].Name) < nameRank(procs[j].Name)
	})
	// Diagnosability: say exactly which processes the bridge sees. When the
	// panel log says this line, the "WeChat is running" question is answered
	// by the bridge itself, not by a screenshot.
	parts := make([]string, 0, len(procs))
	for _, p := range procs {
		parts = append(parts, fmt.Sprintf("%d:%s", p.PID, p.Name))
	}
	bridgeLogDebug("memory: found %d WeChat process(es): %s", len(procs), strings.Join(parts, ", "))
	return func(fn func(pid uint32, name string, addr uintptr, data []byte) error) (int64, []error) {
		var total int64
		var errs []error
		for _, p := range procs {
			var requestedStop bool
			inner := func(addr uintptr, data []byte) error {
				err := fn(p.PID, p.Name, addr, data)
				if err == scan.ErrStopScanning {
					requestedStop = true
				}
				return err
			}
			n, e := scan.ForEachReadableRegion(p.PID, maxScanBytes, inner)
			total += n
			errs = append(errs, e...)
			// A callback returning ErrStopScanning (deadline hit, cap
			// reached, key found) ends the walk for this process; stop
			// walking the remaining processes too instead of starting each
			// one and immediately stopping on its first chunk.
			if requestedStop {
				break
			}
		}
		return total, errs
	}, nil
}

// wxidTokenFromAccountDir shortens an account directory name to a token
// distinctive enough to anchor a memory search (e.g. "wxid_abc123").
func wxidTokenFromAccountDir(accountDir string) string {
	base := filepathBase(accountDir)
	base = strings.TrimSpace(base)
	if base == "" || base == "." {
		return ""
	}
	if strings.HasPrefix(strings.ToLower(base), "wxid_") {
		base = base[:min(len(base), len("wxid_")+10)]
	}
	if len(base) < 5 {
		return ""
	}
	return base
}

// dbKeyPage1Probe verifies 32-byte page keys against one preloaded database
// page 1 (MAC first, then magic; see sqlcipher.Page1Probe).
func dbKeyPage1Probe(dbPath string) (func(pageKey []byte) string, error) {
	p, err := sqlcipher.NewPage1Probe(dbPath)
	if err != nil {
		return nil, err
	}
	return func(pk []byte) string {
		macOK, magicOK := p.Check(pk)
		if macOK {
			return "mac"
		}
		if magicOK {
			return "magic"
		}
		return ""
	}, nil
}

// findDBKeyInMemory scans the running WeChat processes for the 32-byte
// database page key and verifies it against dbPath. Returns the 64-hex key
// and the verification label ("mac" / "magic").
func findDBKeyInMemory(dbPath, accountDir string, logf func(string)) (key, label string, attempts int, err error) {
	probe, err := dbKeyPage1Probe(dbPath)
	if err != nil {
		return "", "", 0, fmt.Errorf("cannot probe database %s: %w", displayDir(dbPath), err)
	}
	salt, _ := readDbSalt(dbPath)
	probeAny := probe
	if len(salt) == 16 {
		// Probe both SQLCipher modes: the candidate as a raw page key (fast,
		// microseconds) and as a passphrase run through the 256000-iteration
		// KDF. Used only on small candidate sets (disk blobs, hex strings) —
		// window sweeps keep the raw probe.
		probeAny = func(cand []byte) string {
			if lab := probe(cand); lab != "" {
				return lab
			}
			return probe(sqlcipher.DerivePageKey(cand, salt))
		}
	}

	// Last-known-good cache: per-account SQLCipher keys don't rotate, so a
	// key verified against this database before verifies again. Re-probe the
	// cached key against the live page 1 (raw and, if a salt is present,
	// passphrase-derived) — a wrong/rotated key fails and we fall through to
	// a full scan. This makes repeat key recovery instant (previously every
	// run re-walked the whole address space for ~4 min).
	if k, ok := loadKeyCache().lookup(dbPath); ok {
		if raw := hexToKeyBytes(k); raw != nil {
			if lab := probeAny(raw); lab != "" {
				bridgeLogDebug("db key scan: cache hit, verified (%s, cache)", lab)
				return k, lab + "-cache", 1, nil
			}
		}
		loadKeyCache().invalidate(dbPath)
		bridgeLogDebug("db key scan: cached key failed verification, removing and rescanning")
	}

	wxidToken := wxidTokenFromAccountDir(accountDir)
	bridgeLogDebug("db key scan: probing %s (page-1 probe ok), wxid token %q, account dir %q",
		displayDir(dbPath), wxidToken, accountDir)

	// WeChat 4.x never keeps the DB key as a plaintext blob in memory: the
	// resident material is a 32-byte mem_data XOR-masked with a hardcoded
	// internal key inside Weixin.dll (see keystream_core.go). Try that
	// de-obfuscation first — it is targeted and returns in seconds when the
	// wkt signature / mov-imm layout matches this version.
	dk, dl, moduleFound := scanDBKeyViaWeixinDll(dbPath, probe, logf)
	if dk != "" {
		bridgeLogDebug("db key scan: recovered via weixin.dll de-obfuscation (%s)", dl)
		loadKeyCache().remember(dbPath, dk)
		return dk, dl, 1, nil
	}

	k, l, attempts := "", "", 0
	if !moduleFound {
		// No weixin.dll (WeChat 3.x family): the key may still be a resident
		// plaintext blob, so the full raw/hex memory sweep applies.
		src, err := nativeMemorySource()
		if err != nil {
			return "", "", 0, err
		}
		var hexCs, rawCs int
		k, l, hexCs, rawCs = scanDBKeyMemory(src, probe, wxidToken, logf)
		bridgeLogDebug("db key scan: done hexCandidates=%d rawCandidates=%d attempts=%d verified=%q",
			hexCs, rawCs, hexCs+rawCs, l)
		attempts = hexCs + rawCs
	} else {
		// WeChat 4.x: the ModeRaw plaintext sweep provably cannot verify a
		// KDF-keyed DB (probe = raw page-key check), so a 200M-candidate
		// sweep is pure wasted minutes — skip it and go straight to disk.
		bridgeLogDebug("db key scan: 4.x weixin.dll found; skipping ModeRaw plaintext sweep (cannot verify a KDF-keyed DB)")
	}
	if k == "" {
		// Fall back to on-disk candidates: config files read directly, and
		// DPAPI-protected blobs unwrapped in this user session — raw windows
		// probe raw mode, hex-string spans probe both modes.
		bridgeLogDebug("db key scan: memory exhausted, trying protected-blob recovery")
		if dk, dl, _ := recoverDBKeyFromDisk(probe, probeAny, accountDir, logf); dk != "" {
			bridgeLogDebug("db key scan: recovered from disk blob (%s)", dl)
			k, l = dk, dl
		}
	}
	if k != "" {
		loadKeyCache().remember(dbPath, k)
	}
	return k, l, attempts, nil
}

// aesKeyProbe verifies a 16-character candidate AES key: decrypt the first
// V2 template block and check the image magic; "" on miss, "block-magic" on
// hit (the caller then confirms with a full template decrypt). The block
// check deliberately rejects the 2-byte "BM" magic (StrongBlockPayload) so
// a wrong key cannot pass the cheap prefilter at ~2^-16 per attempt; the
// authoritative gate is verifyAesKey's full-template StrongMediaPayload.
func aesKeyProbe(ct []byte) func(key16 []byte) string {
	ct = ct[:min(len(ct), 48)]
	return func(k16 []byte) string {
		if len(k16) != 16 || len(ct) < 16 {
			return ""
		}
		dec, derr := datdecrypt.DecryptAESBlock(k16, ct[:16])
		if derr != nil || len(dec) < 4 {
			return ""
		}
		if datdecrypt.StrongBlockPayload(dec) {
			return "block-magic"
		}
		return ""
	}
}

// findImageAesKeyInMemory scans WeChat memory for the 32-char image AES key
// string, probing with the V2 template ciphertext and confirming with a full
// template decrypt (verifyAesKey). files must be the on-disk _t.dat files.
// The accountDir supplies the wxid anchor; a non-empty token makes the scan
// start at the account-keyring region, where the image key actually lives.
//
// Mirrors CipherTalk's imageKeyService retry semantics: up to 3 attempts with
// a 2-second gap, because the key is only resident once images have been
// opened in the client. The guidance message below is the same advice
// CipherTalk shows its users.
func findImageAesKeyInMemory(accountDir string, files []string, logf func(string)) (key string, attempts int, err error) {
	ct, cerr := templateCiphertext(files)
	if cerr != nil {
		return "", 0, cerr
	}
	wxidToken := wxidTokenFromAccountDir(accountDir)
	probe := func(k16 []byte) string {
		if aesKeyProbe(ct)(k16) == "" {
			return ""
		}
		if ok, how := verifyAesKey(k16, files); ok {
			return how
		}
		return ""
	}
	const maxAesScanRetries = 3
	// One deadline shared across all retries: the scan must leave room for the
	// retry attempts (CipherTalk waits 2s between them so the user can open an
	// image chat / Moments and populate the key). aesScanDeadline per attempt
	// would multiply into ~6 minutes of scanning when the key is absent.
	scanDeadline := time.Now().Add(aesScanDeadline)
	for attempt := 1; attempt <= maxAesScanRetries; attempt++ {
		src, serr := nativeMemorySource()
		if serr != nil {
			return "", attempts, serr
		}
		logf(fmt.Sprintf("aes key: memory scan attempt %d/%d (anchor %q)", attempt, maxAesScanRetries, wxidToken))
		k, n, err2 := scanAesKeyMemory(src, probe, wxidToken, aesMaxKeyAttempts, scanDeadline, logf)
		attempts += n
		if err2 != nil {
			return "", attempts, err2
		}
		if len(k) >= 16 {
			return k, attempts, nil
		}
		// CipherTalk parity: the key enters memory only after images are
		// actually opened (thumbnails shown / Moments browsed). Give the
		// client a brief moment, then retry.
		if attempt < maxAesScanRetries && time.Now().Before(scanDeadline) {
			logf(fmt.Sprintf("aes key: not found after %d probe(s) (attempt %d/%d) — open a chat with images or Moments in WeChat so the image key is in memory; retrying in 2s", n, attempt, maxAesScanRetries))
			time.Sleep(2 * time.Second)
		}
	}
	return "", attempts, nil
}

func filepathBase(p string) string {
	if idx := strings.LastIndexAny(p, `/\`); idx >= 0 {
		return p[idx+1:]
	}
	return p
}
