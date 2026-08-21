package bridge

import (
	"archive/zip"
	"bytes"
	"crypto/md5"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"testing"

	"wechatsummary/exporter/internal/sqlite"
)

// plainSource reads a plaintext SQLite file page-by-page (the sqlite reader
// operates on decrypted page images; plaintext files are the no-op case).
type plainSource struct {
	f    *os.File
	size int64
}

func newPlainSource(t *testing.T, path string) sqlite.PageSource {
	t.Helper()
	f, err := os.Open(path)
	if err != nil {
		t.Fatalf("open: %v", err)
	}
	fi, err := f.Stat()
	if err != nil {
		t.Fatalf("stat: %v", err)
	}
	return &plainSource{f: f, size: fi.Size()}
}

func (p *plainSource) ReadPage(pgno int64) ([]byte, error) {
	buf := make([]byte, 4096)
	n, err := p.f.ReadAt(buf, (pgno-1)*4096)
	if err != nil && n < 4096 {
		return nil, err
	}
	return buf[:n], nil
}

func (p *plainSource) NumPages() int64 { return p.size / 4096 }

func makePlainDb(t *testing.T, sql string) string {
	t.Helper()
	dir := t.TempDir()
	path := filepath.Join(dir, "xwechat_files", "wxid_abc", "msg", "MSG.db")
	if err := os.MkdirAll(filepath.Dir(path), 0o755); err != nil {
		t.Fatal(err)
	}
	cmd := exec.Command("sqlite3", path)
	cmd.Stdin = strings.NewReader(sql)
	if out, err := cmd.CombinedOutput(); err != nil {
		t.Fatalf("sqlite3 create failed: %v\n%s", err, out)
	}
	return path
}

func TestBuildExport(t *testing.T) {
	path := makePlainDb(t, `
CREATE TABLE MSG(
  msgLocalID INTEGER NOT NULL,
  msgSvrId INTEGER,
  type INTEGER,
  createTime INTEGER,
  talker TEXT,
  isSend INTEGER,
  content TEXT
);
INSERT INTO MSG VALUES
 (1, 10001, 1, 1750000000, 'wxid_aaa', 1, 'hello world'),
 (2, 10002, 3, 1750000100, 'wxid_bbb', 0, '/cgi-bin/mmwebwx-bin/webwxgetmsgimg?seq=9');
`)
	db, err := sqlite.Open(newPlainSource(t, path))
	if err != nil {
		t.Fatal(err)
	}
	res, err := buildExport(db, path)
	if err != nil {
		t.Fatal(err)
	}
	if res.MessageCount != 2 {
		t.Fatalf("messageCount = %d", res.MessageCount)
	}
	if res.Table != "MSG" {
		t.Fatalf("table = %s", res.Table)
	}
	if res.Nickname != "MSG.db" { // enclosing dir is "msg" -> fallback to file name
		t.Fatalf("nickname = %s", res.Nickname)
	}
	zipBytes, err := base64.StdEncoding.DecodeString(res.ZipBase64)
	if err != nil {
		t.Fatalf("bad base64: %v", err)
	}
	zr, err := zip.NewReader(bytes.NewReader(zipBytes), int64(len(zipBytes)))
	if err != nil {
		t.Fatalf("bad zip: %v", err)
	}
	if len(zr.File) != 1 || zr.File[0].Name != "MSG.db.json" {
		t.Fatalf("zip files = %v", zr.File)
	}
	rc, err := zr.File[0].Open()
	if err != nil {
		t.Fatal(err)
	}
	defer rc.Close()
	raw, err := io.ReadAll(rc)
	if err != nil {
		t.Fatal(err)
	}
	var doc struct {
		Session  json.RawMessage   `json:"session"`
		Messages []json.RawMessage `json:"messages"`
	}
	if err := json.Unmarshal(raw, &doc); err != nil {
		t.Fatalf("messages.json not the expected doc: %v", err)
	}
	if len(doc.Messages) != 2 {
		t.Fatalf("doc messages = %d", len(doc.Messages))
	}
}

