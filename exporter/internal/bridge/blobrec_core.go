// Blob-based DB key recovery: when the 32-byte page key is not resident in
// readable WeChat memory, it may exist on disk either in the clear (config
// files) or as a Windows DPAPI-protected blob (same-user session).
//
// This file holds the platform-independent scanning core; the OS-specific
// halves (candidate discovery + CryptUnprotectData) live in
// blobrec_windows.go / blobrec_other.go.
package bridge

import (
	"encoding/hex"
)

// scanBytesForKeyAny probes every raw 32-byte window with probeRaw (fast, raw
// mode) and every 64-hex / wide-hex window with probeAny (which may also try
// the passphrase-KDF mode). Used by disk-blob recovery so a plaintext
// passphrase written as a hex string still verifies on a KDF-keyed DB.
func scanBytesForKeyAny(data []byte, probeRaw, probeAny func(pageKey []byte) string) (keyHex, label string) {
	if probeRaw == nil {
		return "", ""
	}
	if probeAny == nil {
		probeAny = probeRaw
	}
	for i := 0; i+32 <= len(data); i++ {
		if lab := probeRaw(data[i : i+32]); lab != "" {
			return hex.EncodeToString(data[i : i+32]), lab
		}
	}
	// ASCII hex spans.
	for _, sp := range hexSpans(data) {
		for i := 0; i+64 <= len(sp); i++ {
			raw, derr := hex.DecodeString(string(sp[i : i+64]))
			if derr != nil || len(raw) != 32 {
				continue
			}
			if lab := probeAny(raw); lab != "" {
				return hex.EncodeToString(raw), lab
			}
		}
	}
	// UTF-16LE wide hex ("3\x000\x00f\x00...").
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
				raw, derr := hex.DecodeString(string(sp[w : w+64]))
				if derr != nil || len(raw) != 32 {
					continue
				}
				if lab := probeAny(raw); lab != "" {
					return hex.EncodeToString(raw), lab
				}
			}
		}
		i = j
	}
	return "", ""
}

// scanBytesForKey probes one byte blob for the 32-byte DB key in every shape
// the client could store: raw 32-byte windows, ASCII 64-hex spans, and
// UTF-16LE wide-hex spans. Returns the 64-hex key and the probe label.
func scanBytesForKey(data []byte, probe func(pageKey []byte) string) (keyHex, label string) {
	if probe == nil {
		return "", ""
	}
	for i := 0; i+32 <= len(data); i++ {
		if lab := probe(data[i : i+32]); lab != "" {
			return hex.EncodeToString(data[i : i+32]), lab
		}
	}
	// ASCII hex spans.
	for _, sp := range hexSpans(data) {
		for i := 0; i+64 <= len(sp); i++ {
			raw, derr := hex.DecodeString(string(sp[i : i+64]))
			if derr != nil || len(raw) != 32 {
				continue
			}
			if lab := probe(raw); lab != "" {
				return hex.EncodeToString(raw), lab
			}
		}
	}
	// UTF-16LE wide hex ("3\x000\x00f\x00...").
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
				raw, derr := hex.DecodeString(string(sp[w : w+64]))
				if derr != nil || len(raw) != 32 {
					continue
				}
				if lab := probe(raw); lab != "" {
					return hex.EncodeToString(raw), lab
				}
			}
		}
		i = j
	}
	return "", ""
}
