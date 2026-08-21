package bridge

import (
	"crypto/hmac"
	"crypto/rand"
	"crypto/sha512"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"golang.org/x/crypto/pbkdf2"

	"wechatsummary/exporter/internal/sqlcipher"
)

// --- synthetic SQLCipher database (mirrors the algorithm in sqlcipher.go) ---

func xorSalt(salt []byte) []byte {
	out := make([]byte, len(salt))
	for i, b := range salt {
		out[i] = b ^ 0x3A
	}
	return out
}

func randBytes(t *testing.T, n int) []byte {
	t.Helper()
	b := make([]byte, n)
	if _, err := rand.Read(b); err != nil {
		t.Fatalf("rand: %v", err)
	}
	return b
}

// makeSQLCipherDB writes a single-page SQLCipher-4 database (raw or derived
// mode) and returns its path.
func makeSQLCipherDB(t *testing.T, dir string, mode sqlcipher.KeyMode, secret []byte) string {
	t.Helper()
	salt := randBytes(t, 16)
	var pageKey []byte
	if mode == sqlcipher.ModeRaw {
		if len(secret) != 32 {
			t.Fatalf("raw mode needs 32-byte secret, got %d", len(secret))
		}
		pageKey = secret
	} else {
		pageKey = pbkdf2.Key(secret, salt, 256000, 32, sha512.New)
	}
	macKey := pbkdf2.Key(pageKey, xorSalt(salt), 2, 32, sha512.New)

	payload := randBytes(t, 4000)
	iv := randBytes(t, 16)
	mac := make([]byte, 64)
	{
		h := hmac.New(sha512.New, macKey)
		h.Write(payload)
		h.Write(iv)
		h.Write([]byte{1, 0, 0, 0}) // pgno 1, LE
		copy(mac, h.Sum(nil))
	}
	page1 := make([]byte, 4096)
	copy(page1[0:16], salt)
	copy(page1[16:4016], payload)
	copy(page1[4016:4032], iv)
	copy(page1[4032:4096], mac)

	path := filepath.Join(dir, "MSG.db")
	if err := os.WriteFile(path, page1, 0o600); err != nil {
		t.Fatalf("write db: %v", err)
	}
	return path
}

// --- helpers ---

type respEnvelope struct {
	Ok    bool            `json:"ok"`
	Data  json.RawMessage `json:"data"`
	Error *struct {
		Code    string `json:"code"`
		Message string `json:"message"`
	} `json:"error"`
}

func doReq(t *testing.T, h http.Handler, method, path string, headers map[string]string, body string) (*httptest.ResponseRecorder, respEnvelope) {
	t.Helper()
	var r *http.Request
	if body != "" {
		r = httptest.NewRequest(method, path, strings.NewReader(body))
		r.Header.Set("Content-Type", "application/json")
	} else {
		r = httptest.NewRequest(method, path, nil)
	}
	for k, v := range headers {
		r.Header.Set(k, v)
	}
	w := httptest.NewRecorder()
	h.ServeHTTP(w, r)
	var env respEnvelope
	if len(w.Body.Bytes()) > 0 {
		if err := json.Unmarshal(w.Body.Bytes(), &env); err != nil {
			t.Fatalf("bad envelope: %v (body=%s)", err, w.Body.String())
		}
	}
	return w, env
}

func newTestServer(cfg Config) http.Handler {
	return NewServer(cfg)
}

// --- tests ---