func TestBuildExport41(t *testing.T) {
	hashOf := func(s string) string {
		h := md5.Sum([]byte(s))
		return hex.EncodeToString(h[:])
	}
	path := makePlainDb(t, fmt.Sprintf(`
CREATE TABLE Name2Id(rowid INTEGER PRIMARY KEY, user_name TEXT);
INSERT INTO Name2Id VALUES (1,'wxid_peer1'),(2,'wxid_me');
CREATE TABLE msg_%s(
  local_id INTEGER NOT NULL, server_id INTEGER, type INTEGER,
  create_time INTEGER, message_content TEXT, real_sender_id INTEGER, is_send INTEGER
);
CREATE TABLE msg_%s(
  local_id INTEGER NOT NULL, server_id INTEGER, type INTEGER,
  create_time INTEGER, message_content TEXT, real_sender_id INTEGER, is_send INTEGER
);
INSERT INTO msg_%s VALUES (1,9001,1,1750000000,'from peer1',1,0);
INSERT INTO msg_%s VALUES (1,9101,1,1750000100,'from me',2,1);
`, hashOf("wxid_peer1"), hashOf("wxid_peer2"), hashOf("wxid_peer1"), hashOf("wxid_peer2")))
	db, err := sqlite.Open(newPlainSource(t, path))
	if err != nil {
		t.Fatal(err)
	}
	res, err := buildExport(db, path)
	if err != nil {
		t.Fatal(err)
	}
	if res.MessageCount != 2 {
		t.Fatalf("messageCount = %d", res.MessageCount)
	}
	if !strings.Contains(res.Table, hashOf("wxid_peer1")) || !strings.Contains(res.Table, hashOf("wxid_peer2")) {
		t.Fatalf("table = %s (expected both msg_<md5> tables)", res.Table)
	}
	zipBytes, err := base64.StdEncoding.DecodeString(res.ZipBase64)
	if err != nil {
		t.Fatalf("bad base64: %v", err)
	}
	zr, err := zip.NewReader(bytes.NewReader(zipBytes), int64(len(zipBytes)))
	if err != nil {
		t.Fatalf("bad zip: %v", err)
	}
	rc, err := zr.File[0].Open()
	if err != nil {
		t.Fatal(err)
	}
	defer rc.Close()
	raw, err := io.ReadAll(rc)
	if err != nil {
		t.Fatal(err)
	}
	var doc struct {
		Messages []struct {
			Content        string `json:"content"`
			SenderUsername string `json:"senderUsername"`
			IsSend         int    `json:"isSend"`
		} `json:"messages"`
	}
	if err := json.Unmarshal(raw, &doc); err != nil {
		t.Fatalf("decode messages.json: %v", err)
	}
	byContent := map[string]struct {
		sender string
		isSend int
	}{}
	for _, m := range doc.Messages {
		byContent[m.Content] = struct {
			sender string
			isSend int
		}{m.SenderUsername, m.IsSend}
	}
	if byContent["from peer1"].sender != "wxid_peer1" || byContent["from peer1"].isSend != 0 {
		t.Fatalf("peer1 row resolved wrong: %+v", byContent["from peer1"])
	}
	if byContent["from me"].sender != "wxid_me" || byContent["from me"].isSend != 1 {
		t.Fatalf("me row resolved wrong: %+v", byContent["from me"])
	}
}

func TestExportBadKey(t *testing.T) {
	path := makePlainDb(t, `
CREATE TABLE MSG(msgLocalID INTEGER NOT NULL, content TEXT, createTime INTEGER, talker TEXT, isSend INTEGER, type INTEGER);
INSERT INTO MSG VALUES (1, 'hi', 1750000000, 'wxid_x', 1, 1);
`)
	key := strings.Repeat("ab", 32) // 64 hex chars, almost certainly wrong
	body := fmt.Sprintf(`{"dbPath":%q,"key":%q}`, path, key)
	s := newTestServer(Config{})
	w, env := doReq(t, s, http.MethodPost, "/api/export", map[string]string{"Authorization": "Bearer test-token"}, body)
	if w.Code != http.StatusBadRequest {
		t.Fatalf("status = %d, env = %+v", w.Code, env)
	}
	if env.Error == nil || env.Error.Code != "bad_key" {
		t.Fatalf("expected bad_key, got %+v", env.Error)
	}
}

