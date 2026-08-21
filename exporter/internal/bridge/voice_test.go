package bridge

import (
	"archive/zip"
	"bytes"
	"encoding/base64"
	"io"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"testing"

	"wechatsummary/exporter/internal/extract"
	"wechatsummary/exporter/internal/sqlite"
)

func makeVoiceDb(t *testing.T, voiceRows string) string {
	t.Helper()
	dir := t.TempDir()
	path := filepath.Join(dir, "xwechat_files", "wxid_abc", "db_storage", "message", "message_0.db")
	if err := os.MkdirAll(filepath.Dir(path), 0o755); err != nil {
		t.Fatal(err)
	}
	silkBlob := testSilkBlob(t)
	// The msg table must be named Msg_<32-hex> for FindMessageTables; the
	// 4.0-style column names map onto the bridge's extraction.
	md5 := "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
	sql := `
CREATE TABLE Name2Id(user_name TEXT);
INSERT INTO Name2Id VALUES ('wxid_peer'), ('wxid_abc');
CREATE TABLE VoiceInfo_0(
  create_time INTEGER, chat_name_id INTEGER, msg_svr_id INTEGER, voice_data BLOB
);
` + "INSERT INTO VoiceInfo_0 VALUES " + voiceRows + ";" + `
CREATE TABLE Msg_` + md5 + `(
  msgLocalID INTEGER NOT NULL, msgSvrId INTEGER, type INTEGER,
  createTime INTEGER, talker TEXT, isSend INTEGER, content TEXT
);
INSERT INTO Msg_` + md5 + ` VALUES
 (1, 10001, 34, 1750000000, 'wxid_peer', 1, '/cgi-bin/mmvoice/1'),
 (2, 10002, 34, 1750000000, 'wxid_peer', 0, '/cgi-bin/mmvoice/2'),
 (3, 10003, 1, 1750000100, 'wxid_peer', 1, 'hi');
`
	sql = strings.ReplaceAll(sql, "__SILK__", blobHexLiteral(t, silkBlob))
	cmd := exec.Command("sqlite3", path)
	cmd.Stdin = strings.NewReader(sql)
	if out, err := cmd.CombinedOutput(); err != nil {
		t.Fatalf("sqlite3 create failed: %v\n%s", err, out)
	}
	return path
}

// testSilkBlob returns a genuine short SILK container (canned at module-build
// time; the ccgo encoder is not called from tests because its unsafe pointer
// arithmetic trips checkptr under `go test -race`).
func testSilkBlob(t *testing.T) []byte {
	t.Helper()
	b, err := base64.StdEncoding.DecodeString(testRealSilkBlobB64)
	if err != nil || len(b) == 0 {
		t.Fatalf("canned blob: %v", err)
	}
	return b
}

func blobHexLiteral(t *testing.T, b []byte) string {
	t.Helper()
	h := ""
	for _, c := range b {
		h += "0123456789abcdef"[c>>4 : c>>4+1]
		h += "0123456789abcdef"[c&0xf : c&0xf+1]
	}
	return "X'" + h + "'"
}

