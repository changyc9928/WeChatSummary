package bridge

import (
	"bytes"
	"crypto/aes"
	"crypto/cipher"
	"crypto/ed25519"
	"encoding/binary"
	"encoding/hex"
	"encoding/json"
	"net/http"
	"os"
	"path/filepath"
	"runtime"
	"strings"
	"testing"

	"wechatsummary/exporter/internal/scan"
	"wechatsummary/exporter/internal/sqlcipher"
)

// --- candidate extraction ---

func TestKeyCandidatesFromWindows(t *testing.T) {
	salt := randBytes(t, 16)
	// synthesize a window: [noise(64) | key(32) | salt(16) | noise(64)]
	key := randBytes(t, 32)
	window := append(append(append(randBytes(t, 64), key...), salt...), randBytes(t, 64)...)

	raw, pass := keyCandidatesFromWindows([][]byte{window}, salt, 4000)
	if len(raw) == 0 {
		t.Fatal("no raw candidates produced")
	}
	// the "32 bytes before the salt" candidate must be FIRST and be the key
	if raw[0] != hex.EncodeToString(key) {
		t.Fatalf("expected key-before-salt candidate first, got %s...", raw[0][:8])
	}
	// the dense sweep covers the key at any alignment
	keyHex := hex.EncodeToString(key)
	found := false
	for _, c := range raw {
		if c == keyHex {
			found = true
			break
		}
	}
	if !found {
		t.Fatalf("sweep missed the embedded key (raw=%d candidates)", len(raw))
	}
	if len(pass) == 0 {
		t.Log("no printable-run passphrase candidates from random noise (expected occasionally)")
	}
}

func TestKeyCandidatesDedup(t *testing.T) {
	salt := randBytes(t, 16)
	window := append(randBytes(t, 32), salt...)
	raw, _ := keyCandidatesFromWindows([][]byte{window, window, window}, salt, 5000)
	seen := map[string]bool{}
	for _, c := range raw {
		if seen[c] {
			t.Fatal("duplicate candidate produced")
		}
		seen[c] = true
	}
}

func TestVerifyCandidatesRaw(t *testing.T) {
	dir := t.TempDir()
	key := randBytes(t, 32)
	dbPath := makeSQLCipherDB(t, dir, sqlcipher.ModeRaw, key)
	salt := randBytes(t, 16)

	window := append(append(randBytes(t, 40), key...), salt...)
	raw, _ := keyCandidatesFromWindows([][]byte{window}, salt, 2000)
	got, mode, verification, attempts := verifyCandidates(dbPath, raw, nil)
	if got != hex.EncodeToString(key) || mode != "raw" || verification != "mac" {
		t.Fatalf("expected raw key found, got %q mode %q ver %q (%d attempts)", got, mode, verification, attempts)
	}

	// wrong window -> nothing found, attempts > 0
	wrong, wmode, wver, wattempts := verifyCandidates(dbPath, []string{hex.EncodeToString(randBytes(t, 32))}, nil)
	if wrong != "" || wmode != "" || wver != "" || wattempts == 0 {
		t.Fatalf("expected no match: %q %q %q %d", wrong, wmode, wver, wattempts)
	}
}

// makeMagicOnlyDB builds a SQLCipher-4 page whose payload decrypts to a valid
// SQLite header with the given raw key, but whose MAC is garbage — simulating
// a vendor fork with different HMAC parameters. Strict opens fail; the magic
// probe must still recognize the key.
func makeMagicOnlyDB(t *testing.T, dir string, key []byte) string {
	t.Helper()
	if len(key) != 32 {
		t.Fatalf("need 32-byte key")
	}
	salt := randBytes(t, 16)
	iv := randBytes(t, 16)
	payload := make([]byte, 4000)
	copy(payload, "SQLite format 3\x00")
	binary.BigEndian.PutUint16(payload[16:18], 4096) // page size
	block, err := aes.NewCipher(key)
	if err != nil {
		t.Fatal(err)
	}
	cipher.NewCBCEncrypter(block, iv).CryptBlocks(payload, payload)
	page1 := make([]byte, 4096)
	copy(page1[0:16], salt)
	copy(page1[16:4016], payload)
	copy(page1[4016:4032], iv)
	// bogus MAC: strict verification must fail, magic must pass
	path := filepath.Join(dir, "MSG.db")
	if err := os.WriteFile(path, page1, 0o600); err != nil {
		t.Fatal(err)
	}
	return path
}