func TestExportNoDatabase(t *testing.T) {
	empty := t.TempDir()
	s := newTestServer(Config{DBRoots: []string{empty}})
	w, env := doReq(t, s, http.MethodPost, "/api/export",
		map[string]string{"Authorization": "Bearer test-token"}, `{"key":"`+strings.Repeat("ab", 32)+`"}`)
	if w.Code != http.StatusBadRequest {
		t.Fatalf("status = %d, env = %+v", w.Code, env)
	}
	if env.Error == nil || env.Error.Code != "no_database" {
		t.Fatalf("expected no_database, got %+v", env.Error)
	}
}

func TestExportMethodNotAllowed(t *testing.T) {
	s := newTestServer(Config{})
	w, _ := doReq(t, s, http.MethodGet, "/api/export", map[string]string{"Authorization": "Bearer test-token"}, "")
	if w.Code != http.StatusMethodNotAllowed {
		t.Fatalf("status = %d", w.Code)
	}
}

func TestBuildExportSelection(t *testing.T) {
	hashOf := func(s string) string {
		h := md5.Sum([]byte(s))
		return hex.EncodeToString(h[:])
	}
	p1 := hashOf("wxid_peer1")
	p2 := hashOf("wxid_peer2")
	path := makePlainDb(t, fmt.Sprintf(`
CREATE TABLE msg_%s(
  local_id INTEGER NOT NULL, server_id INTEGER, type INTEGER,
  create_time INTEGER, message_content TEXT, real_sender_id INTEGER, is_send INTEGER
);
CREATE TABLE msg_%s(
  local_id INTEGER NOT NULL, server_id INTEGER, type INTEGER,
  create_time INTEGER, message_content TEXT, real_sender_id INTEGER, is_send INTEGER
);
INSERT INTO msg_%s VALUES (1,9001,1,1750000000,'early peer1',1,0);
INSERT INTO msg_%s VALUES (2,9002,1,1750001000,'late peer1',1,0);
INSERT INTO msg_%s VALUES (1,9101,1,1750000500,'peer2 only',2,1);
`, p1, p2, p1, p1, p2))
	db, err := sqlite.Open(newPlainSource(t, path))
	if err != nil {
		t.Fatal(err)
	}

	// Filter: only table p1.
	res, err := buildExportOpts(db, path, exportMediaOptions{}, exportSelection{Tables: []string{"msg_" + p1}})
	if err != nil {
		t.Fatal(err)
	}
	if res.MessageCount != 2 {
		t.Fatalf("table filter: messageCount = %d, want 2", res.MessageCount)
	}
	if strings.Contains(res.Table, p2) {
		t.Fatalf("table filter leaked %s", p2)
	}

	// Filter: table p1 AND time window [1750000500, 1750001000] -> only 'late peer1'.
	res, err = buildExportOpts(db, path, exportMediaOptions{}, exportSelection{
		Tables: []string{"msg_" + p1},
		From:   1750000500,
		To:     1750001000,
	})
	if err != nil {
		t.Fatal(err)
	}
	if res.MessageCount != 1 {
		t.Fatalf("time filter: messageCount = %d, want 1", res.MessageCount)
	}
	zipBytes, err := base64.StdEncoding.DecodeString(res.ZipBase64)
	if err != nil {
		t.Fatal(err)
	}
	zr, err := zip.NewReader(bytes.NewReader(zipBytes), int64(len(zipBytes)))
	if err != nil {
		t.Fatal(err)
	}
	rc, err := zr.File[0].Open()
	if err != nil {
		t.Fatal(err)
	}
	raw, _ := io.ReadAll(rc)
	rc.Close()
	if !strings.Contains(string(raw), "late peer1") {
		t.Fatalf("time filter kept wrong message: %s", raw)
	}
	if strings.Contains(string(raw), "early peer1") {
		t.Fatalf("time filter kept excluded message: %s", raw)
	}

	// Filter: nonexistent table -> error.
	if _, err := buildExportOpts(db, path, exportMediaOptions{}, exportSelection{Tables: []string{"msg_deadbeef"}}); err == nil {
		t.Fatal("expected error for nonexistent table selection")
	}
}

