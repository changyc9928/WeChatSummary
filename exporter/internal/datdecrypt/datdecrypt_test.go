package datdecrypt

import (
	"bytes"
	"crypto/aes"
	"encoding/binary"
	"testing"
)

func encAESECB(t *testing.T, key, data []byte) []byte {
	t.Helper()
	block, err := aes.NewCipher(key)
	if err != nil {
		t.Fatal(err)
	}
	out := make([]byte, len(data))
	for off := 0; off < len(data); off += aes.BlockSize {
		block.Encrypt(out[off:off+aes.BlockSize], data[off:off+aes.BlockSize])
	}
	return out
}

func pkcs7Pad(data []byte, block int) []byte {
	n := block - len(data)%block
	out := append([]byte{}, data...)
	for i := 0; i < n; i++ {
		out = append(out, byte(n))
	}
	return out
}

func buildV2File(t *testing.T, aesKey []byte, xorKey byte, plainAES, raw, xored []byte) []byte {
	t.Helper()
	enc := encAESECB(t, aesKey, pkcs7Pad(plainAES, aes.BlockSize))
	body := append(append(append([]byte{}, enc...), raw...), xored...)
	hdr := make([]byte, 15)
	copy(hdr, []byte{0x07, 0x08, 0x56, 0x32, 0x08, 0x07})
	// the container's aesSize is the pre-padding length; the reader realigns
	// to 16 (for a non-multiple, that is exactly the padded ciphertext)
	binary.LittleEndian.PutUint32(hdr[6:10], uint32(len(plainAES)))
	binary.LittleEndian.PutUint32(hdr[10:14], uint32(len(xored)))
	return append(hdr, body...)
}

func TestConstantKeyIsCipherTalk(t *testing.T) {
	if string(DefaultV1AESKey) != "cfcd208495d565ef" {
		t.Fatalf("v1 key = %q", DefaultV1AESKey)
	}
}

func TestDetectVersion(t *testing.T) {
	cases := []struct {
		header []byte
		want   int
	}{
		{[]byte{0x07, 0x08, 0x56, 0x31, 0x08, 0x07, 0, 0, 0, 0}, 1},
		{[]byte{0x07, 0x08, 0x56, 0x32, 0x08, 0x07, 0, 0, 0, 0}, 2},
		{[]byte{0xab, 0xcd, 0xef, 0x01, 0x02, 0x03}, 0},
		{[]byte{0xff, 0xd8, 0xff, 0xe0}, 0},
	}
	for _, c := range cases {
		if got := DetectVersion(c.header); got != c.want {
			t.Fatalf("DetectVersion(%x) = %d, want %d", c.header, got, c.want)
		}
	}
}

func TestDecryptV3(t *testing.T) {
	plain := append([]byte{0xff, 0xd8, 0xff, 0xe0, 1, 2, 3}, bytes.Repeat([]byte{0xab}, 64)...)
	const key = byte(0x73)
	wrapped := make([]byte, len(plain))
	for i, b := range plain {
		wrapped[i] = b ^ key
	}
	got, err := Decrypt(wrapped, key, nil)
	if err != nil {
		t.Fatal(err)
	}
	if !bytes.Equal(got[:7], plain[:7]) {
		t.Fatalf("V3 roundtrip mismatch: %x", got[:7])
	}
	if DetectExt(got) != ".jpg" {
		t.Fatalf("ext = %q", DetectExt(got))
	}
}

