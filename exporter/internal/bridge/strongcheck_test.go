package bridge

import (
	"os"
	"path/filepath"
	"testing"

	"wechatsummary/exporter/internal/datdecrypt"
)

// TestStrongPayloadRejectsFalseKey proves the v0.1.23 false positive is dead:
// the key 7C938F60902A1BB5 previously "verified" because a template decrypt
// began with a lucky 2-byte "BM". The old gate (LooksLikeMediaPayload)
// accepted it; the new gate (StrongMediaPayload) must reject it on the same
// files, and verifyAesKey must therefore refuse to return it.
func TestStrongPayloadRejectsFalseKey(t *testing.T) {
	raw := "/Users/yincheng.chang/Downloads/wechat-raw"
	if _, err := os.Stat(raw); err != nil {
		t.Skip("user raw files not present on this machine")
	}
	dir := t.TempDir()
	files := []string{
		"33862c726ffe8009d1e3dbb793379236.dat",
		"33862c726ffe8009d1e3dbb793379236_t.dat",
		"33862c726ffe8009d1e3dbb793379236_b.dat",
	}
	found := []string{}
	for _, n := range files {
		src := filepath.Join(raw, n)
		if b, err := os.ReadFile(src); err == nil {
			os.WriteFile(filepath.Join(dir, n), b, 0644)
			found = append(found, n)
		}
	}
	if len(found) == 0 {
		t.Skip("no user .dat files to test against")
	}
	dats := templateFilesInDir(dir)
	if len(dats) == 0 {
		t.Fatal("no V2 template files copied")
	}

	fakeKey := []byte("7C938F60902A1BB5")
	weakOK, weakExt := false, ""
	for _, f := range dats {
		b, _ := os.ReadFile(f)
		xorKey, ok := xorKeyFromTemplateFiles([]string{f})
		if !ok {
			continue
		}
		dec, derr := datdecrypt.Decrypt(b, xorKey, fakeKey)
		if derr == nil && datdecrypt.LooksLikeMediaPayload(dec) {
			weakOK = true
			weakExt = datdecrypt.DetectExt(dec)
			t.Logf("old gate accepted %s (%s) — the false positive the fix removes", filepath.Base(f), weakExt)
		}
	}
	if !weakOK {
		t.Log("note: old gate did not accept on the files present here (false accept is probabilistic)")
	}

	if ok, _ := verifyAesKey(fakeKey, dats); ok {
		t.Fatalf("false key %q still accepted by verifyAesKey (weak gate saw %s)", fakeKey, weakExt)
	}
}

// TestStrongMediaPayload accepts genuine headers and rejects 2-byte BM / xored
// garbage, covering every format the export accept-path relies on.
func TestStrongMediaPayload(t *testing.T) {
	accept := map[string][]byte{
		// JPEG SOI + APP0(0x0010) + JFIF + a valid SOF0 segment (height/width
		// at fixed offsets) — the whole APP0 must fit inside the buffer.
		"jpg-jfif": append(clone([]byte{0xff, 0xd8, 0xff, 0xe0, 0x00, 0x10, 'J', 'F', 'I', 'F', 0x00, 0x01, 0x01, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0xff, 0xc0, 0x00, 0x11, 0x08, 0x00, 0x10, 0x00, 0x10, 0x03, 0x01, 0x22, 0x00, 0x02, 0x11, 0x01, 0x03, 0x11, 0x01, 0xff, 0xd9}), make([]byte, 300)...),
		// JPEG SOI + APP1(0x0010) + Exif.
		"jpg-exif": append(clone([]byte{0xff, 0xd8, 0xff, 0xe1, 0x00, 0x10, 'E', 'x', 'i', 'f', 0x00, 0x00, 0x49, 0x49, 0x2a, 0x00, 0x08, 0x00, 0x00, 0x00, 0xff, 0xc0, 0x00, 0x11, 0x08, 0x00, 0x10, 0x00, 0x10, 0x03, 0x01, 0x22, 0x00, 0x02, 0x11, 0x01, 0x03, 0x11, 0x01, 0xff, 0xd9}), make([]byte, 300)...),
		"png": {0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 0x00, 0x00, 0x00, 0x0d, 'I', 'H', 'D', 'R', 0, 0, 2, 0, 0, 0, 2, 0, 0, 0},
		"gif": append([]byte("GIF89a"), 0x01, 0x00, 0x01, 0x00, 0x80, 0x00, 0x00, 0x00, 0x00, 0x00, 0xff, 0xff, 0xff, 0x00),
		"webp": append([]byte("RIFF\x04\x00\x00\x00WEBPVP8 "), make([]byte, 24)...),
		// BMP: fileSize must equal the buffer length (54), pixelOffset 54,
		// BITMAPINFOHEADER(40) width/height 1 planes 1 bpp 24.
		"bmp": append(clone([]byte("BM"+string([]byte{0x36, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x36, 0x00, 0x00, 0x00, 0x28, 0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01, 0x00, 0x18, 0x00}))), make([]byte, 24)...),
		"heic": append([]byte{0, 0, 0, 24, 'f', 't', 'y', 'p', 'h', 'e', 'i', 'c'}, make([]byte, 16)...),
	}
	for name, b := range accept {
		if !datdecrypt.StrongMediaPayload(b) {
			t.Errorf("StrongMediaPayload rejected genuine %s header", name)
		}
	}
	reject := map[string][]byte{
		"bmp-2byte-only":   {'B', 'M', 0xb5, 0xaa, 0x13, 0x87, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x01, 0x00, 0x18, 0x00, 0x00, 0x00},
		"random-junk":      []byte{0x1f, 0x23, 0x29, 0x59, 0x7c, 0x1c, 0x47, 0x2a, 0x6a, 0x33, 0x8a, 0x02, 0x12, 0x34, 0x56, 0x78, 0x9a, 0xbc, 0xde, 0xf0},
		"jpg-no-markers":   {0xff, 0xd8, 0xff, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00},
		"png-truncated":    {0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 0x00, 0x00, 0x00, 0x00, 'I', 'H', 'D', 'R'},
		"gif-short":        []byte("GIF89a"),
		"webp-no-chunk":    append([]byte("RIFF\x04\x00\x00\x00WEBP"), make([]byte, 24)...),
		"bmp-bad-filesize": append([]byte("BM"), append(make([]byte, 0), 0xb5, 0xaa, 0x13, 0x87, 0x00, 0x00, 0x00, 0x00, 0x54, 0x00, 0x00, 0x00, 0x28, 0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01, 0x00, 0x18, 0x00)...),
		"heic-fake-brand":  {0, 0, 0, 24, 'f', 't', 'y', 'p', 'x', 'x', 'x', 'x'},
	}
	for name, b := range reject {
		if datdecrypt.StrongMediaPayload(b) {
			t.Errorf("StrongMediaPayload accepted bogus %s", name)
		}
	}
}

func clone(b []byte) []byte {
	return append([]byte(nil), b...)
}

func templateFilesInDir(dir string) []string {
	var out []string
	_ = filepath.Walk(dir, func(p string, info os.FileInfo, err error) error {
		if err == nil && !info.IsDir() && datdecrypt.DetectVersion(mustRead(p)) == 2 {
			out = append(out, p)
		}
		return nil
	})
	return out
}

func mustRead(p string) []byte {
	b, _ := os.ReadFile(p)
	return b
}