func TestBuildExportDiskDelivery(t *testing.T) {
	path := makePlainDb(t, `
CREATE TABLE MSG(
  msgLocalID INTEGER NOT NULL, msgSvrId INTEGER, type INTEGER,
  createTime INTEGER, talker TEXT, isSend INTEGER, content TEXT
);
INSERT INTO MSG VALUES (1, 10001, 1, 1750000000, 'wxid_aaa', 1, 'hello world');
`)
	db, err := sqlite.Open(newPlainSource(t, path))
	if err != nil {
		t.Fatal(err)
	}
	out := t.TempDir()
	res, err := buildExportOpts(db, path, exportMediaOptions{}, exportSelection{OutDir: out})
	if err != nil {
		t.Fatal(err)
	}
	if res.ZipPath == "" || res.ZipSize == 0 {
		t.Fatalf("expected disk zip, got path=%q size=%d", res.ZipPath, res.ZipSize)
	}
	if res.ZipBase64 != "" {
		t.Fatalf("disk delivery must not inline base64")
	}
	if !strings.HasPrefix(res.ZipPath, out) {
		t.Fatalf("zip path %s not under out dir %s", res.ZipPath, out)
	}
	fi, err := os.Stat(res.ZipPath)
	if err != nil || fi.Size() == 0 {
		t.Fatalf("zip file missing/empty: %v", err)
	}
}

func TestExportSessionsEndpoint(t *testing.T) {
	hashOf := func(s string) string {
		h := md5.Sum([]byte(s))
		return hex.EncodeToString(h[:])
	}
	p1 := hashOf("wxid_peer1")
	key := strings.Repeat("ab", 32)
	dir := t.TempDir()
	path := filepath.Join(dir, "wc.db")
	sql := fmt.Sprintf(`
PRAGMA key = "x'%s'";
PRAGMA cipher_page_size = 4096;
PRAGMA cipher_hmac_algorithm = HMAC_SHA512;
PRAGMA cipher_kdf_algorithm = PBKDF2_HMAC_SHA512;
PRAGMA kdf_iter = 256000;
PRAGMA cipher_use_hmac = ON;
CREATE TABLE msg_%s(
  local_id INTEGER NOT NULL, server_id INTEGER, type INTEGER,
  create_time INTEGER, message_content TEXT, real_sender_id INTEGER, is_send INTEGER
);
INSERT INTO msg_%s VALUES (1,9001,1,1750000000,'early',1,0);
INSERT INTO msg_%s VALUES (2,9002,1,1750001000,'late',1,0);
`, key, p1, p1, p1)
	bin := sqlcipherBin()
	if bin == "" {
		t.Skip("sqlcipher CLI not installed")
	}
	cmd := exec.Command(bin, path)
	cmd.Stdin = strings.NewReader(sql)
	if out, err := cmd.CombinedOutput(); err != nil {
		t.Fatalf("sqlcipher create failed: %v\n%s", err, out)
	}
	s := newTestServer(Config{DBRoots: []string{dir}})
	w, env := doReq(t, s, http.MethodPost, "/api/export/sessions",
		map[string]string{"Authorization": "Bearer test-token"},
		fmt.Sprintf(`{"dbPath":%q,"key":"%s"}`, path, key))
	if w.Code != http.StatusOK {
		t.Fatalf("status = %d, env = %+v", w.Code, env)
	}
	var data struct {
		Sessions []struct {
			Table   string  `json:"table"`
			Count   float64 `json:"count"`
			MinTime float64 `json:"minTime"`
			MaxTime float64 `json:"maxTime"`
		} `json:"sessions"`
	}
	if err := json.Unmarshal(env.Data, &data); err != nil {
		t.Fatalf("bad data: %v", err)
	}
	if len(data.Sessions) != 1 {
		t.Fatalf("sessions = %+v", data.Sessions)
	}
	si := data.Sessions[0]
	if si.Table != "msg_"+p1 {
		t.Fatalf("session table = %v", si.Table)
	}
	if si.Count != 2 {
		t.Fatalf("session count = %v", si.Count)
	}
	if si.MinTime != 1750000000 || si.MaxTime != 1750001000 {
		t.Fatalf("session range = %v/%v", si.MinTime, si.MaxTime)
	}
}

func sqlcipherBin() string {
	if b, err := exec.LookPath("sqlcipher"); err == nil {
		return b
	}
	if _, err := os.Stat("/opt/homebrew/opt/sqlcipher/bin/sqlcipher"); err == nil {
		return "/opt/homebrew/opt/sqlcipher/bin/sqlcipher"
	}
	return ""
}

