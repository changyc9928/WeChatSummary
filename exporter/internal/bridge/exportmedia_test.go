package bridge

import (
	"archive/zip"
	"bytes"
	"crypto/aes"
	"crypto/md5"
	"encoding/base64"
	"encoding/binary"
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
	"time"

	"wechatsummary/exporter/internal/datdecrypt"
	"wechatsummary/exporter/internal/sqlite"
)

func md5Sum(s string) [16]byte { return md5.Sum([]byte(s)) }

// TestEmojiFieldsFromXML verifies the CipherTalk emoji XML field parsing
// (cdnurl|thumburl, (emoticon)md5, encrypturl, aeskey, &amp; decoding).
func TestEmojiFieldsFromXML(t *testing.T) {
	xml := `<msg><emoji cdnurl="http://emoji.qpic.cn/wx_emoji/a.gif?t=1&amp;x=2" emoticonmd5="021f90f74ca157d280d7a6a82064c109" encrypturl="https://example.com/e.bin" aeskey="AbCd1234eFgh5678" type="2"/></msg>`
	f := emojiFieldsFromXML(xml, "")
	if f.cdnURL != "http://emoji.qpic.cn/wx_emoji/a.gif?t=1&x=2" {
		t.Errorf("cdnURL = %q", f.cdnURL)
	}
	if f.md5 != "021f90f74ca157d280d7a6a82064c109" {
		t.Errorf("md5 = %q", f.md5)
	}
	if f.encryptURL != "https://example.com/e.bin" {
		t.Errorf("encryptURL = %q", f.encryptURL)
	}
	if f.aesKey != "AbCd1234eFgh5678" {
		t.Errorf("aesKey = %q", f.aesKey)
	}
	// thumburl fallback + <md5> tag form
	f2 := emojiFieldsFromXML(`<msg><emoji thumburl="https://cdn/x.png"/><md5>99887766554433221100aabbccddeeff</md5></msg>`, "")
	if f2.cdnURL != "https://cdn/x.png" || f2.md5 != "99887766554433221100aabbccddeeff" {
		t.Errorf("f2 = %+v", f2)
	}
	// plain md5= attribute (no emoticon prefix)
	f3 := emojiFieldsFromXML(`<emoji md5="11223344556677889900aabbccddeeff"/>`, "")
	if f3.md5 != "11223344556677889900aabbccddeeff" {
		t.Errorf("f3.md5 = %q", f3.md5)
	}
}

// TestHashStringCipherTalk checks the JS-compatible hashString (djb2, 32-bit
// truncation, |hash| in hex) against values computed with Node's runtime.
func TestHashStringCipherTalk(t *testing.T) {
	cases := []struct {
		in   string
		want string
	}{
		{"", "0"},
		{"abc", "17862"},
		{"https://thirdwx.qlogo.cn/mmopen/g3MonUZtNHkdm/132?wx_fmt=gif", "561172f"},
		{"http://emoji.qpic.cn/wx_emoji/abc123.gif", "9964c9c"},
	}
	for _, c := range cases {
		if got := hashStringCipherTalk(c.in); got != c.want {
			t.Errorf("hashStringCipherTalk(%q) = %s, want %s", c.in, got, c.want)
		}
	}
}

// TestDetectEmojiExtCipherTalk mirrors CipherTalk's detectEmojiExt.
func TestDetectEmojiExtCipherTalk(t *testing.T) {
	cases := []struct {
		name string
		head []byte
		want string
	}{
		{"png", []byte{0x89, 0x50, 0x4e, 0x47}, ".png"},
		{"jpg", []byte{0xff, 0xd8, 0xff, 0xe0}, ".jpg"},
		{"webp", []byte("RIFF...."), ".webp"},
		{"gif", []byte("GIF89a"), ".gif"},
		{"unknown", []byte{1, 2, 3}, ".gif"},
		{"empty", nil, ".gif"},
	}
	for _, c := range cases {
		if got := detectEmojiExtCipherTalk(c.head); got != c.want {
			t.Errorf("%s: detectEmojiExtCipherTalk = %q, want %q", c.name, got, c.want)
		}
	}
}