func TestHealthCORSAndAuth(t *testing.T) {
	h := newTestServer(Config{
		AllowOrigins: []string{"http://localhost:5173"},
		Token:        "sekrit",
	})

	// no auth -> 401
	w, env := doReq(t, h, http.MethodGet, "/api/health", nil, "")
	if w.Code != http.StatusUnauthorized || env.Ok || env.Error == nil || env.Error.Code != "unauthorized" {
		t.Fatalf("expected 401 unauthorized, got %d %+v", w.Code, env)
	}

	// wrong token -> 401
	_, env = doReq(t, h, http.MethodGet, "/api/health", map[string]string{"Authorization": "Bearer nope"}, "")
	if env.Ok || env.Error == nil || env.Error.Code != "unauthorized" {
		t.Fatalf("expected 401 for bad token, got %+v", env)
	}

	// allowed origin -> CORS header, ok payload
	w, env = doReq(t, h, http.MethodGet, "/api/health", map[string]string{
		"Authorization": "Bearer sekrit",
		"Origin":        "http://localhost:5173",
	}, "")
	if w.Code != http.StatusOK || !env.Ok {
		t.Fatalf("health failed: %d %+v", w.Code, env)
	}
	if got := w.Header().Get("Access-Control-Allow-Origin"); got != "http://localhost:5173" {
		t.Fatalf("CORS origin = %q", got)
	}
	var data struct {
		Service       string `json:"service"`
		ScanSupported bool   `json:"scanSupported"`
		Platform      string `json:"platform"`
	}
	if err := json.Unmarshal(env.Data, &data); err != nil {
		t.Fatalf("decode health data: %v", err)
	}
	if data.Service != "wechat-key-bridge" {
		t.Fatalf("service = %q", data.Service)
	}

	// disallowed origin -> no CORS header
	w, _ = doReq(t, h, http.MethodGet, "/api/health", map[string]string{
		"Authorization": "Bearer sekrit",
		"Origin":        "https://evil.example",
	}, "")
	if got := w.Header().Get("Access-Control-Allow-Origin"); got != "" {
		t.Fatalf("disallowed origin leaked CORS header %q", got)
	}

	// preflight
	w, _ = doReq(t, h, http.MethodOptions, "/api/health", map[string]string{
		"Origin":                        "http://localhost:5173",
		"Access-Control-Request-Method": "GET",
	}, "")
	if w.Code != http.StatusNoContent {
		t.Fatalf("preflight code = %d", w.Code)
	}

	// Private Network Access preflight: Chrome/Edge send
	// Access-Control-Request-Private-Network and abort the real request with
	// "Failed to fetch" unless the bridge answers with
	// Access-Control-Allow-Private-Network: true.
	w, _ = doReq(t, h, http.MethodOptions, "/api/health", map[string]string{
		"Origin":                                 "http://localhost:5173",
		"Access-Control-Request-Method":          "GET",
		"Access-Control-Request-Private-Network": "true",
	}, "")
	if got := w.Header().Get("Access-Control-Allow-Private-Network"); got != "true" {
		t.Fatalf("PNA preflight missing Access-Control-Allow-Private-Network (got %q)", got)
	}
}

func TestCORSAnyOrigin(t *testing.T) {
	h := newTestServer(Config{AllowAnyOrigin: true})

	// any origin gets an echoed CORS header
	w, _ := doReq(t, h, http.MethodGet, "/api/health", map[string]string{
		"Origin": "http://192.168.0.216:3001",
	}, "")
	if got := w.Header().Get("Access-Control-Allow-Origin"); got != "http://192.168.0.216:3001" {
		t.Fatalf("cors-any origin = %q", got)
	}

	// and the PNA header on preflights
	w, _ = doReq(t, h, http.MethodOptions, "/api/health", map[string]string{
		"Origin":                                 "http://192.168.0.216:3001",
		"Access-Control-Request-Private-Network": "true",
	}, "")
	if got := w.Header().Get("Access-Control-Allow-Private-Network"); got != "true" {
		t.Fatalf("cors-any PNA header = %q", got)
	}

	// still refuses non-loopback binds? (bind check lives in cmd/bridge, but
	// with AllowAnyOrigin the CORS layer must not leak for empty Origin)
	w, _ = doReq(t, h, http.MethodGet, "/api/health", nil, "")
	if got := w.Header().Get("Access-Control-Allow-Origin"); got != "" {
		t.Fatalf("cors-any leaked header for no Origin: %q", got)
	}
}

