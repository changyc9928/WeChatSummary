package bridge

import (
	"bytes"
	"encoding/binary"
	"encoding/hex"
	"os"

	"wechatsummary/exporter/internal/sqlcipher"
)

// WeChat 4.x database-key de-obfuscation core. The resident key is never a
// plaintext blob: the real passphrase is
//
//	passphrase = internal_key XOR mem_data
//
// where internal_key is a 32-byte constant compiled into Weixin.dll (found by
// scanning the module for either the 4 x `mov rdx,imm64` + `test rax,rax`
// sequence (ChatShadow wechat_v4_bin) or the wkt "keystream signature"
// function prologue whose trailing `mov [rsp+..],rax; movabs rax,imm64`
// markers carry the absolute addresses of the key globals), and mem_data is a
// 32-byte resident blob anchored near well-known WeChat string markers.
//
// The module bytes are pure data here; all functions in this file are
// cross-platform and unit-tested against synthetic module images. The Windows
// glue (keystream_windows.go) feeds them real Weixin.dll bytes.

// readDbSalt returns the first 16 bytes of the database page 1 (the
// SQLCipher salt the KDF mixes the passphrase with).
func readDbSalt(dbPath string) ([]byte, error) {
	f, err := os.Open(dbPath)
	if err != nil {
		return nil, err
	}
	defer f.Close()
	page1 := make([]byte, sqlcipher.PageSize)
	n, err := f.Read(page1)
	if err != nil && n == 0 {
		return nil, err
	}
	return page1[:16], nil
}

// weixinKeySignature is the 87-byte "keystream signature": the prologue of a
// Weixin.dll function that copies the key string (WCDB SSO string copy from
// struct offsets 0x2B8/0x2C8/0x2D0) and ends right before a `movabs rax,imm64`
// whose immediate is an absolute address inside the module. Compiled from the
// hex-string constant stored in wechat_key_tool.dll (file offset 0x53aef).
var weixinKeySignature = mustHexBytes(
	"83ec404889d64889cb0f57c00f1142100f11024c8bb1c80200004883b9d0020000107209" +
		"488b9bb8020000eb074881c3b80200004d85f60f880a0200004983fe10736d4c897610" +
		"48c746180f0000000f10030f110648b8")

// weixinKeyMarkers are the three wkt "keystream marker" code fragments that
// follow the signature match. Each is exactly the 7 bytes
// `mov [rsp+0x20/28/30],rax; movabs rax,` — the 8-byte imm64 at marker+7 is an
// absolute data address inside Weixin.dll (the obfuscated key / keystream).
var weixinKeyMarkers = [][]byte{
	mustHexBytes("488944242048b8"),
	mustHexBytes("488944242848b8"),
	mustHexBytes("488944243048b8"),
}

// chatShadowMarkers are Weixin.dll .rdata string anchors around which the
// 32-byte mem_data blob lives (ChatShadow wechat_v4_bin.cpp).
var chatShadowMarkers = []string{
	"g_voice_input_show_note_placeholder_text",
	"clicfg_xwechat",
}

// memDataFilter rejects a 32-byte window when any byte appears 3 or more
// times (XOR masks / key material are high-entropy; strings, pointers and
// code are not).
func memDataFilter(w []byte) bool {
	if len(w) < 32 {
		return false
	}
	var freq [256]uint8
	for _, c := range w[:32] {
		freq[c]++
		if freq[c] >= 3 {
			return false
		}
	}
	return true
}

// xorBytes32 XORs two 32-byte slices into a fresh 32-byte slice.
func xorBytes32(a, b []byte) []byte {
	out := make([]byte, 32)
	for i := 0; i < 32; i++ {
		out[i] = a[i] ^ b[i]
	}
	return out
}

// reverseBytes32 returns the reversed-byte reinterpretation of a 32-byte key
// (some builds store key chunks reversed inside movabs immediates).
func reverseBytes32(key []byte) []byte {
	out := make([]byte, len(key))
	for i := range key {
		out[len(key)-1-i] = key[i]
	}
	return out
}

// indexBytes finds the first occurrence of needle in hay, or -1.
func indexBytes(hay, needle []byte) int {
	return bytes.Index(hay, needle)
}

