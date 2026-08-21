package bridge

import (
	"bytes"
	"encoding/hex"
	"errors"
	"fmt"
	"os"
	"strings"

	"wechatsummary/exporter/internal/scan"
	"wechatsummary/exporter/internal/sqlcipher"
)

// autoFindResult is the JSON payload of /api/key/autofind.
type autoFindResult struct {
	Found        bool       `json:"found"`
	Key          string     `json:"key,omitempty"`          // 64-hex raw key or passphrase string
	Mode         string     `json:"mode,omitempty"`         // raw | derived(passphrase)
	Verification string     `json:"verification,omitempty"` // mac | magic (page-1 decrypt)
	Salt         string     `json:"salt"`                   // the database salt that was scanned for
	DBPath       string     `json:"dbPath"`
	Attempts     int        `json:"attempts"`            // candidate keys verified
	Hits         int        `json:"hits"`                // salt-pattern memory hits used
	SaltDumps    []saltDump `json:"saltDumps,omitempty"` // per-hit hex windows for diagnosis
	Reason       string     `json:"reason,omitempty"`    // human-readable outcome when not found
}

// saltDump is one memory hit's surroundings, hex-encoded, so that layouts
// that do not match the key+salt heuristic can be diagnosed from the data.
type saltDump struct {
	Process   string `json:"process"`
	PID       int    `json:"pid"`
	Address   string `json:"address"`
	SaltHex   string `json:"saltHex"`
	BeforeHex string `json:"beforeHex,omitempty"` // up to 128 bytes before the salt
	AfterHex  string `json:"afterHex,omitempty"`  // 96 bytes after the salt
}

// readDBSalt returns the SQLCipher salt: the first 16 bytes of the database
// file (WeChat 4.x WCDB page 1 layout: salt | payload | iv | mac). No key is
// needed to read it, so it can be used as a per-install memory-scan pattern.
func readDBSalt(path string) ([]byte, error) {
	f, err := os.Open(path)
	if err != nil {
		return nil, err
	}
	defer f.Close()
	salt := make([]byte, 16)
	n, err := f.Read(salt)
	if err != nil && n != 16 {
		return nil, fmt.Errorf("cannot read salt from %s: %w", path, err)
	}
	return salt, nil
}

// keyCandidatesFromWindows turns raw memory windows (each containing a salt
// occurrence somewhere inside) into ordered key candidates:
//
//   - raw: 64-hex 32-byte windows — the 32 bytes immediately before the salt,
//     the 32 bytes immediately after, then every 32-byte window of the full
//     buffer (step 1). Each is verified against the DB with ModeRaw, i.e. a
//     plain HMAC check — microseconds per attempt, so the sweep is cheap.
//   - pass: printable ASCII runs (passphrase mode is PBKDF2-heavy, so it is
//     capped separately by the caller).
//
// Candidates are deduplicated and the result order is deterministic.
func keyCandidatesFromWindows(windows [][]byte, salt []byte, maxRaw int) (raw []string, pass []string) {
	rawSet := make(map[string]bool)
	passSet := make(map[string]bool)
	addRaw := func(b []byte) {
		if len(b) != 32 {
			return
		}
		h := hex.EncodeToString(b)
		if !rawSet[h] && len(raw) < maxRaw {
			rawSet[h] = true
			raw = append(raw, h)
		}
	}
	addPass := func(s string) {
		s = strings.TrimSpace(s)
		if len(s) < 8 || len(s) > 48 {
			return
		}
		if !passSet[s] {
			passSet[s] = true
			pass = append(pass, s)
		}
	}

	for _, w := range windows {
		idx := bytes.Index(w, salt)
		if idx < 0 {
			// Window may be truncated; fall back to the full-buffer sweep.
			idx = -1
		}
		if idx >= 0 {
			addRaw(sliceOrNil(w, idx-32, idx))                     // key directly before salt
			addRaw(sliceOrNil(w, idx+len(salt), idx+len(salt)+32)) // key directly after salt
		}
		for i := 0; i+32 <= len(w); i++ {
			addRaw(w[i : i+32])
		}
		// printable runs as passphrase candidates (4.1+ derived mode)
		for _, run := range printableRuns(w) {
			addPass(string(run))
		}
	}
	return raw, pass
}

// sliceOrNil returns b[from:to] or nil when the range is invalid.
func sliceOrNil(b []byte, from, to int) []byte {
	if from < 0 || to > len(b) || from > to {
		return nil
	}
	return b[from:to]
}