// TestDecryptEmojiAES round-trips an emoji encryptUrl payload: AES-128-ECB
// with key = first 16 hex chars of md5(aeskey), PKCS7 auto-padding.
func TestDecryptEmojiAES(t *testing.T) {
	aesKey := "AbCd1234eFgh5678"
	sum := md5.Sum([]byte(aesKey))
	hexKey := hex.EncodeToString(sum[:])[:16]
	plain := append([]byte("GIF89a-fake-emoji"), bytes.Repeat([]byte{0x42}, 64)...)
	enc := encAESECBTest(t, []byte(hexKey), pkcs7PadTest(plain, 16))
	dec, err := decryptEmojiAES(enc, aesKey)
	if err != nil {
		t.Fatal(err)
	}
	if !bytes.Equal(dec, plain) {
		t.Fatalf("roundtrip mismatch: %d vs %d bytes", len(dec), len(plain))
	}
	if got := detectEmojiExtCipherTalk(dec); got != ".gif" {
		t.Fatalf("ext = %q", got)
	}
	// wrong aeskey must fail (bad padding) — do not silently produce garbage
	if _, err := decryptEmojiAES(enc, "WrongKey00000000"); err == nil {
		t.Fatalf("wrong aeskey decrypted successfully")
	}
}

// TestResolveEmojiLocalCacheOnly verifies the emoji fetch chain prefers the
// plaintext Emojis cache and NEVER reads business/emoticon/Persist raw files
// (the earlier bridge bug that exported encrypted Persist bytes as .gif).
func TestResolveEmojiLocalCacheOnly(t *testing.T) {
	dir := t.TempDir()
	accountDir := filepath.Join(dir, "xwechat_files", "wxid_abc")
	md5v := "021f90f74ca157d280d7a6a82064c109"
	// Persist file exists (encrypted) but must NOT be picked up.
	persist := filepath.Join(accountDir, "business", "emoticon", "Persist", md5v[:2])
	if err := os.MkdirAll(persist, 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(persist, md5v), []byte("encrypted-bytes-not-gif"), 0o600); err != nil {
		t.Fatal(err)
	}
	me := &mediaExportRuntime{accountDir: accountDir}
	f := emojiFields{md5: md5v, cdnURL: "", encryptURL: "", aesKey: ""}
	if data, src := me.resolveEmoji(md5v, f); data != nil {
		t.Fatalf("Persist file leaked into export (src=%s, %d bytes)", src, len(data))
	}
	// Now drop a real plaintext into the Emojis cache dir: it must win.
	emojiDir := filepath.Join(accountDir, "Emojis")
	if err := os.MkdirAll(emojiDir, 0o755); err != nil {
		t.Fatal(err)
	}
	want := []byte("GIF89a-real-emoji")
	if err := os.WriteFile(filepath.Join(emojiDir, md5v+".gif"), want, 0o600); err != nil {
		t.Fatal(err)
	}
	// fresh runtime: the failed lookup above must NOT have poisoned it
	me = &mediaExportRuntime{accountDir: accountDir}
	data, src := me.resolveEmoji(md5v, f)
	if data == nil || src != "local" || !bytes.Equal(data, want) {
		t.Fatalf("local cache resolve = %q %q", src, data)
	}
	// memoization: second call returns the same bytes without re-reading.
	if data2, src2 := me.resolveEmoji(md5v, f); src2 != "memo" || !bytes.Equal(data2, want) {
		t.Fatalf("memo resolve = %q %q", src2, data2)
	}
}

