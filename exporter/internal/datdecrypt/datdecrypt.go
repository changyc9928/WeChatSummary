// Package datdecrypt decrypts WeChat media cache files (*.dat) — images and
// audio stored on disk are wrapped in WeChat's own container formats:
//
//	V1/V2: 07 08 56 31/32 08 07 [aesSize:4 LE] [xorSize:4 LE] [pad] AES-128-ECB(...) raw XOR(...)
//	V3:    no signature; the whole file is XOR'd with a per-file key
//	V4:    like V2 but with a different (per-version) key schedule
//
// The algorithm is a direct Go port of CipherTalk's open-source
// electron/services/datDecryptCore.ts + imageKeyService.ts
// (github.com/ILoveBingLu/CipherTalk, CC BY-NC-SA 4.0):
//
//   - V1 files use a constant AES key: the first 16 chars of MD5("0") as raw
//     ASCII, i.e. "cfcd208495d565ef" (their DEFAULT_V1_AES_KEY).
//   - V2 files use a per-account AES key that must be recovered from the
//     running WeChat (the bridge does this via wx_key's
//     wkt_scan_image_key_auth, see internal/bridge).
//   - The XOR key is (almost always) derivable from the file itself: XOR the
//     first byte against a known JPEG/PNG/GIF/BMP/WebP signature byte, or,
//     more robustly, from _t.dat thumbnail "template" files.
package datdecrypt

import (
	"bytes"
	"crypto/aes"
	"crypto/md5"
	"encoding/binary"
	"encoding/hex"
	"errors"
	"fmt"
)

// DefaultV1AESKey is CipherTalk's constant key for V1 files: the first 16
// hex chars of MD5("0"), taken as raw ASCII bytes ("cfcd208495d565ef").
var DefaultV1AESKey = func() []byte {
	d := md5.Sum([]byte("0"))
	return AsciiKey16(hex.EncodeToString(d[:])[:16])
}()

// AsciiKey16 returns the first 16 bytes of s interpreted as ASCII
// (CipherTalk's asciiKey16).
func AsciiKey16(s string) []byte {
	if len(s) < 16 {
		return nil
	}
	return []byte(s[:16])
}

var (
	v1Sig = []byte{0x07, 0x08, 0x56, 0x31, 0x08, 0x07}
	v2Sig = []byte{0x07, 0x08, 0x56, 0x32, 0x08, 0x07}
)

// DetectVersion returns 1 for V1 files, 2 for V2 files, 0 otherwise
// (V3-style whole-file XOR, or plaintext). Mirrors CipherTalk's getDatVersion.
func DetectVersion(data []byte) int {
	if len(data) >= 6 {
		if eq(data[:6], v1Sig) {
			return 1
		}
		if eq(data[:6], v2Sig) {
			return 2
		}
	}
	return 0
}

func eq(a, b []byte) bool {
	if len(a) != len(b) {
		return false
	}
	for i := range a {
		if a[i] != b[i] {
			return false
		}
	}
	return true
}

// DetectXorKeyFromHeader derives the XOR key from a V3-style file by trying
// each known image signature: the key must turn the first signature bytes
// into a full signature match. Returns (key, true) or (0, false).
func DetectXorKeyFromHeader(data []byte) (byte, bool) {
	sigs := [][]byte{
		{0xff, 0xd8, 0xff},       // jpg
		{0x89, 0x50, 0x4e, 0x47}, // png
		{0x47, 0x49, 0x46, 0x38}, // gif
		{0x42, 0x4d},             // bmp
		{0x52, 0x49, 0x46, 0x46}, // webp
	}
	if len(data) == 0 {
		return 0, false
	}
	for _, sig := range sigs {
		if len(data) < len(sig) {
			continue
		}
		key := data[0] ^ sig[0]
		ok := true
		for i := 1; i < len(sig); i++ {
			if data[i]^key != sig[i] {
				ok = false
				break
			}
		}
		if ok {
			return key, true
		}
	}
	return 0, false
}