func TestVoiceFindBlobStrategies(t *testing.T) {
	blobA := testSilkBlob(t)
	blobB := testSilkBlob(t)
	blobC := testSilkBlob(t)
	ha := blobHexLiteral(t, blobA)
	hb := blobHexLiteral(t, blobB)
	hc := blobHexLiteral(t, blobC)
	rows := "(1750000000, 1, 10001, " + ha + "), " +
		"(1750000000, 2, 0, " + hb + "), " +
		"(1750000100, 1, 0, " + hc + ")"
	db, err := sqlite.Open(newPlainSource(t, makeVoiceDb(t, rows)))
	if err != nil {
		t.Fatal(err)
	}
	vdbs := probesVoiceDBs([]*sqlite.DB{db})
	if len(vdbs) == 0 {
		t.Fatal("no VoiceInfo probed")
	}
	if vdbs[0].name2IDTbl == "" || vdbs[0].chatNameCol == "" || vdbs[0].svrIDCol == "" {
		t.Fatalf("probe incomplete: %+v", vdbs[0])
	}
	cands := []string{"wxid_peer", "wxid_abc"}

	// Strategy A: svr_id exact (row 1 has msg_svr_id=10001 chat=1).
	{
		got := findVoiceBlob(voiceMsgRef{createTime: 1750000000, localID: 1, serverID: 10001}, cands, vdbs, 0)
		if got == nil || !bytes.Equal(got, blobA) {
			t.Fatalf("strategy A: got %x want %x", got, blobA)
		}
	}
	// Strategy B: chat + createTime ORDER BY rowid; localID 1 -> idx0 -> blobA,
	// localID 2 -> idx1 -> blobB.
	{
		got := findVoiceBlob(voiceMsgRef{createTime: 1750000000, localID: 1, serverID: 0}, cands, vdbs, 0)
		if got == nil || !bytes.Equal(got, blobA) {
			t.Fatalf("strategy B idx0: got %x want %x", got, blobA)
		}
		got2 := findVoiceBlob(voiceMsgRef{createTime: 1750000000, localID: 2, serverID: 0}, cands, vdbs, 1)
		if got2 == nil || !bytes.Equal(got2, blobB) {
			t.Fatalf("strategy B idx1: got %x want %x", got2, blobB)
		}
	}
	// Strategy C: createTime only fallback (chat rowid does not match this
	// time; row 3 has chat=1 which matches, so B still picks it — force C by
	// using a session candidate not present in Name2Id).
	{
		got := findVoiceBlob(voiceMsgRef{createTime: 1750000100, localID: 3, serverID: 0}, []string{"wxid_absent"}, vdbs, 0)
		if got == nil || !bytes.Equal(got, blobC) {
			t.Fatalf("strategy C: got %x want %x", got, blobC)
		}
	}
}

// TestExportVoiceEndToEnd builds a message DB with 2 voice messages, runs
// exportVoice over the extracted messages and verifies the zip gains
// voices/20250615/....wav (CipherTalk dateFolder = YYYYMMDD) and message
// content is rewritten.
func TestExportVoiceEndToEnd(t *testing.T) {
	rows := "(1750000000, 1, 10001, __SILK__), (1750000000, 2, 0, __SILK__)"
	path := makeVoiceDb(t, strings.ReplaceAll(rows, "__SILK__", blobHexLiteral(t, testSilkBlob(t))))
	db, err := sqlite.Open(newPlainSource(t, path))
	if err != nil {
		t.Fatal(err)
	}
	tables, err := extract.FindMessageTables(db)
	if err != nil || len(tables) == 0 {
		t.Fatalf("tables: %v %v", tables, err)
	}
	tbl := tables[0]
	msgs, err := extract.ExtractMessagesRange(tbl.Table, tbl.Columns, 0, nil, 0, 0)
	if err != nil {
		t.Fatal(err)
	}
	var voiceMsgs int
	for _, m := range msgs {
		if m.LocalType == 34 {
			voiceMsgs++
		}
	}
	if voiceMsgs != 2 {
		t.Fatalf("voice msgs = %d", voiceMsgs)
	}
	var buf bytes.Buffer
	zw := zip.NewWriter(&buf)
	saved, failed := exportVoice(zw, "", nil, []*sqlite.DB{db}, msgs, []string{"wxid_peer", "wxid_abc"})
	if err := zw.Close(); err != nil {
		t.Fatal(err)
	}
	if saved != 2 || failed != 0 {
		t.Fatalf("saved=%d failed=%d", saved, failed)
	}
	zr, err := zip.NewReader(bytes.NewReader(buf.Bytes()), int64(buf.Len()))
	if err != nil {
		t.Fatal(err)
	}
	var wavEntries []string
	for _, f := range zr.File {
		if strings.HasPrefix(f.Name, "voices/") {
			wavEntries = append(wavEntries, f.Name)
			rc, err := f.Open()
			if err != nil {
				t.Fatal(err)
			}
			raw, _ := io.ReadAll(rc)
			rc.Close()
			if string(raw[:4]) != "RIFF" || string(raw[8:12]) != "WAVE" {
				t.Fatalf("%s not a wav", f.Name)
			}
		}
	}
	if len(wavEntries) != 2 {
		t.Fatalf("wav entries = %v", wavEntries)
	}
	var contentRewritten int
	for i := range msgs {
		if msgs[i].LocalType == 34 && strings.HasPrefix(msgs[i].Content, "[语音消息] voices/") {
			contentRewritten++
		}
	}
	if contentRewritten != 2 {
		t.Fatalf("content rewritten = %d (want 2)", contentRewritten)
	}
}