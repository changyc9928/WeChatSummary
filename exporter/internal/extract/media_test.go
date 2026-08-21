package extract

import (
	"encoding/hex"
	"strings"
	"testing"
)

func TestLocalTypeName(t *testing.T) {
	cases := map[int64]string{
		1:     "文本消息",
		3:     "图片消息",
		34:    "语音消息",
		42:    "名片消息",
		43:    "视频消息",
		47:    "动画表情",
		48:    "位置消息",
		49:    "链接消息",
		50:    "通话消息",
		10000: "系统消息",
		999:   "其他消息",
	}
	for lt, want := range cases {
		if got := LocalTypeName(lt); got != want {
			t.Errorf("LocalTypeName(%d) = %q, want %q", lt, got, want)
		}
	}
}

func TestMediaMd5FromXML(t *testing.T) {
	cases := []struct {
		raw  string
		want string
	}{
		{`<img type="3" md5="a1b2c3d4e5f60798a1b2c3d4e5f60798" len="123"/>`, "a1b2c3d4e5f60798a1b2c3d4e5f60798"},
		{`<msg><md5>ABCDEF0123456789ABCDEF0123456789</md5></msg>`, "abcdef0123456789abcdef0123456789"},
		{`no md5 here`, ""},
		{`len="4" md5 = "00112233445566778899aabbccddeeff" tail`, "00112233445566778899aabbccddeeff"},
	}
	for _, c := range cases {
		if got := MediaMd5FromXML(c.raw); got != c.want {
			t.Errorf("MediaMd5FromXML(%q) = %q, want %q", c.raw, got, c.want)
		}
	}
}

func TestParseImageDatName(t *testing.T) {
	// blob with printable "packed ... a1b2c3d4e5f60798.dat ..." content
	blob := []byte("some packed info a1b2c3d4e5f60798.dat trailing")
	if got := ParseImageDatName(blob); got != "a1b2c3d4e5f60798" {
		t.Errorf("blob: got %q", got)
	}
	// hex-encoded string of that blob
	hexStr := hex.EncodeToString(blob)
	if got := ParseImageDatName(hexStr); got != "a1b2c3d4e5f60798" {
		t.Errorf("hex string: got %q", got)
	}
	// long hex run fallback (no .dat token), embedded in a blob payload
	if got := ParseImageDatName(hex.EncodeToString([]byte("xx feedfacefeedfacefeedfacefeedface yy"))); got != "feedfacefeedfacefeedfacefeedface" {
		t.Errorf("hex run: got %q", got)
	}
	if got := ParseImageDatName("nothing useful"); got != "" {
		t.Errorf("garbage: got %q", got)
	}
	if got := ParseImageDatName([]byte{0x00, 0x01, 0xff}); got != "" {
		t.Errorf("binary: got %q", got)
	}
}

func TestScanPackedInfo(t *testing.T) {
	blob := []byte("wxid_abc a1b2c3d4e5f60798.dat")
	blobHex := hex.EncodeToString(blob)
	path := makeDb(t, `
CREATE TABLE t(local_id INTEGER, packed_info_data BLOB, message_content TEXT);
INSERT INTO t VALUES (7, x'`+blobHex+`', '<img md5="00000000000000000000000000000000"/>');
INSERT INTO t VALUES (8, NULL, 'plain');
`)
	db := openPlain(t, path)
	tbl, err := db.Table("t")
	if err != nil {
		t.Fatal(err)
	}
	cols := parseColumnNames(`CREATE TABLE t(local_id INTEGER, packed_info_data BLOB, message_content TEXT)`)
	got, samples, err := ScanPackedInfo(tbl, cols)
	if err != nil {
		t.Fatal(err)
	}
	// The map is keyed by b-tree rowid (rowid 1 and 2 here, not local_id).
	if got[1] != "a1b2c3d4e5f60798" {
		t.Fatalf("row 1 datName = %q (want rowid-keyed map)", got[1])
	}
	if _, ok := got[7]; ok {
		t.Fatal("map must not be keyed by local_id=7")
	}
	if _, ok := got[2]; ok {
		t.Fatal("row 2 must not have a datName (NULL packed info)")
	}
	if len(samples) != 0 {
		t.Fatalf("no unparseable blobs expected, got %d samples", len(samples))
	}
	// no packed-info column -> empty result, no error
	got2, _, err := ScanPackedInfo(tbl, []string{"local_id", "message_content"})
	if err != nil || len(got2) != 0 {
		t.Fatalf("no-col case: %v %v", got2, err)
	}
}

func TestPackedBytesRejectsTinyHex(t *testing.T) {
	// two hex characters are not a usable blob for naming
	if got := ParseImageDatName("ab"); got != "" {
		t.Fatalf("got %q", got)
	}
	if !strings.Contains("x", "x") {
		t.Fatal("unreachable")
	}
}