func TestVerifyCandidatesMagicFallback(t *testing.T) {
	dir := t.TempDir()
	key := randBytes(t, 32)
	dbPath := makeMagicOnlyDB(t, dir, key)

	// strict open must fail (bad MAC)
	if f, err := sqlcipher.Open(dbPath, key, sqlcipher.ModeRaw, true); err == nil {
		f.Close()
		t.Fatal("expected strict open to fail on bad MAC")
	}
	macOK, magicOK, err := sqlcipher.ProbePageKey(dbPath, key)
	if err != nil {
		t.Fatal(err)
	}
	if macOK {
		t.Fatal("expected macOK=false for garbage MAC")
	}
	if !magicOK {
		t.Fatal("expected magicOK=true for correct key")
	}

	// verifyCandidates must return the key with verification=magic
	got, mode, verification, attempts := verifyCandidates(dbPath, []string{hex.EncodeToString(key)}, nil)
	if got != hex.EncodeToString(key) || mode != "raw" || verification != "magic" || attempts == 0 {
		t.Fatalf("got %q %q %q (%d)", got, mode, verification, attempts)
	}
}

func TestKeyToolParsing(t *testing.T) {
	acc, err := parseKeyToolAccount([]byte(`{"db_key":"` + strings.Repeat("ab", 32) + `","wxid":"wxid_test","name":"n","number":"num","phone":"p","seed":7}`))
	if err != nil {
		t.Fatal(err)
	}
	if acc.DbKey != strings.Repeat("ab", 32) || acc.Wxid != "wxid_test" || acc.Seed != 7 {
		t.Fatalf("bad account: %+v", acc)
	}
	if _, err := parseKeyToolAccount([]byte(`{"db_key":"tooshort"}`)); err == nil {
		t.Fatal("expected error for short db_key")
	}
	if _, err := parseKeyToolAccount([]byte(`{"db_key":"zz` + strings.Repeat("ab", 31) + `"}`)); err == nil {
		t.Fatal("expected error for non-hex db_key")
	}
	diag, err := parseKeyToolDiag([]byte(`{"key":"` + strings.Repeat("cd", 32) + `","pids":2,"markers":1,"candidates":5}`))
	if err != nil {
		t.Fatal(err)
	}
	if diag.Key != strings.Repeat("cd", 32) || diag.Pids != 2 || diag.Candidates != 5 {
		t.Fatalf("bad diag: %+v", diag)
	}
	// the embedded Ed25519 key must parse and sign
	priv, err := keyToolKey()
	if err != nil {
		t.Fatalf("keyToolKey: %v", err)
	}
	if len(priv) != ed25519.PrivateKeySize {
		t.Fatalf("private key size = %d", len(priv))
	}
	_ = ed25519.Sign(priv, randBytes(t, 32))
}

func TestKeyToolCandidates(t *testing.T) {
	cands := keyToolCandidates("/tmp/x.dll")
	if cands[0] != "/tmp/x.dll" {
		t.Fatalf("explicit path must come first: %v", cands)
	}
	for i := 1; i < len(cands); i++ {
		if cands[i] == "/tmp/x.dll" {
			t.Fatal("duplicate explicit path")
		}
	}
}

func TestReadDBSalt(t *testing.T) {
	dir := t.TempDir()
	salt := randBytes(t, 16)
	path := filepath.Join(dir, "hdr.db")
	if err := os.WriteFile(path, salt, 0o600); err != nil {
		t.Fatal(err)
	}
	got, err := readDBSalt(path)
	if err != nil || !bytes.Equal(got, salt) {
		t.Fatalf("readDBSalt = %x, %v", got, err)
	}
	if _, err := readDBSalt(filepath.Join(dir, "missing.db")); err == nil {
		t.Fatal("expected error for missing file")
	}
}