// DecryptV3 whole-file XOR (meta: keeps trailing NULs; the caller strips
// them with StripTrailingNul, matching CipherTalk's plaintext-passthrough).
func DecryptV3(data []byte, xorKey byte) []byte {
	out := make([]byte, len(data))
	for i, b := range data {
		out[i] = b ^ xorKey
	}
	return out
}

// decryptAES128ECB decrypts raw ECB blocks of len(data) (multiple of 16).
func decryptAES128ECB(aesKey, data []byte) ([]byte, error) {
	if len(aesKey) != 16 {
		return nil, fmt.Errorf("aes key must be 16 bytes, got %d", len(aesKey))
	}
	block, err := aes.NewCipher(aesKey)
	if err != nil {
		return nil, err
	}
	if len(data)%aes.BlockSize != 0 {
		return nil, fmt.Errorf("aes data length %d is not block aligned", len(data))
	}
	out := make([]byte, len(data))
	for off := 0; off < len(data); off += aes.BlockSize {
		block.Decrypt(out[off:off+aes.BlockSize], data[off:off+aes.BlockSize])
	}
	return out, nil
}

// strictRemovePadding validates and strips PKCS7 padding
// (CipherTalk's strictRemovePadding).
func strictRemovePadding(data []byte) ([]byte, error) {
	if len(data) == 0 {
		return nil, errors.New("decrypted result empty")
	}
	paddingLength := int(data[len(data)-1])
	if paddingLength == 0 || paddingLength > 16 || paddingLength > len(data) {
		return nil, errors.New("PKCS7 padding length illegal")
	}
	for i := len(data) - paddingLength; i < len(data); i++ {
		if data[i] != byte(paddingLength) {
			return nil, errors.New("PKCS7 padding content illegal")
		}
	}
	return data[:len(data)-paddingLength], nil
}

// DecryptAESBlock decrypts a single raw ECB block (no padding handling).
// Used to validate a recovered media AES key against template ciphertext.
func DecryptAESBlock(aesKey, block []byte) ([]byte, error) {
	if len(block) != aes.BlockSize {
		return nil, fmt.Errorf("block must be %d bytes, got %d", aes.BlockSize, len(block))
	}
	c, err := aes.NewCipher(aesKey)
	if err != nil {
		return nil, err
	}
	out := make([]byte, aes.BlockSize)
	c.Decrypt(out, block)
	return out, nil
}

// DecryptAES128ECBPadded decrypts AES-128-ECB data and strips the PKCS7
// padding (CipherTalk's createDecipheriv('aes-128-ecb', key, null) with
// autoPadding). Used for emoji encryptUrl payloads whose key is the first 16
// hex chars of md5(aeskey).
func DecryptAES128ECBPadded(aesKey, data []byte) ([]byte, error) {
	dec, derr := decryptAES128ECB(aesKey, data)
	if derr != nil {
		return nil, derr
	}
	return strictRemovePadding(dec)
}

// DecryptV4 handles the V1/V2 container: [sig6][aesSize:4][xorSize:4][pad1]
// AES-128-ECB(pkcs7) raw XOR. aesKey must be 16 bytes.
func DecryptV4(data []byte, xorKey byte, aesKey []byte) ([]byte, error) {
	if len(data) < 0x0f {
		return nil, errors.New("file too small to parse")
	}
	header := data[:0x0f]
	rest := data[0x0f:]

	aesSize := int(int32(binary.LittleEndian.Uint32(header[6:10])))
	xorSize := int(int32(binary.LittleEndian.Uint32(header[10:14])))

	// AES data must be aligned to 16 bytes (PKCS7); when aesSize % 16 == 0 an
	// extra full block of padding is present.
	remainder := (aesSize%16 + 16) % 16
	alignedAesSize := aesSize + (16 - remainder)

	if alignedAesSize > len(rest) {
		return nil, errors.New("AES data length exceeds file length")
	}

	var unpadded []byte
	aesData := rest[:alignedAesSize]
	if len(aesData) > 0 {
		dec, derr := decryptAES128ECB(aesKey, aesData)
		if derr != nil {
			return nil, derr
		}
		var perr error
		unpadded, perr = strictRemovePadding(dec)
		if perr != nil {
			return nil, perr
		}
	}

	remaining := rest[alignedAesSize:]
	if xorSize < 0 || xorSize > len(remaining) {
		return nil, errors.New("XOR data length illegal")
	}

	var out []byte
	out = append(out, unpadded...)
	rawLen := len(remaining) - xorSize
	out = append(out, remaining[:rawLen]...)
	for _, b := range remaining[rawLen:] {
		out = append(out, b^xorKey)
	}
	return out, nil
}

