package extract

import (
	"crypto/md5"
	"encoding/hex"
	"encoding/json"
	"fmt"
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

func makeDb(t *testing.T, sql string) string {
	t.Helper()
	dir := t.TempDir()
	path := filepath.Join(dir, "t.db")
	cmd := exec.Command("sqlite3", path)
	cmd.Stdin = strings.NewReader(sql)
	if out, err := cmd.CombinedOutput(); err != nil {
		t.Fatalf("sqlite3 create failed: %v\n%s", err, out)
	}
	return path
}

func openPlain(t *testing.T, path string) *sqlite.DB {
	t.Helper()
	db, err := sqlite.Open(newPlainSource(t, path))
	if err != nil {
		t.Fatalf("Open: %v", err)
	}
	return db
}

func TestParseColumnNames(t *testing.T) {
	cases := []struct {
		sql  string
		want []string
	}{
		{`CREATE TABLE MSG(msgLocalID INTEGER PRIMARY KEY, msgSvrId INTEGER, type INTEGER, createTime INTEGER, talker TEXT, isSend INTEGER, content TEXT)`, []string{"msgLocalID", "msgSvrId", "type", "createTime", "talker", "isSend", "content"}},
		{`CREATE TABLE "message" ("createTime" TEXT, "content" BLOB, flag INTEGER NOT NULL DEFAULT 0)`, []string{"createTime", "content", "flag"}},
		{`CREATE TABLE t(a INTEGER, b TEXT DEFAULT (1+2), c TEXT)`, []string{"a", "b", "c"}},
		{`CREATE TABLE t(a INTEGER, UNIQUE(a))`, []string{"a"}},
	}
	for i, c := range cases {
		got := parseColumnNames(c.sql)
		if strings.Join(got, ",") != strings.Join(c.want, ",") {
			t.Fatalf("case %d: got %v, want %v", i, got, c.want)
		}
	}
}

func TestExtractMessages(t *testing.T) {
	path := makeDb(t, `
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
 (2, 10002, 3, 1750000100, 'wxid_bbb', 0, '/cgi-bin/mmwebwx-bin/webwxgetmsgimg?seq=9'),
 (3, 10003, 1, 0, NULL, NULL, NULL);  -- tombstone: must be skipped
`)
	db := openPlain(t, path)
	tables, err := FindMessageTables(db)
	if err != nil {
		t.Fatal(err)
	}
	if len(tables) != 1 || tables[0].Table.Name() != "MSG" {
		t.Fatalf("tables = %v", tables)
	}
	msgs, err := ExtractMessages(tables[0].Table, tables[0].Columns, 0, nil)
	if err != nil {
		t.Fatal(err)
	}
	if len(msgs) != 2 {
		t.Fatalf("expected 2 messages (tombstone skipped), got %d", len(msgs))
	}
	m := msgs[0]
	if m.LocalID != 1 || m.PlatformMessageID != 10001 || m.CreateTime != 1750000000 {
		t.Fatalf("bad first message: %+v", m)
	}
	if m.SenderUsername != "wxid_aaa" || m.Content != "hello world" || m.IsSend != 1 {
		t.Fatalf("bad first message fields: %+v", m)
	}
	if !strings.Contains(m.FormattedTime, "2025") {
		t.Fatalf("formattedTime = %q", m.FormattedTime)
	}
	if m.Source != "wechat-extract" {
		t.Fatalf("source = %q", m.Source)
	}
}

func TestFindMessageTablesFallsBack(t *testing.T) {	path := makeDb(t, `
CREATE TABLE message(mid INTEGER PRIMARY KEY, createTime INTEGER, content TEXT, talker TEXT, isSend INTEGER, type INTEGER);
INSERT INTO message VALUES (1, 1750000000, 'hi', 'wxid_x', 1, 1);
`)
	db := openPlain(t, path)
	tables, err := FindMessageTables(db)
	if err != nil {
		t.Fatal(err)
	}
	if len(tables) != 1 || tables[0].Table.Name() != "message" {
		t.Fatalf("tables = %v", tables)
	}
	msgs, err := ExtractMessages(tables[0].Table, tables[0].Columns, 0, nil)
	if err != nil {
		t.Fatal(err)
	}
	if len(msgs) != 1 || msgs[0].Content != "hi" {
		t.Fatalf("unexpected: %+v", msgs)
	}
}

// TestExtract41 simulates the WeChat 4.1.x schema: per-session tables named
// msg_<md5> plus a Name2Id rowid<->wxid map joined via real_sender_id.
func TestExtract41(t *testing.T) {
	// md5("wxid_peer1") and md5("wxid_peer2")
	hashOf := func(s string) string {
		h := md5.Sum([]byte(s))
		return hex.EncodeToString(h[:])
	}
	path := makeDb(t, fmt.Sprintf(`
CREATE TABLE Name2Id(rowid INTEGER PRIMARY KEY, user_name TEXT);
INSERT INTO Name2Id VALUES (1,'wxid_peer1'),(2,'wxid_peer2'),(3,'wxid_me');
CREATE TABLE msg_%s(
  local_id INTEGER NOT NULL,
  server_id INTEGER,
  type INTEGER,
  local_type INTEGER,
  create_time INTEGER,
  message_content TEXT,
  real_sender_id INTEGER,
  is_send INTEGER
);
CREATE TABLE msg_%s(
  local_id INTEGER NOT NULL,
  server_id INTEGER,
  type INTEGER,
  create_time INTEGER,
  message_content TEXT,
  real_sender_id INTEGER,
  is_send INTEGER
);
INSERT INTO msg_%s VALUES
 (1, 9001, 1, 1, 1750000000, 'hello from peer1', 1, 0),
 (2, 9002, 49, 49, 1750000100, '<file>...</file>', 3, 1);
INSERT INTO msg_%s VALUES
 (1, 9101, 1, 1750000200, 'hi peer2', 3, 1);  -- table without local_type
`, hashOf("wxid_peer1"), hashOf("wxid_peer2"), hashOf("wxid_peer1"), hashOf("wxid_peer2")))
	db := openPlain(t, path)
	tables, err := FindMessageTables(db)
	if err != nil {
		t.Fatal(err)
	}
	if len(tables) != 2 {
		t.Fatalf("expected 2 per-session tables, got %d", len(tables))
	}
	name2id := LoadName2Id(db)
	if len(name2id) != 3 {
		t.Fatalf("name2id = %v", name2id)
	}
	var msgs []Message
	for _, mt := range tables {
		part, err := ExtractMessages(mt.Table, mt.Columns, 0, name2id)
		if err != nil {
			t.Fatal(err)
		}
		msgs = append(msgs, part...)
	}
	if len(msgs) != 3 {
		t.Fatalf("expected 3 messages, got %d", len(msgs))
	}
	var peer1In, peer1Out bool
	for _, m := range msgs {
		switch {
		case m.Content == "hello from peer1" && m.SenderUsername == "wxid_peer1" && m.IsSend == 0:
			peer1In = true
		case m.Content == "<file>...</file>" && m.SenderUsername == "wxid_me" && m.IsSend == 1:
			peer1Out = true
		}
		if m.PlatformMessageID == 9101 && m.SenderUsername != "wxid_me" {
			t.Fatalf("peer2 row sender = %q", m.SenderUsername)
		}
	}
	if !peer1In || !peer1Out {
		t.Fatalf("missing expected messages: %+v", msgs)
	}
}

func TestExtractJSONShape(t *testing.T) {
	ex := BuildExport([]Message{{CreateTime: 1750000000, Content: "x", SenderUsername: "wxid_a"}}, "wxid_test")
	b, err := Marshal(ex)
	if err != nil {
		t.Fatal(err)
	}
	var doc map[string]any
	if err := json.Unmarshal(b, &doc); err != nil {
		t.Fatal(err)
	}
	if _, ok := doc["messages"]; !ok {
		t.Fatal("missing messages key")
	}
	if _, ok := doc["session"]; !ok {
		t.Fatal("missing session key")
	}
}

// TestFindMessageTablesCapitalMsg guards the 4.1.12.26 table naming: the
// per-session chat tables are Msg_<md5> with a capital M, which the previous
// case-sensitive regex (^msg_...) silently skipped, making the export report
// "no chat-log table found" on every 4.1.12.26 install.
func TestFindMessageTablesCapitalMsg(t *testing.T) {
	path := makeDb(t, `
CREATE TABLE Msg_00d5d9b804b9756dc952f6d5168e1922(
  msgLocalID INTEGER NOT NULL,
  msgSvrId INTEGER,
  type INTEGER,
  createTime INTEGER,
  talker TEXT,
  isSend INTEGER,
  content TEXT
);
INSERT INTO Msg_00d5d9b804b9756dc952f6d5168e1922 VALUES
 (1, 10001, 1, 1750000000, 'wxid_aaa', 1, 'hello');
`)
	db := openPlain(t, path)
	tables, err := FindMessageTables(db)
	if err != nil {
		t.Fatalf("capital-M Msg_ table must be found: %v", err)
	}
	if len(tables) != 1 || tables[0].Table.Name() != "Msg_00d5d9b804b9756dc952f6d5168e1922" {
		t.Fatalf("tables = %v", tables)
	}
	msgs, err := ExtractMessages(tables[0].Table, tables[0].Columns, 0, nil)
	if err != nil {
		t.Fatal(err)
	}
	if len(msgs) != 1 || msgs[0].Content != "hello" {
		t.Fatalf("messages = %+v", msgs)
	}
}