// TestVerifyAesKeyStrict ensures a candidate AES key is accepted ONLY when it
// fully decrypts a V2 template (strict PKCS7 + image magic). A key that
// merely matches a 16-byte block "magic" (the old block-magic fallback, e.g.
// a chance "BM" BMP prefix) must be rejected.
func TestVerifyAesKeyStrict(t *testing.T) {
	correctKey := []byte("0123456789abcdef")
	xorKey := byte(0x76)
	plain := append([]byte{0xff, 0xd8, 0xff, 0xe0, 0, 0x10, 'J', 'F', 'I', 'F', 0, 1}, bytes.Repeat([]byte{0x5a}, 300)...)
	// template tail must satisfy XorKeyFromTemplate: last two bytes are the
	// xored JPEG EOI ff d9, i.e. stored as (xor^ff, xor^d9).
	xored := []byte{xorKey ^ 0xff, xorKey ^ 0xd9}
	tpl := buildV2FileTest(t, correctKey, xorKey, plain, nil, xored)
	dir := t.TempDir()
	p := filepath.Join(dir, "x_t.dat")
	if err := os.WriteFile(p, tpl, 0o600); err != nil {
		t.Fatal(err)
	}
	ok, how := verifyAesKey(correctKey, []string{p})
	if !ok || how != "template-decrypt" {
		t.Fatalf("correct key rejected: %v %q", ok, how)
	}
	// wrong key: full decrypt must fail (PKCS7) -> rejected, no block-magic.
	ok, how = verifyAesKey([]byte("abcdef0123456789"), []string{p})
	if ok {
		t.Fatalf("wrong key accepted as %q", how)
	}
	// A key whose single first block happens to decrypt to "BM" (BMP magic)
	// must still be rejected: search for such a key by brute force over the
	// first ciphertext block, then confirm verifyAesKey says no.
	ct := tpl[0x0f : 0x0f+16]
	bmKey := findKeyWithBlockPrefix(t, ct, []byte{'B', 'M'})
	if bmKey == nil {
		t.Skip("no BM-prefix key found (should not happen: 2^16 search)")
	}
	ok, how = verifyAesKey(bmKey, []string{p})
	if ok {
		t.Fatalf("BM-block-magic key accepted as %q — block-magic fallback must be gone", how)
	}
}

// --- helpers ---