// makePlainDbAt creates a plaintext sqlite db at the given path.
func makePlainDbAt(t *testing.T, path, sql string) {
	t.Helper()
	if err := os.MkdirAll(filepath.Dir(path), 0o755); err != nil {
		t.Fatal(err)
	}
	cmd := exec.Command("sqlite3", path)
	cmd.Stdin = strings.NewReader(sql)
	if out, err := cmd.CombinedOutput(); err != nil {
		t.Fatalf("sqlite3 create failed: %v\n%s", err, out)
	}
}

// TestBuildExportShards verifies that the same Msg_<md5> table spread across
// two message-N.db shards merges into one export (WeChat 4.1 shards history).
func TestBuildExportShards(t *testing.T) {
	hashOf := func(s string) string {
		h := md5.Sum([]byte(s))
		return hex.EncodeToString(h[:])
	}
	p1 := hashOf("wxid_peer1")
	dir := t.TempDir()
	shard1 := filepath.Join(dir, "message_0.db")
	shard2 := filepath.Join(dir, "message_1.db")
	makePlainDbAt(t, shard1, fmt.Sprintf(`
CREATE TABLE msg_%s(
  local_id INTEGER NOT NULL, server_id INTEGER, type INTEGER,
  create_time INTEGER, message_content TEXT, real_sender_id INTEGER, is_send INTEGER
);
INSERT INTO msg_%s VALUES (1,9001,1,1750000000,'early shard1',1,0);
`, p1, p1))
	makePlainDbAt(t, shard2, fmt.Sprintf(`
CREATE TABLE msg_%s(
  local_id INTEGER NOT NULL, server_id INTEGER, type INTEGER,
  create_time INTEGER, message_content TEXT, real_sender_id INTEGER, is_send INTEGER
);
INSERT INTO msg_%s VALUES (2,9002,1,1750001000,'late shard2',1,0);
`, p1, p1))

	// openMsgShards works on plaintext files via ModeRaw? No — plaintext needs
	// the plain source path. Simulate with two sqlite.DB over plain sources.
	dbs := []*sqlite.DB{
		openPlainAt(t, shard1),
		openPlainAt(t, shard2),
	}
	res, err := buildExportOptsShards(dbs, shard1, nil, exportMediaOptions{}, exportSelection{})
	if err != nil {
		t.Fatal(err)
	}
	if res.MessageCount != 2 {
		t.Fatalf("messageCount = %d, want 2 (merged across shards)", res.MessageCount)
	}
	zipBytes, err := base64.StdEncoding.DecodeString(res.ZipBase64)
	if err != nil {
		t.Fatal(err)
	}
	zr, err := zip.NewReader(bytes.NewReader(zipBytes), int64(len(zipBytes)))
	if err != nil {
		t.Fatal(err)
	}
	var found bytes.Buffer
	for _, zf := range zr.File {
		if !strings.HasSuffix(zf.Name, ".json") {
			continue
		}
		rc, err := zf.Open()
		if err != nil {
			t.Fatal(err)
		}
		io.Copy(&found, rc)
		rc.Close()
	}
	body := found.String()
	if !strings.Contains(body, "early shard1") || !strings.Contains(body, "late shard2") {
		t.Fatalf("merged messages missing: %s", body)
	}
}

func openPlainAt(t *testing.T, path string) *sqlite.DB {
	t.Helper()
	db, err := sqlite.Open(newPlainSource(t, path))
	if err != nil {
		t.Fatal(err)
	}
	return db
}

// TestSessionTableIDs verifies the session.db SessionTable -> md5(username)
// map returned by sessionTableIDs for all recognized table/column spellings.
func TestSessionTableIDs(t *testing.T) {
	hashOf := func(s string) string {
		h := md5.Sum([]byte(s))
		return hex.EncodeToString(h[:])
	}
	room := "28141105522@chatroom" // a chat id that never appears in Name2Id
	path := makePlainDb(t, strings.Join([]string{
		"CREATE TABLE SessionTable(",
		"  username TEXT, sort_timestamp INTEGER, last_timestamp INTEGER,",
		"  unread_count INTEGER, summary TEXT, last_msg_type INTEGER, type INTEGER",
		");",
		"INSERT INTO SessionTable VALUES ('" + room + "', 1780000000, 1780000001, 0, 'hi', 1, 1);",
		"INSERT INTO SessionTable VALUES ('wxid_peerZ', 1770000000, 1770000001, 0, '', 1, 1);",
	}, "\n"))
	db, err := sqlite.Open(newPlainSource(t, path))
	if err != nil {
		t.Fatal(err)
	}
	m := sessionTableIDs(db)
	if m.byMD5[hashOf(room)] != room {
		t.Fatalf("room missing from session table map; have %d entries", len(m.byMD5))
	}
	if m.byMD5[hashOf("wxid_peerZ")] != "wxid_peerZ" {
		t.Fatalf("peerZ missing from session table map")
	}
}