func TestCollectSaltWindows(t *testing.T) {
	community := scan.DefaultPatterns[0].Name
	good := []scan.Hit{
		{Pattern: "dbsalt", WindowHex: hex.EncodeToString(randBytes(t, 32))},
		{Pattern: community, WindowHex: hex.EncodeToString(randBytes(t, 32))},
		{Pattern: "wcdb", WindowHex: hex.EncodeToString(randBytes(t, 32))}, // excluded
		{Pattern: "dbsalt", WindowHex: "zz"},                               // bad hex, skipped
		{Pattern: "dbsalt", WindowHex: ""},                                 // empty, skipped
	}
	windows := collectSaltWindows(good)
	if len(windows) != 2 {
		t.Fatalf("expected 2 windows, got %d", len(windows))
	}
}

func TestAutoFindPatterns(t *testing.T) {
	community := scan.DefaultPatterns[0]
	// salt equal to the community anchor -> only the per-install pattern
	patterns := autoFindPatterns(community.Bytes)
	if len(patterns) != 1 {
		t.Fatalf("expected only the per-install salt when it equals the community one; got %d", len(patterns))
	}
	// different salt -> per-install + community fallback
	patterns = autoFindPatterns(randBytes(t, 16))
	if len(patterns) != 2 {
		t.Fatalf("expected per-install salt + community fallback; got %d", len(patterns))
	}
}

// --- discovery ---

func TestFindWeChatDataDirsAndCollect(t *testing.T) {
	base := t.TempDir()
	wxDir := filepath.Join(base, "data", "xwechat_files")
	msgDir := filepath.Join(wxDir, "wxid_x", "db_storage", "msg")
	if err := os.MkdirAll(msgDir, 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(msgDir, "MSG.db"), []byte("x"), 0o600); err != nil {
		t.Fatal(err)
	}
	// deep media tree beyond the MSG search depth (depth 5: attach/file/2026-07/nested)
	deep := filepath.Join(wxDir, "wxid_x", "attach", "file", "2026-07", "nested")
	if err := os.MkdirAll(deep, 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(deep, "MSG.db"), []byte("x"), 0o600); err != nil {
		t.Fatal(err)
	}

	dirs := findWeChatDataDirs(base, 3)
	if len(dirs) != 1 || dirs[0] != wxDir {
		t.Fatalf("findWeChatDataDirs = %v, want [%s]", dirs, wxDir)
	}

	var out []string
	seen := map[string]bool{}
	var err error
	out, err = collectMsgDBs(wxDir, out, seen, 3, true)
	if err != nil {
		t.Fatal(err)
	}
	if len(out) != 0 {
		t.Fatalf("depth 3 should find nothing (real MSG.db is at depth 4), got %v", out)
	}
	out, err = collectMsgDBs(wxDir, out, seen, 4, true)
	if err != nil {
		t.Fatal(err)
	}
	if len(out) != 1 || out[0] != filepath.Join(msgDir, "MSG.db") {
		t.Fatalf("depth 4 should find only the real MSG.db, got %v", out)
	}
	out, err = collectMsgDBs(wxDir, out, seen, 8, true)
	if err != nil {
		t.Fatal(err)
	}
	if len(out) != 2 { // both depths now permitted
		t.Fatalf("collectMsgDBs found %d dbs: %v", len(out), out)
	}
}

func TestFindWeChatDBsDecoyIgnored(t *testing.T) {
	base := t.TempDir()
	if err := os.MkdirAll(filepath.Join(base, "xwechat_files", "sub"), 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(base, "xwechat_files", "sub", "other.db"), []byte("x"), 0o600); err != nil {
		t.Fatal(err)
	}
	found, err := findWeChatDBs([]string{base})
	if err != nil {
		t.Fatal(err)
	}
	if len(found) != 0 {
		t.Fatalf("expected no results, got %v", found)
	}
}

func TestPathDepth(t *testing.T) {
	base := filepath.Join(string(filepath.Separator), "x", "y", "z")
	nested := filepath.Join(base, "a", "b", "c", "MSG.db")
	if d := pathDepth(base, nested); d != 4 {
		t.Fatalf("pathDepth = %d, want 4", d)
	}
}