func TestScanUnsupportedOnNonWindows(t *testing.T) {
	h := newTestServer(Config{})
	w, env := doReq(t, h, http.MethodGet, "/api/scan", nil, "")
	// The test host may be Windows in CI; only assert the envelope shape.
	if w.Code == http.StatusNotImplemented {
		if env.Error == nil || env.Error.Code != "unsupported_platform" {
			t.Fatalf("unexpected error envelope: %+v", env)
		}
		return
	}
	if w.Code != http.StatusOK || !env.Ok {
		t.Fatalf("unexpected scan response: %d %+v", w.Code, env)
	}
}

func TestValidateRawKey(t *testing.T) {
	dir := t.TempDir()
	secret := randBytes(t, 32)
	db := makeSQLCipherDB(t, dir, sqlcipher.ModeRaw, secret)

	h := newTestServer(Config{})
	body := fmt.Sprintf(`{"key":%q,"dbPath":%q}`, hex.EncodeToString(secret), db)
	w, env := doReq(t, h, http.MethodPost, "/api/key/validate", nil, body)
	if w.Code != http.StatusOK || !env.Ok {
		t.Fatalf("validate failed: %d %+v", w.Code, env)
	}
	var data struct {
		Valid bool   `json:"valid"`
		Mode  string `json:"mode"`
		Salt  string `json:"salt"`
	}
	if err := json.Unmarshal(env.Data, &data); err != nil {
		t.Fatalf("decode: %v", err)
	}
	if !data.Valid || data.Mode != "raw" || len(data.Salt) != 32 {
		t.Fatalf("raw validate: %+v", data)
	}
}

func TestValidateWrongKey(t *testing.T) {
	dir := t.TempDir()
	secret := randBytes(t, 32)
	db := makeSQLCipherDB(t, dir, sqlcipher.ModeRaw, secret)

	h := newTestServer(Config{})
	wrong := randBytes(t, 32)
	body := fmt.Sprintf(`{"key":%q,"dbPath":%q}`, hex.EncodeToString(wrong), db)
	w, env := doReq(t, h, http.MethodPost, "/api/key/validate", nil, body)
	if w.Code != http.StatusOK || !env.Ok {
		t.Fatalf("unexpected: %d %+v", w.Code, env)
	}
	var data struct {
		Valid  bool   `json:"valid"`
		Reason string `json:"reason"`
	}
	if err := json.Unmarshal(env.Data, &data); err != nil {
		t.Fatalf("decode: %v", err)
	}
	if data.Valid {
		t.Fatalf("wrong key validated: %+v", data)
	}
}

func TestValidateDerivedPassphrase(t *testing.T) {
	dir := t.TempDir()
	// A passphrase must arrive as hex; use a 32-byte passphrase for the
	// 64-hex input shape the bridge accepts.
	passphrase := []byte(strings.Repeat("8", 32))
	db := makeSQLCipherDB(t, dir, sqlcipher.ModeDerived, passphrase)

	h := newTestServer(Config{})
	body := fmt.Sprintf(`{"key":%q,"dbPath":%q}`, hex.EncodeToString(passphrase), db)
	w, env := doReq(t, h, http.MethodPost, "/api/key/validate", nil, body)
	if w.Code != http.StatusOK || !env.Ok {
		t.Fatalf("validate failed: %d %+v", w.Code, env)
	}
	var data struct {
		Valid bool   `json:"valid"`
		Mode  string `json:"mode"`
	}
	if err := json.Unmarshal(env.Data, &data); err != nil {
		t.Fatalf("decode: %v", err)
	}
	if !data.Valid || data.Mode != "derived(passphrase)" {
		t.Fatalf("derived validate: %+v", data)
	}
}