func encAESECBTest(t *testing.T, key, data []byte) []byte {
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

func pkcs7PadTest(data []byte, block int) []byte {
	n := block - len(data)%block
	out := append([]byte{}, data...)
	for i := 0; i < n; i++ {
		out = append(out, byte(n))
	}
	return out
}

func buildV2FileTest(t *testing.T, aesKey []byte, xorKey byte, plainAES, raw, xored []byte) []byte {
	t.Helper()
	enc := encAESECBTest(t, aesKey, pkcs7PadTest(plainAES, 16))
	body := append(append(append([]byte{}, enc...), raw...), xored...)
	hdr := make([]byte, 15)
	copy(hdr, []byte{0x07, 0x08, 0x56, 0x32, 0x08, 0x07})
	binary.LittleEndian.PutUint32(hdr[6:10], uint32(len(plainAES)))
	binary.LittleEndian.PutUint32(hdr[10:14], uint32(len(xored)))
	return append(hdr, body...)
}

// findKeyWithBlockPrefix brute-forces a 16-byte ASCII key whose AES-ECB
// decryption of block starts with prefix (a deterministic stand-in for the
// chance 2-byte "BM" false positive the old block-magic check accepted).
func findKeyWithBlockPrefix(t *testing.T, block, prefix []byte) []byte {
	t.Helper()
	if len(prefix) > 2 {
		t.Fatalf("prefix too long: %d", len(prefix))
	}
	// space of 2^16 ASCII key suffixes; the first 14 bytes are fixed.
	base := []byte("0123456789abcd")
	for hi := 0; hi < 256; hi++ {
		for lo := 0; lo < 256; lo++ {
			k := append(append([]byte{}, base...), byte(hi), byte(lo))
			dec, err := datdecrypt.DecryptAESBlock(k, block)
			if err != nil {
				return nil
			}
			match := true
			for i := range prefix {
				if dec[i] != prefix[i] {
					match = false
					break
				}
			}
			if match {
				return k
			}
		}
	}
	return nil
}

// jpgPayload returns a small fake JPEG (with EOI) so ext sniffing works.
func jpgPayload() []byte {
	return append([]byte{0xff, 0xd8, 0xff, 0xe0, 0, 0x10, 'J', 'F', 'I', 'F', 0, 1, 1, 0, 0, 1},
		bytes.Repeat([]byte{0x42}, 128)...)
}

// newMediaTestEnv builds a fake 4.1 account: DB at <root>/xwechat_files/
// wxid_abc/db_storage/message/MSG.db with one per-session table msg_<hash>
// (image + text rows), plus a V3-XORed thumbnail .dat under
// msg/attach/<hash>/<YYYY-MM>/Img/<md5>_t.dat (hash = md5 of the session id,
// the same value that names the table).
func newMediaTestEnv(t *testing.T, createTime int64, imageMd5 string, xorKey byte) (dbPath, accountDir string) {
	t.Helper()
	dir := t.TempDir()
	sessionHash := fmt.Sprintf("%x", md5Sum("wxid_peer"))
	dbPath = filepath.Join(dir, "xwechat_files", "wxid_abc", "db_storage", "message", "MSG.db")
	accountDir = filepath.Join(dir, "xwechat_files", "wxid_abc")
	if err := os.MkdirAll(filepath.Dir(dbPath), 0o755); err != nil {
		t.Fatal(err)
	}
	xml := fmt.Sprintf(`<img type="3" md5="%s" len="123"/>`, imageMd5)
	// WeChat 4.1.x layout: message_content is a binary blob; the readable
	// form lives in compress_content (zstd/hex/base64-wrapped or plain).
	// Model that here: binary content column + readable compress_content.
	binImg := "X'010101'"
	binText := "X'68656C6C6F2074657874206D657373616765'" // "hello text message"
	sql := fmt.Sprintf(`
CREATE TABLE msg_%s(
  local_id INTEGER NOT NULL, server_id INTEGER, type INTEGER,
  create_time INTEGER, message_content BLOB, compress_content TEXT,
  real_sender_id INTEGER, is_send INTEGER
);
INSERT INTO msg_%s VALUES
 (1, 9001, 3, %d, %s, '%s', 1, 0),
 (2, 9002, 1, %d, %s, 'hello text message', 1, 1);
`, sessionHash, sessionHash, createTime, binImg, xml, createTime+60, binText)
	cmd := exec.Command("sqlite3", dbPath)
	cmd.Stdin = strings.NewReader(sql)
	if out, err := cmd.CombinedOutput(); err != nil {
		t.Fatalf("sqlite3 create failed: %v\n%s", err, out)
	}

	month := time.Unix(createTime, 0).Format("2006-01")
	attachDir := filepath.Join(accountDir, "msg", "attach", sessionHash, month, "Img")
	if err := os.MkdirAll(attachDir, 0o755); err != nil {
		t.Fatal(err)
	}
	plain := jpgPayload()
	xored := make([]byte, len(plain))
	for i, b := range plain {
		xored[i] = b ^ xorKey
	}
	if err := os.WriteFile(filepath.Join(attachDir, imageMd5+"_t.dat"), xored, 0o600); err != nil {
		t.Fatal(err)
	}
	return dbPath, accountDir
}

func openExportDB(t *testing.T, path string) *sqlite.DB {
	t.Helper()
	db, err := sqlite.Open(newPlainSource(t, path))
	if err != nil {
		t.Fatal(err)
	}
	return db
}

func TestExportWithMedia(t *testing.T) {
	const createTime = 1750000100 // 2025-06-15
	const imageMd5 = "aabbccddeeff00112233445566778899"
	dbPath, accountDir := newMediaTestEnv(t, createTime, imageMd5, 0x73)
	db := openExportDB(t, dbPath)
	res, err := buildExportOpts(db, dbPath, exportMediaOptions{
		Enabled:    true,
		AccountDir: accountDir,
		XorKey:     "73",
	}, exportSelection{})
	if err != nil {
		t.Fatal(err)
	}
	if res.MediaCount != 1 || res.MediaFailed != 0 {
		t.Fatalf("media counts = %d/%d (reason %s)", res.MediaCount, res.MediaFailed, res.MediaReason)
	}
	zipBytes, err := base64.StdEncoding.DecodeString(res.ZipBase64)
	if err != nil {
		t.Fatal(err)
	}
	zr, err := zip.NewReader(bytes.NewReader(zipBytes), int64(len(zipBytes)))
	if err != nil {
		t.Fatal(err)
	}
	entries := map[string][]byte{}
	for _, f := range zr.File {
		rc, rerr := f.Open()
		if rerr != nil {
			t.Fatal(rerr)
		}
		b, rerr := io.ReadAll(rc)
		rc.Close()
		if rerr != nil {
			t.Fatal(rerr)
		}
		entries[f.Name] = b
	}
	jsonName := ""
	for name := range entries {
		if strings.HasSuffix(name, ".json") {
			jsonName = name
			break
		}
	}
	if jsonName == "" {
		t.Fatalf("zip missing json entry (entries: %d)", len(entries))
	}
	wantRel := fmt.Sprintf("images/20250615/%d_%s.jpg", createTime, imageMd5)
	img, ok := entries[wantRel]
	if !ok {
		t.Fatalf("zip missing %s", wantRel)
	}
	if !bytes.Equal(jpgPayload(), img) {
		t.Fatalf("media payload mismatch: %d vs %d bytes", len(img), len(jpgPayload()))
	}
	var doc struct {
		Messages []struct {
			Type    string `json:"type"`
			Content string `json:"content"`
		} `json:"messages"`
	}
	if err := json.Unmarshal(entries[jsonName], &doc); err != nil {
		t.Fatal(err)
	}
	if doc.Messages[0].Type != "图片消息" || doc.Messages[0].Content != "[图片] "+wantRel {
		t.Fatalf("image message = %+v", doc.Messages[0])
	}
	if doc.Messages[1].Type != "文本消息" || doc.Messages[1].Content != "hello text message" {
		t.Fatalf("text message = %+v", doc.Messages[1])
	}
}

func TestExportWithMediaAutoXorFromHeader(t *testing.T) {
	const createTime = 1750000100
	const imageMd5 = "aabbccddeeff00112233445566778899"
	dbPath, accountDir := newMediaTestEnv(t, createTime, imageMd5, 0x73)
	db := openExportDB(t, dbPath)
	// no manual xor: the V3 file's jpg magic must yield the key automatically
	res, err := buildExportOpts(db, dbPath, exportMediaOptions{
		Enabled:    true,
		AccountDir: accountDir,
	}, exportSelection{})
	if err != nil {
		t.Fatal(err)
	}
	if res.MediaCount != 1 {
		t.Fatalf("media counts = %d (reason %s)", res.MediaCount, res.MediaReason)
	}
}

func TestExportWithoutMedia(t *testing.T) {
	const createTime = 1750000100
	const imageMd5 = "aabbccddeeff00112233445566778899"
	dbPath, _ := newMediaTestEnv(t, createTime, imageMd5, 0x73)
	db := openExportDB(t, dbPath)
	res, err := buildExport(db, dbPath)
	if err != nil {
		t.Fatal(err)
	}
	if res.MediaCount != 0 {
		t.Fatalf("unexpected media: %+v", res)
	}
	zipBytes, _ := base64.StdEncoding.DecodeString(res.ZipBase64)
	zr, err := zip.NewReader(bytes.NewReader(zipBytes), int64(len(zipBytes)))
	if err != nil {
		t.Fatal(err)
	}
	if len(zr.File) != 1 || !strings.HasSuffix(zr.File[0].Name, ".json") {
		t.Fatalf("zip = %v", zr.File)
	}
}

func TestLogsEndpoint(t *testing.T) {
	// deterministic regardless of test execution order: seed the ring first
	bridgeLog.Add("info", "test seed line")
	s := newTestServer(Config{})
	w, env := doReq(t, s, http.MethodGet, "/api/logs?after=0", nil, "")
	if w.Code != http.StatusOK || !env.Ok {
		t.Fatalf("logs: %d %+v", w.Code, env)
	}
	var data logsResponse
	if err := json.Unmarshal(env.Data, &data); err != nil {
		t.Fatal(err)
	}
	if data.Next <= 0 {
		t.Fatalf("logs next = %d", data.Next)
	}
	found := false
	for _, l := range data.Lines {
		if l.Seq > 0 && l.Msg != "" {
			found = true
		}
	}
	if !found {
		t.Fatalf("no log lines: %+v", data.Lines)
	}
}