// movImmMatch describes one ChatShadow-layout internal-key location: the
// 32-byte key (4 x `48 BA <imm64>` concatenated) and the module offset of the
// sequence start.
type movImmMatch struct {
	Key []byte
	At  int
}

// extractInternalKeyMovImm scans module bytes at/after from for the ChatShadow
// layout: 4 x `48 BA <imm64>` (mov rdx,imm64) within a relaxed window of 80
// bytes, followed by `48 85 C0` (test rax,rax). The four imm64 immediates
// concatenated are the 32-byte internal key. Returns nil when absent; the
// returned match gives the key and where it was found.
func extractInternalKeyMovImm(mod []byte, from int) *movImmMatch {
	const terminator = 0xC0 // 48 85 C0 = test rax,rax
	for i := from; i+10 < len(mod); i++ {
		if mod[i] != 0x48 || mod[i+1] != 0xBA {
			continue
		}
		key := make([]byte, 0, 32)
		pos := i
		ok := true
		for g := 0; g < 4 && ok; g++ {
			if pos+10 > len(mod) || mod[pos] != 0x48 || mod[pos+1] != 0xBA {
				ok = false
				break
			}
			key = append(key, mod[pos+2:pos+10]...)
			// relax forward up to 80 bytes from the scan start to the next
			// 48 BA or the 48 85 C0 terminator
			limit := i + 80
			if limit > len(mod)-2 {
				limit = len(mod) - 2
			}
			next := pos + 10
			reachedTerm := false
			for next < limit {
				if mod[next] == 0x48 && mod[next+1] == 0xBA {
					break
				}
				if mod[next] == 0x48 && mod[next+1] == 0x85 && mod[next+2] == terminator {
					reachedTerm = true
					break
				}
				next++
			}
			if reachedTerm {
				// found the terminator: valid only after the 4th immediate
				if g != 3 {
					ok = false
				}
				pos = next
				break
			}
			pos = next
			if pos >= limit {
				ok = false
			}
		}
		if ok && len(key) == 32 && pos+3 <= len(mod) &&
			mod[pos] == 0x48 && mod[pos+1] == 0x85 && mod[pos+2] == terminator {
			return &movImmMatch{Key: key, At: i}
		}
	}
	return nil
}

// ripRelativeTargets scans the module bytes in [from,to) for RIP-relative
// `lea r64,[rip+rel32]` / `mov r64,[rip+rel32]` (48 8D/8B /?05) and `movabs
// r64,imm64` (48 B8..48 BF). Each hit yields the absolute target address the
// instruction references — in this version These are exactly how the matched
// key function reaches its resident key globals. from/to are offsets into mod;
// moduleBase anchors the RIP math (instruction VA = moduleBase+offset).
func ripRelativeTargets(mod []byte, from, to int, moduleBase uintptr) []uintptr {
	if to > len(mod) {
		to = len(mod)
	}
	var out []uintptr
	for i := from; i+7 <= to; {
		b0 := mod[i]
		if b0 != 0x48 && b0 != 0x4C {
			i++
			continue
		}
		b1 := mod[i+1]
		b2 := mod[i+2]
		// lea/mov r64,[rip+rel32]: 48/4C 8D/8B with ModRM mod=00 r/m=101
		if (b1 == 0x8D || b1 == 0x8B) && b2&0xC7 == 0x05 && i+7 <= to {
			rel := int32(binary.LittleEndian.Uint32(mod[i+3 : i+7]))
			out = append(out, moduleBase+uintptr(i)+7+uintptr(int64(rel)))
			i += 7
			continue
		}
		// movabs r64, imm64
		if b1 >= 0xB8 && b1 <= 0xBF && i+10 <= to {
			out = append(out, uintptr(binary.LittleEndian.Uint64(mod[i+2:i+10])))
			i += 10
			continue
		}
		i++
	}
	return out
}