func TestValidateBadKeyFormat(t *testing.T) {
	h := newTestServer(Config{})
	w, env := doReq(t, h, http.MethodPost, "/api/key/validate",
		nil, `{"key":"not-hex","dbPath":"/tmp/x.db"}`)
	if w.Code != http.StatusBadRequest || env.Error == nil || env.Error.Code != "bad_key" {
		t.Fatalf("expected bad_key, got %d %+v", w.Code, env)
	}
}

func TestDBDiscoveryAndAutoFind(t *testing.T) {
	dir := t.TempDir()
	wechat := filepath.Join(dir, "xwechat_files", "wxid_abc", "msg")
	if err := os.MkdirAll(wechat, 0o755); err != nil {
		t.Fatalf("mkdir: %v", err)
	}
	secret := randBytes(t, 32)
	db := makeSQLCipherDB(t, wechat, sqlcipher.ModeRaw, secret)
	// A decoy not under a WeChat folder must be ignored.
	decoy := filepath.Join(dir, "elsewhere")
	if err := os.MkdirAll(decoy, 0o755); err != nil {
		t.Fatalf("mkdir decoy: %v", err)
	}
	if err := os.WriteFile(filepath.Join(decoy, "MSG.db"), []byte("x"), 0o600); err != nil {
		t.Fatalf("decoy: %v", err)
	}

	h := newTestServer(Config{DBRoots: []string{dir}})

	// /api/dbs lists only the WeChat one.
	w, env := doReq(t, h, http.MethodGet, "/api/dbs", nil, "")
	if w.Code != http.StatusOK || !env.Ok {
		t.Fatalf("dbs: %d %+v", w.Code, env)
	}
	var dbs struct {
		Databases []string `json:"databases"`
	}
	if err := json.Unmarshal(env.Data, &dbs); err != nil {
		t.Fatalf("decode dbs: %v", err)
	}
	if len(dbs.Databases) != 1 || dbs.Databases[0] != db {
		t.Fatalf("dbs = %v, want [%s]", dbs.Databases, db)
	}

	// validate with empty dbPath auto-finds it.
	body := fmt.Sprintf(`{"key":%q}`, hex.EncodeToString(secret))
	w, env = doReq(t, h, http.MethodPost, "/api/key/validate", nil, body)
	if w.Code != http.StatusOK || !env.Ok {
		t.Fatalf("auto validate: %d %+v", w.Code, env)
	}
	var data struct {
		Valid  bool   `json:"valid"`
		DBPath string `json:"dbPath"`
	}
	if err := json.Unmarshal(env.Data, &data); err != nil {
		t.Fatalf("decode: %v", err)
	}
	if !data.Valid || data.DBPath != db {
		t.Fatalf("auto validate: %+v", data)
	}
}

// TestKeyToolHandlerWithoutDLL locks the /api/key/tool contract: without a
// server-provided DLL path (and no DLL on non-Windows hosts) it must answer
// 200 ok:true with found:false and a helpful reason, never an HTTP error.
func TestKeyToolHandlerWithoutDLL(t *testing.T) {
	h := newTestServer(Config{})
	w, env := doReq(t, h, http.MethodPost, "/api/key/tool",
		nil, `{"dllPath":"C:\\missing\\wechat_key_tool.dll","dbPath":"C:\\nope\\message_0.db"}`)
	if w.Code != http.StatusOK || !env.Ok {
		t.Fatalf("expected 200 ok, got %d %+v", w.Code, env)
	}
	var data keyToolResult
	if err := json.Unmarshal(env.Data, &data); err != nil {
		t.Fatalf("decode: %v (body=%s)", err, w.Body.String())
	}
	if data.Found {
		t.Fatal("must not claim a key with no DLL present")
	}
	if data.Reason == "" {
		t.Fatal("expected a reason explaining the failure")
	}
	if data.Attempts != 0 || data.Key != "" {
		t.Fatalf("unexpected data: %+v", data)
	}
}
