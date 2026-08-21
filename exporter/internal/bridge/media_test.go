package bridge

import (
	"archive/zip"
	"bytes"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"net/http"
	"os"
	"path/filepath"
	"testing"
)

// buildV3Dat wraps plain bytes in whole-file XOR (WeChat 3.x style).
func buildV3Dat(t *testing.T, plain []byte, xorKey byte) []byte {
	t.Helper()
	out := make([]byte, len(plain))
	for i, b := range plain {
		out[i] = b ^ xorKey
	}
	return out
}

// jpgHeader returns a small fake JPEG payload (with EOI) so ext sniffing works.
func jpgHeader() []byte {
	return append([]byte{0xff, 0xd8, 0xff, 0xe0, 0, 0x10, 'J', 'F', 'I', 'F', 0, 1, 1, 0, 0, 1},
		bytes.Repeat([]byte{0x42}, 64)...)
}

func TestMediaKeysNoTemplates(t *testing.T) {
	dir := t.TempDir()
	h := newTestServer(Config{})
	body := fmt.Sprintf(`{"dir":%q}`, filepath.Join(dir, "empty"))
	w, env := doReq(t, h, http.MethodPost, "/api/media/keys", nil, body)
	if w.Code != http.StatusOK || !env.Ok {
		t.Fatalf("keys: %d %+v", w.Code, env)
	}
	var data mediaKeysResult
	if err := json.Unmarshal(env.Data, &data); err != nil {
		t.Fatal(err)
	}
	if data.Found || data.TemplateCount != 0 || data.Reason == "" {
		t.Fatalf("expected no templates: %+v", data)
	}
}

func TestMediaKeysTemplateXorOnly(t *testing.T) {
	dir := t.TempDir()
	// two consistent template files + one junk
	for i, pair := range [][2]byte{{0x8c, 0xaa}, {0x8c, 0xaa}, {0x12, 0xed}} {
		name := fmt.Sprintf("2025-%02d_thumb_t.dat", i+1)
		b := append(bytes.Repeat([]byte{0x77}, 100), pair[0], pair[1])
		if err := os.WriteFile(filepath.Join(dir, name), b, 0o600); err != nil {
			t.Fatal(err)
		}
	}
	h := newTestServer(Config{})
	body := fmt.Sprintf(`{"dir":%q}`, dir)
	w, env := doReq(t, h, http.MethodPost, "/api/media/keys", nil, body)
	if w.Code != http.StatusOK || !env.Ok {
		t.Fatalf("keys: %d %+v", w.Code, env)
	}
	var data mediaKeysResult
	if err := json.Unmarshal(env.Data, &data); err != nil {
		t.Fatal(err)
	}
	if !data.Found || data.XorKey != "73" {
		t.Fatalf("expected xor 73: %+v", data)
	}
	if data.TemplateCount != 3 {
		t.Fatalf("templateCount = %d", data.TemplateCount)
	}
	if data.AesKey != "" || data.Source != "" {
		t.Fatalf("no DLL on this host, expected no aes: %+v", data)
	}
}

func TestMediaDecryptSingleV3(t *testing.T) {
	dir := t.TempDir()
	plain := jpgHeader()
	path := filepath.Join(dir, "2025-01_img_1.dat")
	if err := os.WriteFile(path, buildV3Dat(t, plain, 0x73), 0o600); err != nil {
		t.Fatal(err)
	}
	h := newTestServer(Config{})
	body := fmt.Sprintf(`{"path":%q}`, path)
	w, env := doReq(t, h, http.MethodPost, "/api/media/decrypt", nil, body)
	if w.Code != http.StatusOK || !env.Ok {
		t.Fatalf("decrypt: %d %+v", w.Code, env)
	}
	var data mediaDecryptResult
	if err := json.Unmarshal(env.Data, &data); err != nil {
		t.Fatal(err)
	}
	if data.Ext != ".jpg" || data.Version != 0 || data.XorKey != "73" {
		t.Fatalf("bad result: %+v", data)
	}
	dec, err := base64.StdEncoding.DecodeString(data.Data)
	if err != nil {
		t.Fatal(err)
	}
	if !bytes.Equal(dec, plain) {
		t.Fatalf("roundtrip mismatch: %d vs %d bytes", len(dec), len(plain))
	}
}

func TestMediaDecryptSingleNeedsXor(t *testing.T) {
	dir := t.TempDir()
	// deterministic all-zero bytes: no image magic can match under any XOR key
	path := filepath.Join(dir, "x.dat")
	if err := os.WriteFile(path, bytes.Repeat([]byte{0x00}, 64), 0o600); err != nil {
		t.Fatal(err)
	}
	h := newTestServer(Config{})
	body := fmt.Sprintf(`{"path":%q}`, path)
	w, env := doReq(t, h, http.MethodPost, "/api/media/decrypt", nil, body)
	if w.Code != http.StatusBadRequest {
		t.Fatalf("expected 400, got %d %+v", w.Code, env)
	}
	if env.Error == nil || env.Error.Code != "xor_key_required" {
		t.Fatalf("expected xor_key_required: %+v", env)
	}
}

func TestMediaDecryptDir(t *testing.T) {
	dir := t.TempDir()
	sub := filepath.Join(dir, "2025-01", "Img")
	if err := os.MkdirAll(sub, 0o755); err != nil {
		t.Fatal(err)
	}
	plain1 := jpgHeader()
	plain2 := append([]byte{0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a}, bytes.Repeat([]byte{0x77}, 60)...)
	if err := os.WriteFile(filepath.Join(sub, "a.dat"), buildV3Dat(t, plain1, 0x73), 0o600); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(sub, "b.dat"), buildV3Dat(t, plain2, 0x73), 0o600); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(dir, "note.txt"), []byte("hi"), 0o600); err != nil {
		t.Fatal(err)
	}

	h := newTestServer(Config{})
	body := fmt.Sprintf(`{"dir":%q}`, dir)
	w, env := doReq(t, h, http.MethodPost, "/api/media/decrypt-dir", nil, body)
	if w.Code != http.StatusOK || !env.Ok {
		t.Fatalf("decrypt-dir: %d %+v", w.Code, env)
	}
	var data mediaDirResult
	if err := json.Unmarshal(env.Data, &data); err != nil {
		t.Fatal(err)
	}
	if data.Decrypted != 2 || data.Plain != 1 || data.FileCount != 3 || data.Failed != 0 {
		t.Fatalf("bad counts: %+v", data)
	}
	zr, err := zip.NewReader(bytes.NewReader(mustDecodeBase64(t, data.ZipBase64)), int64(len(mustDecodeBase64(t, data.ZipBase64))))
	if err != nil {
		t.Fatal(err)
	}
	names := map[string]bool{}
	for _, f := range zr.File {
		names[f.Name] = true
	}
	for _, want := range []string{"2025-01/Img/a.jpg", "2025-01/Img/b.png", "note.txt"} {
		if !names[want] {
			t.Fatalf("zip missing %s (have %v)", want, names)
		}
	}
}

func TestAccountDirOf(t *testing.T) {
	p := filepath.Join("Users", "me", "Documents", "xwechat_files", "wxid_abc", "db_storage", "message", "message_0.db")
	got := accountDirOf(p)
	want := filepath.Join("Users", "me", "Documents", "xwechat_files", "wxid_abc")
	if got != want {
		t.Fatalf("accountDirOf = %q, want %q", got, want)
	}
}

func mustDecodeBase64(t *testing.T, s string) []byte {
	t.Helper()
	b, err := base64.StdEncoding.DecodeString(s)
	if err != nil {
		t.Fatal(err)
	}
	return b
}