// Decrypt dispatches on the container version. V1 uses the constant key; V2
// needs the per-account aesKey (16 bytes); version 0 is whole-file XOR.
func Decrypt(data []byte, xorKey byte, aesKey []byte) ([]byte, error) {
	version := DetectVersion(data)
	switch version {
	case 0:
		return DecryptV3(data, xorKey), nil
	case 1:
		return DecryptV4(data, xorKey, DefaultV1AESKey)
	case 2:
		if len(aesKey) != 16 {
			return nil, errors.New("V2 file needs the account image AES key (16 bytes)")
		}
		return DecryptV4(data, xorKey, aesKey)
	default:
		return nil, fmt.Errorf("unknown dat version %d", version)
	}
}

// DetectExt sniffs the decrypted payload and returns ".png"/".jpg"/".gif"/
// ".webp"/".bmp" or "" (CipherTalk's detectImageExtension, including the
// wxgf header-skip offsets).
func DetectExt(data []byte) string {
	if len(data) < 12 {
		return ""
	}
	if data[0] == 'w' && data[1] == 'x' && data[2] == 'g' && data[3] == 'f' {
		for _, off := range []int{0x10, 0x12, 0x14, 0x18, 0x20, 0xd0, 0x100} {
			if len(data) > off+12 {
				if e := detectAt(data[off:]); e != "" {
					return e
				}
			}
		}
		for i := 4; i < len(data)-3 && i < 512; i++ {
			if data[i] == 0xff && data[i+1] == 0xd8 && data[i+2] == 0xff {
				return ".jpg"
			}
		}
		return ""
	}
	return detectAt(data)
}

func detectAt(b []byte) string {
	if len(b) < 12 {
		return ""
	}
	if b[0] == 0x47 && b[1] == 0x49 && b[2] == 0x46 {
		return ".gif"
	}
	if b[0] == 0x89 && b[1] == 0x50 && b[2] == 0x4e && b[3] == 0x47 {
		return ".png"
	}
	if b[0] == 0xff && b[1] == 0xd8 && b[2] == 0xff {
		return ".jpg"
	}
	if b[0] == 0x52 && b[1] == 0x49 && b[2] == 0x46 && b[3] == 0x46 &&
		b[8] == 0x57 && b[9] == 0x45 && b[10] == 0x42 && b[11] == 0x50 {
		return ".webp"
	}
	if b[0] == 0x42 && b[1] == 0x4d {
		return ".bmp"
	}
	// HEIC/HEIF: WeChat 4.x stores HEVC-encoded chat images as ISO BMFF
	// (box size + "ftyp" at offset 4 + major brand).
	if b[4] == 'f' && b[5] == 't' && b[6] == 'y' && b[7] == 'p' {
		switch string(b[8:12]) {
		case "heic", "heix", "hevc", "hevx", "mif1", "msf1":
			return ".heic"
		}
	}
	return ""
}

// StripTrailingNul trims trailing 0x00 bytes (old WeChat wrote plaintext
// payloads with NUL padding).
func StripTrailingNul(data []byte) []byte {
	end := len(data)
	for end > 0 && data[end-1] == 0x00 {
		end--
	}
	return data[:end]
}

// XorKeyFromTemplate computes the XOR key from a _t.dat template file by
// majority vote over (last-2-bytes) pairs with CipherTalk's consistency
// check xorKey = x ^ 0xFF and y ^ 0xD9 == xorKey.
func XorKeyFromTemplate(pairs [][2]byte) (byte, bool) {
	counts := map[[2]byte]int{}
	for _, p := range pairs {
		counts[p]++
	}
	var best [2]byte
	bestN := 0
	for p, n := range counts {
		if n > bestN {
			best, bestN = p, n
		}
	}
	if bestN == 0 {
		return 0, false
	}
	xorKey := best[0] ^ 0xff
	if best[1]^0xd9 != xorKey {
		return 0, false
	}
	return xorKey, true
}