// signatureMatch finds the wkt "keystream signature" inside module bytes and
// returns the absolute data addresses carried by the trailing movabs
// immediates (each marker match contributes its imm64). Returns nil when the
// signature does not match this module's code layout.
func signatureMatch(mod []byte) (idx int, targets []uintptr) {
	idx = indexBytes(mod, weixinKeySignature)
	if idx < 0 {
		return -1, nil
	}
	from := idx + len(weixinKeySignature)
	if from+15 > len(mod) {
		return -1, nil
	}
	window := mod[from:min(from+0x100, len(mod))]
	var out []uintptr
	for _, marker := range weixinKeyMarkers {
		mi := indexBytes(window, marker)
		if mi < 0 {
			continue
		}
		if mi+15 > len(window) {
			continue
		}
		out = append(out, uintptr(binary.LittleEndian.Uint64(window[mi+7:mi+15])))
	}
	return idx, out
}

// markerWindows returns the byte ranges around every occurrence of the marker
// strings in module bytes (ChatShadow's ±2KiB window). start<=end byte offsets
// into mod; empty when no marker is present.
func markerWindows(mod []byte, window int) [][2]int {
	var out [][2]int
	for _, mk := range chatShadowMarkers {
		mb := []byte(mk)
		for off := 0; ; {
			i := indexBytes(mod[off:], mb)
			if i < 0 {
				break
			}
			abs := off + i
			start := abs - window
			if start < 0 {
				start = 0
			}
			end := abs + len(mb) + window
			if end > len(mod) {
				end = len(mod)
			}
			out = append(out, [2]int{start, end})
			off = abs + len(mb)
		}
	}
	return out
}

// memDataCandidates collects the filtered 32-byte windows of mod inside the
// given ranges. Each returned slice aliases mod (the caller XORs immediately).
// capTotal bounds the total number of candidates returned.
func memDataCandidates(mod []byte, ranges [][2]int, capTotal int) [][]byte {
	var out [][]byte
	for _, r := range ranges {
		start, end := r[0], r[1]
		if start < 0 {
			start = 0
		}
		if end > len(mod) {
			end = len(mod)
		}
		for k := start; k+32 <= end && len(out) < capTotal; k++ {
			if memDataFilter(mod[k : k+32]) {
				out = append(out, mod[k:k+32])
			}
		}
		if len(out) >= capTotal {
			break
		}
	}
	return out
}

// memDataCandidatesAll scans every offset of mod (fallback when no marker
// anchors exist in this version's layout). capTotal bounds candidates.
func memDataCandidatesAll(mod []byte, capTotal int) [][]byte {
	var out [][]byte
	for k := 0; k+32 <= len(mod) && len(out) < capTotal; k++ {
		if memDataFilter(mod[k : k+32]) {
			out = append(out, mod[k:k+32])
		}
	}
	return out
}

// passphraseCandidates combines every internal key with every mem_data window
// (internal key count x mem count, capped). The src slice reports which
// internal-key index produced each candidate ("" labels come from the caller).
func passphraseCandidates(internalKeys [][]byte, mems [][]byte, capTotal int) ([][]byte, []int) {
	var out [][]byte
	var src []int
	for ik, ikb := range internalKeys {
		for _, m := range mems {
			if len(out) >= capTotal {
				break
			}
			out = append(out, xorBytes32(ikb, m))
			src = append(src, ik)
		}
	}
	return out, src
}

// hasMarkers reports which of the known ChatShadow marker strings occur in
// module bytes (diagnostics for the panel log).
func hasMarkers(mod []byte) []string {
	var found []string
	for _, mk := range chatShadowMarkers {
		if bytes.Contains(mod, []byte(mk)) {
			found = append(found, mk)
		}
	}
	return found
}

func mustHexBytes(s string) []byte {
	b, err := hex.DecodeString(s)
	if err != nil {
		panic("bad hex constant: " + err.Error())
	}
	return b
}

// keyProbeFunc verifies a derived 32-byte page key against the preloaded
// database page 1; returns "" on miss, or a label ("mac"/"magic") on hit.
type keyProbeFunc func(pageKey []byte) string

// dllOffsets maps an absolute imm64 VA back to module-image offsets. The
// immediate is a link-time VA; the module may be loaded at its preferred base
// (0x180000000 for WeChat 4.x) or relocated by ASLR, so both interpretations
// are tried (bounded to the module image by the caller).
func dllOffsets(va, base uintptr) []int {
	out := []int{int(va - base)}
	if base != 0x180000000 {
		out = append(out, int(va-0x180000000))
	}
	return out
}