// --- handler envelope (non-Windows: unsupported_platform) ---

func TestAutoFindUnsupportedPlatform(t *testing.T) {
	if runtime.GOOS == "windows" {
		t.Skip("windows host runs the real engine")
	}
	dir := t.TempDir()
	key := randBytes(t, 32)
	dbPath := makeSQLCipherDB(t, dir, sqlcipher.ModeRaw, key)

	h := newTestServer(Config{})
	w, env := doReq(t, h, http.MethodPost, "/api/key/autofind",
		map[string]string{"Content-Type": "application/json"},
		`{"dbPath": "`+dbPath+`"}`)
	if w.Code != http.StatusNotImplemented {
		t.Fatalf("expected 501 on non-Windows, got %d: %+v", w.Code, env)
	}
	if env.Error == nil || env.Error.Code != "unsupported_platform" {
		t.Fatalf("unexpected envelope: %+v", env)
	}
}

func TestAutoFindBadRequest(t *testing.T) {
	h := newTestServer(Config{})
	w, env := doReq(t, h, http.MethodPost, "/api/key/autofind",
		map[string]string{"Content-Type": "application/json"}, `{not json`)
	if w.Code != http.StatusBadRequest || env.Error == nil || env.Error.Code != "bad_request" {
		t.Fatalf("expected bad_request, got %d %+v", w.Code, env)
	}
}

func TestAutoFindNoDatabase(t *testing.T) {
	dir := t.TempDir()
	h := newTestServer(Config{})
	w, env := doReq(t, h, http.MethodPost, "/api/key/autofind",
		map[string]string{"Content-Type": "application/json"},
		`{"dbPath": "`+filepath.Join(dir, "none.db")+`"}`)
	// salt read fails -> found:false with reason (HTTP 200, not an error)
	if w.Code != http.StatusOK || !env.Ok {
		t.Fatalf("expected ok envelope, got %d %+v", w.Code, env)
	}
	var res autoFindResult
	if err := json.Unmarshal(env.Data, &res); err != nil {
		t.Fatalf("decode result: %v", err)
	}
	if res.Found {
		t.Fatal("expected found=false")
	}
	if !strings.Contains(res.Reason, "cannot read salt") {
		t.Fatalf("unexpected reason: %q", res.Reason)
	}
}

// TestFindMessageDbNames41 covers the WeChat 4.1.x file layout: the chat log
// is split into msg_*.db / message_*.db / biz_message_*.db files (MSG.db no
// longer exists). Non-message .db files must stay ignored.
func TestFindMessageDbNames41(t *testing.T) {
	base := t.TempDir()
	root := filepath.Join(base, "xwechat_files", "wxid_x", "db_storage")
	mk := func(rel string) {
		full := filepath.Join(root, rel)
		if err := os.MkdirAll(filepath.Dir(full), 0o755); err != nil {
			t.Fatal(err)
		}
		if err := os.WriteFile(full, []byte("x"), 0o600); err != nil {
			t.Fatal(err)
		}
	}
	mk(filepath.Join("message", "message_0.db"))
	mk(filepath.Join("message", "message_1.db"))
	mk(filepath.Join("msg", "msg_2.db"))
	mk(filepath.Join("msg", "biz_message_0.db"))
	mk(filepath.Join("session", "session.db")) // not a message db
	mk(filepath.Join("msg", "media.db"))       // not a message db
	mk(filepath.Join("msg", "other.db"))

	found, err := findWeChatDBs([]string{base})
	if err != nil {
		t.Fatal(err)
	}
	want := []string{
		filepath.Join(root, "message", "message_0.db"),
		filepath.Join(root, "message", "message_1.db"),
		filepath.Join(root, "msg", "biz_message_0.db"),
		filepath.Join(root, "msg", "msg_2.db"),
	}
	if strings.Join(found, "\n") != strings.Join(want, "\n") {
		t.Fatalf("found:\n%s\nwant:\n%s", strings.Join(found, "\n"), strings.Join(want, "\n"))
	}
}