// TestSessionTableIDsPrefersRealIndex regresses the v0.1.24 chat-name bug: the
// session DB's tables are name-sorted, SessionDeleteTable (an empty deletion
// log) sorts before SessionTable, and the old break-on-first-match loop
// scanned the empty table and returned 0 ids — every chat name collapsed.
// sessionTableIDs must scan ALL session tables: SessionTable + the deletion
// log rows + SessionNoContactInfoTable titles (display-name fallback source).
func TestSessionTableIDsPrefersRealIndex(t *testing.T) {
	hashOf := func(s string) string {
		h := md5.Sum([]byte(s))
		return hex.EncodeToString(h[:])
	}
	room := "28141105522@chatroom"
	peer := "wxid_peerQ"
	nocontact := "wxid_nocontactQ"
	path := makePlainDb(t, strings.Join([]string{
		"CREATE TABLE SessionDeleteTable(username TEXT, delete_time INTEGER);",
		"CREATE TABLE SessionNoContactInfoTable(username TEXT, session_title TEXT, time INTEGER);",
		"CREATE TABLE SessionTable(",
		"  username TEXT, sort_timestamp INTEGER, last_timestamp INTEGER,",
		"  unread_count INTEGER, summary TEXT, last_msg_type INTEGER, type INTEGER",
		");",
		// The deletion log is empty: the old code scanned this and stopped.
		"INSERT INTO SessionDeleteTable VALUES ('gone', 1780000000);",
		"INSERT INTO SessionNoContactInfoTable VALUES ('" + nocontact + "', '某群聊', 1780000001);",
		"INSERT INTO SessionTable VALUES ('" + room + "', 1780000000, 1780000001, 0, 'hi', 1, 1);",
		"INSERT INTO SessionTable VALUES ('" + peer + "', 1770000000, 1770000001, 0, '', 1, 1);",
	}, "\n"))
	db, err := sqlite.Open(newPlainSource(t, path))
	if err != nil {
		t.Fatal(err)
	}
	m := sessionTableIDs(db)
	if m.byMD5[hashOf(room)] != room {
		t.Fatalf("room missing: SessionDeleteTable must not shadow SessionTable (have %d ids)", len(m.byMD5))
	}
	if m.byMD5[hashOf(peer)] != peer {
		t.Fatalf("peerQ missing from SessionTable")
	}
	if m.byMD5[hashOf(nocontact)] != nocontact {
		t.Fatalf("no-contact username missing from SessionNoContactInfoTable")
	}
	if m.titles[hashOf(nocontact)] != "某群聊" {
		t.Fatalf("session_title not captured: %q", m.titles[hashOf(nocontact)])
	}
	// gone was a deletion-log row: it is a real username, so sessionTableIDs
	// scanning every session table should still surface it.
	if m.byMD5[hashOf("gone")] != "gone" {
		t.Fatalf("deletion-log row gone missing (should be scanned, not skipped)")
	}
}