// LooksLikeMediaPayload reports whether the payload is a known image. Used
// to validate a recovered AES key against template ciphertext.
func LooksLikeMediaPayload(data []byte) bool {
	return len(data) >= 4 && DetectExt(data) != ""
}

// StrongMediaPayload reports whether data begins with a structurally valid
// image. Unlike LooksLikeMediaPayload — which accepts any DetectExt hit,
// including a mere 2-byte "BM" — this requires the full signature plus a
// coherent header layout (markers, chunk sizes, dimensions, planes/bpp).
// A wrong AES key therefore passes with probability ~2^-40+ instead of
// ~2^-16, which is the difference between a false "BM" accept at ~14.9M
// scan attempts and a real key.
func StrongMediaPayload(data []byte) bool {
	if len(data) < 12 {
		return false
	}
	if data[0] == 0xff && data[1] == 0xd8 && data[2] == 0xff {
		return strongJPEG(data)
	}
	if bytes.HasPrefix(data, []byte{0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a}) {
		// PNG: signature + IHDR chunk (len 13, type "IHDR", sane width).
		if len(data) < 24 {
			return false
		}
		if !bytes.Equal(data[8:12], []byte{0x00, 0x00, 0x00, 0x0d}) {
			return false
		}
		if string(data[12:16]) != "IHDR" {
			return false
		}
		return binary.BigEndian.Uint32(data[16:20]) > 0
	}
	if bytes.HasPrefix(data, []byte("GIF87a")) || bytes.HasPrefix(data, []byte("GIF89a")) {
		// GIF: logical screen descriptor with sane dimensions.
		if len(data) < 10 {
			return false
		}
		w := binary.LittleEndian.Uint16(data[6:8])
		h := binary.LittleEndian.Uint16(data[8:10])
		return w > 0 && h > 0
	}
	if len(data) >= 20 && bytes.HasPrefix(data, []byte("RIFF")) && bytes.Equal(data[8:12], []byte("WEBP")) {
		switch string(data[12:16]) {
		case "VP8 ", "VP8L", "VP8X":
			return true
		}
		return false
	}
	if data[0] == 'B' && data[1] == 'M' {
		return strongBMP(data)
	}
	// HEIC/HEIF: ISO BMFF ftyp box at +4 with a known major brand.
	if len(data) >= 16 && data[4] == 'f' && data[5] == 't' && data[6] == 'y' && data[7] == 'p' {
		switch string(data[8:12]) {
		case "heic", "heix", "hevc", "hevx", "mif1", "msf1":
			return true
		}
	}
	return false
}

// StrongBlockPayload is the cheap per-window prefilter used by the memory
// scan: multi-byte image magic only, deliberately excluding the 2-byte "BM"
// (a 16-byte block cannot validate a BMP header). The authoritative gate is
// StrongMediaPayload on the full template decrypt.
func StrongBlockPayload(b []byte) bool {
	if len(b) < 4 {
		return false
	}
	if b[0] == 0x47 && b[1] == 0x49 && b[2] == 0x46 {
		return true // gif
	}
	if b[0] == 0x89 && b[1] == 0x50 && b[2] == 0x4e && b[3] == 0x47 {
		return true // png
	}
	if b[0] == 0xff && b[1] == 0xd8 && b[2] == 0xff {
		return true // jpg
	}
	if b[0] == 0x52 && b[1] == 0x49 && b[2] == 0x46 && b[3] == 0x46 && len(b) >= 12 &&
		b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P' {
		return true // webp
	}
	if len(b) >= 12 && b[4] == 'f' && b[5] == 't' && b[6] == 'y' && b[7] == 'p' {
		switch string(b[8:12]) {
		case "heic", "heix", "hevc", "hevx", "mif1", "msf1":
			return true
		}
	}
	return false
}