func TestDecryptV2WithRecoveredKey(t *testing.T) {
	accountKey := []byte("0123456789abcdef") // 16 ASCII chars
	xorKey := byte(0x76)
	plainAES := append([]byte{0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 0, 0, 0, 0x0d}, bytes.Repeat([]byte{0x5a}, 300)...)
	raw := []byte{1, 2, 3, 4, 5}
	xored := []byte{0xde, 0xad, 0xbe, 0xef}
	wrapped := buildV2File(t, accountKey, xorKey, plainAES, raw, xored)

	if DetectVersion(wrapped) != 2 {
		t.Fatal("expected V2 detection")
	}
	got, err := Decrypt(wrapped, xorKey, accountKey)
	if err != nil {
		t.Fatal(err)
	}
	want := append(append([]byte{}, plainAES...), raw...)
	for _, b := range xored {
		want = append(want, b^xorKey)
	}
	if !bytes.Equal(got, want) {
		t.Fatalf("V2 decrypt mismatch: got %d bytes want %d", len(got), len(want))
	}
	if DetectExt(got) != ".png" {
		t.Fatalf("ext = %q", DetectExt(got))
	}
}

func TestDecryptV2NeedsAccountKey(t *testing.T) {
	accountKey := []byte("0123456789abcdef")
	wrapped := buildV2File(t, accountKey, 0x76, []byte("ABCDEFGHIJKLMNOPQRSTUVWXYZ1234567890"), nil, nil)
	if _, err := Decrypt(wrapped, 0x76, nil); err == nil {
		t.Fatal("expected error without account key")
	}
	got, err := Decrypt(wrapped, 0x76, accountKey)
	if err != nil {
		t.Fatal(err)
	}
	if string(got) != "ABCDEFGHIJKLMNOPQRSTUVWXYZ1234567890" {
		t.Fatalf("payload = %q", got)
	}
}

func TestDetectXorKeyFromHeader(t *testing.T) {
	for _, sig := range [][]byte{{0xff, 0xd8, 0xff}, {0x89, 0x50, 0x4e, 0x47}} {
		key := byte(0x73)
		wrapped := make([]byte, len(sig))
		for i, b := range sig {
			wrapped[i] = b ^ key
		}
		got, ok := DetectXorKeyFromHeader(wrapped)
		if !ok || got != key {
			t.Fatalf("xor from header = %d,%v", got, ok)
		}
	}
}

func TestXorKeyFromTemplate(t *testing.T) {
	// x ^ 0xFF == xorKey and y ^ 0xD9 == xorKey: for xorKey 0x73 the real
	// template pair is {0x8c, 0xaa}.
	pairs := [][2]byte{{0x8c, 0xaa}, {0x8c, 0xaa}, {0x8c, 0xaa}, {0x12, 0xed}}
	xorKey, ok := XorKeyFromTemplate(pairs)
	if !ok || xorKey != 0x73 {
		t.Fatalf("template xor = %d ok=%v", xorKey, ok)
	}
	// inconsistent pair set -> reject
	if _, ok := XorKeyFromTemplate([][2]byte{{0x10, 0x20}}); ok {
		t.Fatal("expected reject on inconsistent pair")
	}
}

func TestStripTrailingNul(t *testing.T) {
	if got := StripTrailingNul([]byte{1, 2, 0, 0, 0}); len(got) != 2 {
		t.Fatalf("strip = %d", len(got))
	}
}

func TestDetectExtSniffs(t *testing.T) {
	cases := map[string]string{
		"jpg":  ".jpg",
		"png":  ".png",
		"gif":  ".gif",
		"webp": ".webp",
		"bmp":  ".bmp",
		"junk": "",
	}
	var payloads = map[string][]byte{
		"jpg":  {0xff, 0xd8, 0xff, 0xe0, 0, 0, 0, 0, 0, 0, 0, 0},
		"png":  {0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 0, 0, 0, 0, 0, 0, 0, 0},
		"gif":  {0x47, 0x49, 0x46, 0x38, 0x39, 0x61, 0, 0, 0, 0, 0, 0},
		"webp": {0x52, 0x49, 0x46, 0x46, 0x24, 0, 0, 0, 0x57, 0x45, 0x42, 0x50},
		"bmp":  {0x42, 0x4d, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0},
		"junk": {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11},
	}
	for name, want := range cases {
		if got := DetectExt(payloads[name]); got != want {
			t.Fatalf("DetectExt(%s) = %q, want %q", name, got, want)
		}
	}
}