// TestResolveSessionNamesFromSessionDB verifies resolveSessionNames can still
// resolve chats when a readable session.db exists (plaintext in tests is not
// openable by sqlcipher, so this exercises the Name2Id fallback with the
// session-table map merged in when a real encrypted session.db is present).
func TestResolveSessionNamesFromSessionDB(t *testing.T) {
	hashOf := func(s string) string {
		h := md5.Sum([]byte(s))
		return hex.EncodeToString(h[:])
	}
	p1 := hashOf("wxid_peer1")
	path := makePlainDb(t, fmt.Sprintf(`
CREATE TABLE msg_%s(
  local_id INTEGER NOT NULL, server_id INTEGER, type INTEGER,
  create_time INTEGER, message_content TEXT, real_sender_id INTEGER, is_send INTEGER
);
INSERT INTO msg_%s VALUES (1,9001,1,1750000000,'hi',1,0);
CREATE TABLE Name2Id(rowid INTEGER PRIMARY KEY, user_name TEXT);
INSERT INTO Name2Id VALUES (1, 'wxid_peer1');
`, p1, p1))
	db, err := sqlite.Open(newPlainSource(t, path))
	if err != nil {
		t.Fatal(err)
	}
	names := resolveSessionNames(t.TempDir(), nil, []*sqlite.DB{db}, []string{"msg_" + p1})
	if names["msg_"+p1].ID != "wxid_peer1" {
		t.Fatalf("p1 session id = %q", names["msg_"+p1].ID)
	}
}

// TestResolveSessionNames verifies Msg_<md5> -> session id via Name2Id.
func TestResolveSessionNames(t *testing.T) {
	hashOf := func(s string) string {
		h := md5.Sum([]byte(s))
		return hex.EncodeToString(h[:])
	}
	p1 := hashOf("wxid_peer1")
	p2 := hashOf("wxid_peer2")
	path := makePlainDb(t, fmt.Sprintf(`
CREATE TABLE msg_%s(
  local_id INTEGER NOT NULL, server_id INTEGER, type INTEGER,
  create_time INTEGER, message_content TEXT, real_sender_id INTEGER, is_send INTEGER
);
INSERT INTO msg_%s VALUES (1,9001,1,1750000000,'hi',1,0);
CREATE TABLE msg_%s(
  local_id INTEGER NOT NULL, server_id INTEGER, type INTEGER,
  create_time INTEGER, message_content TEXT, real_sender_id INTEGER, is_send INTEGER
);
INSERT INTO msg_%s VALUES (1,9101,1,1750000500,'yo',2,0);
CREATE TABLE Name2Id(rowid INTEGER PRIMARY KEY, user_name TEXT);
INSERT INTO Name2Id VALUES (1, 'wxid_peer1');
INSERT INTO Name2Id VALUES (2, 'wxid_peer2');
`, p1, p1, p2, p2))
	db, err := sqlite.Open(newPlainSource(t, path))
	if err != nil {
		t.Fatal(err)
	}
	names := resolveSessionNames(t.TempDir(), nil, []*sqlite.DB{db}, []string{"msg_" + p1, "msg_" + p2})
	if names["msg_"+p1].ID != "wxid_peer1" {
		t.Fatalf("p1 session id = %q, want wxid_peer1", names["msg_"+p1].ID)
	}
	if names["msg_"+p1].Display != "wxid_peer1" {
		t.Fatalf("p1 display = %q", names["msg_"+p1].Display)
	}
	if names["msg_"+p2].ID != "wxid_peer2" {
		t.Fatalf("p2 session id = %q, want wxid_peer2", names["msg_"+p2].ID)
	}
}

// TestIndexHardlinkDBAndResolve verifies the WeChat 4.1 hardlink.db flow end
// to end: image_hardlink_info_* md5 -> dir1/dir2 rowids -> dir2id* names ->
// msg/attach/<dir1Name>/<dir2Name>/Img/<file_name>.
func TestIndexHardlinkDBAndResolve(t *testing.T) {
	path := makePlainDb(t, `
CREATE TABLE image_hardlink_info_xxx(
  md5 TEXT, dir1 INTEGER, dir2 INTEGER, file_name TEXT
);
INSERT INTO image_hardlink_info_xxx VALUES ('aabbccddeeff00112233445566778899', 7, 9, '2026-08.jpg');
INSERT INTO image_hardlink_info_xxx VALUES ('00000000000000000000000000000000', 1, 2, 'other.jpg');
CREATE TABLE dir2id_a(rowid INTEGER PRIMARY KEY, username TEXT);
INSERT INTO dir2id_a VALUES (7, 'dirA');
INSERT INTO dir2id_a VALUES (9, 'dirB');
INSERT INTO dir2id_a VALUES (1, 'dirC');
INSERT INTO dir2id_a VALUES (2, 'dirD');
`)
	db, err := sqlite.Open(newPlainSource(t, path))
	if err != nil {
		t.Fatal(err)
	}
	idx, reason := indexHardlinkDB(db)
	if idx == nil {
		t.Fatalf("indexHardlinkDB = nil, reason %q", reason)
	}
	if len(idx.byMD5) != 2 {
		t.Fatalf("byMD5 size = %d, want 2", len(idx.byMD5))
	}
	e, ok := idx.byMD5["aabbccddeeff00112233445566778899"]
	if !ok {
		t.Fatal("md5 row missing")
	}
	if e.dir1 != 7 || e.dir2 != 9 || e.fileName != "2026-08.jpg" {
		t.Fatalf("entry = %+v", e)
	}
	if idx.dirNames[7] != "dirA" || idx.dirNames[9] != "dirB" {
		t.Fatalf("dirNames = %v", idx.dirNames)
	}
}