// printableRuns splits b into maximal runs of printable ASCII (0x20..0x7E).
func printableRuns(b []byte) [][]byte {
	var runs [][]byte
	start := -1
	for i, c := range b {
		if c >= 0x20 && c < 0x7f {
			if start < 0 {
				start = i
			}
			continue
		}
		if start >= 0 {
			runs = append(runs, b[start:i])
			start = -1
		}
	}
	if start >= 0 {
		runs = append(runs, b[start:])
	}
	return runs
}

// verifyCandidates checks raw (64-hex) and passphrase candidates against the
// database. Raw candidates use ModeRaw (fast HMAC), falling back to the
// page-1 magic probe (lenient open) when the HMAC parameters do not match;
// passphrases use ModeDerived only (PBKDF2, expensive). Returns the first
// verified key with its verification source.
func verifyCandidates(dbPath string, rawCandidates, passCandidates []string) (key, mode, verification string, attempts int) {
	for _, h := range rawCandidates {
		attempts++
		b, err := hex.DecodeString(h)
		if err != nil || len(b) != 32 {
			continue
		}
		f, oerr := sqlcipher.Open(dbPath, b, sqlcipher.ModeRaw, true)
		if oerr == nil {
			f.Close()
			return h, "raw", "mac", attempts
		}
		if macOK, magicOK, perr := sqlcipher.ProbePageKey(dbPath, b); perr == nil && magicOK {
			if lf, lerr := sqlcipher.OpenLenient(dbPath, b, sqlcipher.ModeRaw, true); lerr == nil {
				lf.Close()
				return h, "raw", "magic", attempts
			}
			_ = macOK
		}
	}
	for _, p := range passCandidates {
		attempts++
		f, oerr := sqlcipher.Open(dbPath, []byte(p), sqlcipher.ModeDerived, true)
		if oerr != nil {
			continue
		}
		f.Close()
		return p, "derived(passphrase)", "mac", attempts
	}
	return "", "", "", attempts
}

// collectSaltDumps renders per-hit hex windows for diagnostics.
func collectSaltDumps(hits []scan.Hit, salt []byte) []saltDump {
	var out []saltDump
	for _, h := range hits {
		if h.Pattern != "dbsalt" && h.Pattern != scan.DefaultPatterns[0].Name {
			continue
		}
		raw, err := hex.DecodeString(h.WindowHex)
		if err != nil || len(raw) == 0 {
			continue
		}
		idx := bytes.Index(raw, salt)
		if idx < 0 {
			idx = 0
		}
		d := saltDump{
			Process: h.Process,
			PID:     int(h.PID),
			Address: fmt.Sprintf("0x%X", h.Address),
			SaltHex: hex.EncodeToString(salt),
		}
		if before := sliceOrNil(raw, maxInt(0, idx-128), idx); len(before) > 0 {
			d.BeforeHex = hex.EncodeToString(before)
		}
		if after := sliceOrNil(raw, idx+len(salt), idx+len(salt)+96); len(after) > 0 {
			d.AfterHex = hex.EncodeToString(after)
		}
		out = append(out, d)
	}
	return out
}

func maxInt(a, b int) int {
	if a > b {
		return a
	}
	return b
}

// autoFindPatterns builds the memory-scan pattern list for autofind: the
// per-install database salt first (precise), then the community 4.x salt as a
// fallback anchor for layouts where the two differ.
func autoFindPatterns(dbSalt []byte) []scan.Pattern {
	patterns := []scan.Pattern{{
		Name:  "dbsalt",
		Bytes: append([]byte(nil), dbSalt...),
		Hex:   hex.EncodeToString(dbSalt),
	}}
	community := scan.DefaultPatterns[0]
	if !bytes.Equal(community.Bytes, dbSalt) {
		patterns = append(patterns, community)
	}
	return patterns
}

// collectSaltWindows extracts raw windows from salt-pattern hits.
func collectSaltWindows(hits []scan.Hit) [][]byte {
	var windows [][]byte
	for _, h := range hits {
		if h.Pattern != "dbsalt" && h.Pattern != scan.DefaultPatterns[0].Name {
			continue
		}
		b, err := hex.DecodeString(h.WindowHex)
		if err != nil || len(b) == 0 {
			continue
		}
		windows = append(windows, b)
	}
	return windows
}

// windowsByProcess groups hits by process for display/debug.
func windowsByProcess(hits []scan.Hit) map[string]int {
	out := map[string]int{}
	for _, h := range hits {
		if h.Pattern == "dbsalt" || h.Pattern == scan.DefaultPatterns[0].Name {
			out[fmt.Sprintf("%s#%d", h.Process, h.PID)]++
		}
	}
	return out
}

var errNoDatabase = errors.New("no WeChat database found; provide dbPath explicitly")
