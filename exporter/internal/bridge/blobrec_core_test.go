package bridge

import (
	"bytes"
	"encoding/hex"
	"testing"
)

// fakePageProbe accepts exactly the given 32-byte key, mimicking the
// sqlcipher page-1 probe.
func fakePageProbe(want []byte) func(pageKey []byte) string {
	return func(pageKey []byte) string {
		if bytes.Equal(pageKey, want) {
			return "mac"
		}
		return ""
	}
}

func TestScanBytesForKeyRaw(t *testing.T) {
	key := bytes.Repeat([]byte{0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77, 0x88}, 4)
	// Key buried inside a container blob at an odd offset.
	data := append([]byte("HEADER\x00\x01"), key...)
	data = append(data, []byte("TAIL...")...)
	k, l := scanBytesForKey(data, fakePageProbe(key))
	if k != hex.EncodeToString(key) || l != "mac" {
		t.Fatalf("raw shape: got %q/%q", k, l)
	}
}

func TestScanBytesForKeyAsciiHex(t *testing.T) {
	key := bytes.Repeat([]byte{0xaa, 0xbb, 0xcc, 0xdd, 0xee, 0xff, 0x00, 0x11}, 4)
	data := []byte("cfg: " + hex.EncodeToString(key) + " ;end")
	k, _ := scanBytesForKey(data, fakePageProbe(key))
	if k != hex.EncodeToString(key) {
		t.Fatalf("ascii-hex shape: got %q", k)
	}
}

func TestScanBytesForKeyWideHex(t *testing.T) {
	key := bytes.Repeat([]byte{0x01, 0x23, 0x45, 0x67, 0x89, 0xab, 0xcd, 0xef}, 4)
	var wide []byte
	for _, c := range []byte(hex.EncodeToString(key)) {
		wide = append(wide, c, 0x00)
	}
	data := append([]byte("w:"), wide...)
	k, _ := scanBytesForKey(data, fakePageProbe(key))
	if k != hex.EncodeToString(key) {
		t.Fatalf("wide-hex shape: got %q", k)
	}
}

func TestScanBytesForKeyMiss(t *testing.T) {
	key := bytes.Repeat([]byte{0x77}, 32)
	data := bytes.Repeat([]byte{0x41}, 4096) // all ASCII 'A'
	k, _ := scanBytesForKey(data, fakePageProbe(key))
	if k != "" {
		t.Fatalf("expected miss, got %q", k)
	}
}