// TestHardlinkResolveFromDir verifies the full 4.1 flow against a real account
// tree: encrypted (xor-only V1) image under msg/attach/<dir1>/<dir2>/Img/,
// with the hardlink index built by indexHardlinkDB and resolve() locating +
// decrypting the file.
func TestHardlinkResolveFromDir(t *testing.T) {
	acct := filepath.Join(t.TempDir(), "xwechat_files", "wxid_abc")
	imgDir := filepath.Join(acct, "msg", "attach", "dirA", "dirB", "Img")
	if err := os.MkdirAll(imgDir, 0o755); err != nil {
		t.Fatal(err)
	}
	// A tiny V1-encrypted JPEG: a structurally complete header (SOI + APP0
	// with JFIF + SOF0) so the strong-media accept-path accepts it — the
	// StrongMediaPayload gate rejects truncated fragments the way the old
	// 2-byte magic check could not.
	plain := []byte{
		0xFF, 0xD8, 0xFF, 0xE0, 0x00, 0x10, 0x4A, 0x46, 0x49, 0x46, 0x00, 0x01, 0x01, 0x00, 0x00, 0x01,
		0x00, 0x01, 0x00, 0x00, 0xFF, 0xC0, 0x00, 0x11, 0x08, 0x00, 0x10, 0x00, 0x10, 0x03, 0x01, 0x22,
		0x00, 0x02, 0x11, 0x01, 0x03, 0x11, 0x01, 0xFF, 0xD9,
	}
	enc := make([]byte, len(plain))
	for i, b := range plain {
		enc[i] = b ^ 0x23
	}
	if err := os.WriteFile(filepath.Join(imgDir, "2026-08.jpg"), enc, 0o644); err != nil {
		t.Fatal(err)
	}
	// Build the hardlink index from a plain DB (loadHardlinkIndex itself needs
	// a sqlcipher-encrypted file; the index-building + resolve are the units
	// under test here).
	dbPath := makePlainDb(t, `
CREATE TABLE image_hardlink_info_xxx(
  md5 TEXT, dir1 INTEGER, dir2 INTEGER, file_name TEXT
);
INSERT INTO image_hardlink_info_xxx VALUES ('aabbccddeeff00112233445566778899', 7, 9, '2026-08.jpg');
CREATE TABLE dir2id_a(rowid INTEGER PRIMARY KEY, username TEXT);
INSERT INTO dir2id_a VALUES (7, 'dirA');
INSERT INTO dir2id_a VALUES (9, 'dirB');
`)
	db, err := sqlite.Open(newPlainSource(t, dbPath))
	if err != nil {
		t.Fatal(err)
	}
	idx, reason := indexHardlinkDB(db)
	if idx == nil {
		t.Fatalf("index = nil: %s", reason)
	}
	got, dec, rerr := idx.resolve(acct, "aabbccddeeff00112233445566778899", 0x23, nil)
	if rerr != nil {
		t.Fatalf("resolve: %v", rerr)
	}
	if got != filepath.Join(imgDir, "2026-08.jpg") {
		t.Fatalf("path = %s", got)
	}
	if string(dec) != string(plain) {
		t.Fatalf("decrypted mismatch: %x", dec)
	}
	// Unknown md5 must fail cleanly.
	if _, _, rerr := idx.resolve(acct, "ffffffffffffffffffffffffffffffff", 0x23, nil); rerr == nil {
		t.Fatal("unknown md5 resolved without error")
	}
}