// LooksLikeStrongMedia is the export accept-path gate: a structurally valid
// image or a wxgf container (CipherTalk's looksLikeNativeImagePayload also
// accepts wxgf). Weak 2-byte signatures are rejected.
func LooksLikeStrongMedia(data []byte) bool {
	if StrongMediaPayload(data) {
		return true
	}
	return len(data) >= 4 && data[0] == 'w' && data[1] == 'x' && data[2] == 'g' && data[3] == 'f'
}

// strongJPEG walks the marker segments after SOI and accepts only when an
// APP0+JFIF, APP1+Exif, or a well-formed SOF segment is found — a real
// image always has one of these; a wrong-key decrypt essentially never does.
func strongJPEG(b []byte) bool {
	i := 2 // skip SOI ff d8
	for i+4 <= len(b) {
		if b[i] != 0xff {
			return false
		}
		m := b[i+1]
		switch {
		case m == 0xd8 || m == 0x01 || (m >= 0xd0 && m <= 0xd7):
			i += 2 // SOI / TEM / RSTn: no payload
		case m == 0xd9:
			return false // EOI before any structure
		case m == 0xe0 || m == 0xe1:
			// APP0 (JFIF) / APP1 (Exif): identifier right after length.
			if i+8 > len(b) {
				return false
			}
			segLen := int(binary.BigEndian.Uint16(b[i+2 : i+4]))
			if segLen < 2 || i+4+segLen > len(b) {
				return false
			}
			if (m == 0xe0 && bytes.Equal(b[i+4:i+8], []byte("JFIF"))) ||
				(m == 0xe1 && bytes.Equal(b[i+4:i+8], []byte("Exif"))) {
				return true
			}
			i += 2 + segLen
		case m >= 0xc0 && m <= 0xcf && m != 0xc4 && m != 0xc8 && m != 0xcc:
			// SOF: precision + height/width + component count.
			if i+10 > len(b) {
				return false
			}
			segLen := int(binary.BigEndian.Uint16(b[i+2 : i+4]))
			if segLen < 7 || i+4+segLen > len(b) {
				return false
			}
			h := binary.BigEndian.Uint16(b[i+5 : i+7])
			w := binary.BigEndian.Uint16(b[i+7 : i+9])
			n := b[i+9]
			if h > 0 && w > 0 && n >= 1 && n <= 4 {
				return true
			}
			return false
		default:
			if i+4 > len(b) {
				return false
			}
			segLen := int(binary.BigEndian.Uint16(b[i+2 : i+4]))
			if segLen < 2 || i+4+segLen > len(b) {
				return false
			}
			i += 2 + segLen
		}
	}
	return false
}

// strongBMP validates the BITMAPINFOHEADER/COREHEADER fields of a "BM" file.
func strongBMP(b []byte) bool {
	if len(b) < 30 {
		return false
	}
	fileSize := binary.LittleEndian.Uint32(b[2:6])
	if b[6] != 0 || b[7] != 0 || b[8] != 0 || b[9] != 0 {
		return false // reserved must be zero
	}
	pixelOffset := binary.LittleEndian.Uint32(b[10:14])
	if pixelOffset < 26 || int(pixelOffset) > len(b) {
		return false
	}
	dibSize := binary.LittleEndian.Uint32(b[14:18])
	if fileSize != 0 && int(fileSize) != len(b) {
		return false
	}
	switch dibSize {
	case 12, 40, 52, 56, 64, 108, 124:
	default:
		return false
	}
	if dibSize >= 40 {
		w := int32(binary.LittleEndian.Uint32(b[18:22]))
		h := int32(binary.LittleEndian.Uint32(b[22:26]))
		if w <= 0 || h == 0 {
			return false
		}
		if binary.LittleEndian.Uint16(b[26:28]) != 1 {
			return false // planes must be 1
		}
		switch binary.LittleEndian.Uint16(b[28:30]) {
		case 1, 4, 8, 16, 24, 32:
		default:
			return false
		}
	} else {
		// 12-byte BITMAPCOREHEADER: 16-bit dimensions at +18/+20.
		w := binary.LittleEndian.Uint16(b[18:20])
		h := binary.LittleEndian.Uint16(b[20:22])
		if w == 0 || h == 0 {
			return false
		}
	}
	return true
}
