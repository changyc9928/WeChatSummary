package bridge

import (
	"os"
	"path/filepath"
	"testing"
)

func TestNormalizeDatBase(t *testing.T) {
	// CipherTalk normalizeDatBase: strip one trailing .dat/.jpg, then
	// repeatedly strip trailing [._]<letter> pairs — NOT longer suffixes
	// like _hd/_thumb/_t_nw (those are handled by matchesDatName/includes).
	cases := map[string]string{
		"abc.dat":          "abc",
		"abc_h.dat":        "abc",
		"abc.h.dat":        "abc",
		"abc_hd.dat":       "abc_hd", // _hd is not a [._][a-z] pair
		"abc_t.dat":        "abc",
		"abc_t_W.dat":      "abc",       // _w then _t pairs
		"abc_t_NW.dat":     "abc_t_nw",  // _NW: pair _w stripped? no: "abc_t_nw" -> last pair "nw" not [._][a-z]
		"abc_h_w.dat":      "abc",       // _w then _h pairs
		"abc_thumb.dat":    "abc_thumb", // _thumb not stripped
		"AbC_JPG":          "abc_jpg",   // .jpg stripped exactly, then no pairs
		"689f239a4cfc.dat": "689f239a4cfc",
		"plain":            "plain",
		"a_b_c.dat":        "a", // _c then _b
	}
	for in, want := range cases {
		if got := normalizeDatBase(in); got != want {
			t.Errorf("normalizeDatBase(%q) = %q, want %q", in, got, want)
		}
	}
}

func TestMatchesDatName(t *testing.T) {
	cases := []struct {
		file, dat string
		want      bool
	}{
		{"abc.dat", "abc", true},
		{"abc_h.dat", "abc", true},   // normalized base equal
		{"abc_t.dat", "abc", true},   // normalized base equal
		{"abc_t_W.dat", "abc", true}, // regex `^abc(?:[._][a-z])?\.dat$` matches abc_t_W? yes ([._]t + _W)
		{"abc_hd.dat", "abc", true},  // lower.includes("abc")
		{"abc.dat", "abc_h", true},   // normalized both -> abc
		{"689f239a4cfc0f6daaea181bccb0bde3.dat", "689f239a4cfc0f6daaea181bccb0bde3", true},
		{"unrelated.dat", "abc", false},
	}
	for _, c := range cases {
		if got := matchesDatName(c.file, c.dat); got != c.want {
			t.Errorf("matchesDatName(%q, %q) = %v, want %v", c.file, c.dat, got, c.want)
		}
	}
}

func TestIsThumbnailDat(t *testing.T) {
	cases := map[string]bool{
		"abc.dat":       false,
		"abc_t.dat":     true,
		"abc.t.dat":     true,
		"abc_t_W.dat":   true,
		"abc_thumb.dat": true,
		"abc_h.dat":     false,
		"abc_hd.dat":    false,
		"abc_h_w.dat":   false,
		"abc_t_abc.dat": true,
	}
	for in, want := range cases {
		if got := isThumbnailDat(in); got != want {
			t.Errorf("isThumbnailDat(%q) = %v, want %v", in, got, want)
		}
	}
}

func TestGetLikelyDatFileNamesOrder(t *testing.T) {
	names := getLikelyDatFileNames("abc", true)
	want := []string{
		"abc.dat",
		"abc_h.dat",
		"abc.h.dat",
		"abc_hd.dat",
		"abc_t.dat",
		"abc.t.dat",
		"abc_thumb.dat",
	}
	if len(names) != len(want) {
		t.Fatalf("got %d names %v, want %d", len(names), names, len(want))
	}
	for i := range want {
		if names[i] != want[i] {
			t.Errorf("name[%d] = %q, want %q (all: %v)", i, names[i], want[i], names)
		}
	}
	// Thumbnail-disabled must drop the _t/.t/_thumb names.
	noThumb := getLikelyDatFileNames("abc", false)
	for _, n := range noThumb {
		if isThumbnailDat(n) {
			t.Errorf("allowThumbnail=false returned thumbnail %q (all: %v)", n, noThumb)
		}
	}
}

func TestFullDatIndexLookup(t *testing.T) {
	root := t.TempDir()
	dirs := []string{
		"msg/attach/2e1228eda4243db429eb18d0ab385df5/2026-08/Img",
		"msg/attach/2e1228eda4243db429eb18d0ab385df5/2026-07/Img",
		"msg/attach/7778fa9dbce759ea659df14e6648fb57/2026-08/mg",
	}
	for _, d := range dirs {
		if err := os.MkdirAll(filepath.Join(root, d), 0o755); err != nil {
			t.Fatal(err)
		}
	}
	files := map[string]string{
		"msg/attach/2e1228eda4243db429eb18d0ab385df5/2026-08/Img/689f239a4cfc0f6daaea181bccb0bde3.dat":   "full",
		"msg/attach/2e1228eda4243db429eb18d0ab385df5/2026-07/Img/689f239a4cfc0f6daaea181bccb0bde3_t.dat": "thumb",
		"msg/attach/7778fa9dbce759ea659df14e6648fb57/2026-08/mg/abc_h_w.dat":                             "variant",
	}
	for p, content := range files {
		if err := os.MkdirAll(filepath.Dir(filepath.Join(root, p)), 0o755); err != nil {
			t.Fatal(err)
		}
		if err := os.WriteFile(filepath.Join(root, p), []byte(content), 0o644); err != nil {
			t.Fatal(err)
		}
	}

	idx := buildFullDatIndex(filepath.Join(root, "msg", "attach"))
	if idx.count != 3 {
		t.Errorf("indexed %d files, want 3", idx.count)
	}
	// allowThumbnail=true accepts any candidate for the base (CipherTalk does
	// not prefer HD here — thumbnails are fine for export).
	hit := idx.lookupFullDatIndex("689f239a4cfc0f6daaea181bccb0bde3", true, false)
	if hit == "" {
		t.Errorf("allowThumbnail full lookup found nothing")
	}
	// thumbOnly returns a thumbnail.
	thumbHit := idx.lookupFullDatIndex("689f239a4cfc0f6daaea181bccb0bde3", true, true)
	if thumbHit == "" || !isThumbnailPath(thumbHit) {
		t.Errorf("thumbOnly lookup returned %q, want a thumbnail", thumbHit)
	}
	// allowThumbnail=false skips thumbnails and must find the full-size file.
	fullHit := idx.lookupFullDatIndex("689f239a4cfc0f6daaea181bccb0bde3", false, false)
	if fullHit == "" || isThumbnailPath(fullHit) {
		t.Errorf("allowThumbnail=false lookup returned %q, want the non-thumb", fullHit)
	}
	// Variant-suffixed file is reachable by its normalized base.
	vHit := idx.lookupFullDatIndex("abc", true, false)
	if vHit == "" {
		t.Errorf("variant base lookup for abc found nothing")
	}
}

func TestFastProbabilisticSearchOldLayout(t *testing.T) {
	root := t.TempDir()
	md5hex := "689f239a4cfc0f6daaea181bccb0bde3"
	p := filepath.Join(root, md5hex[0:2], md5hex[2:4], "Img", md5hex+".dat")
	if err := os.MkdirAll(filepath.Dir(p), 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(p, []byte("x"), 0o644); err != nil {
		t.Fatal(err)
	}
	hit := fastProbabilisticSearch(root, md5hex+".dat", 0)
	if hit != p {
		t.Errorf("old-layout search = %q, want %q", hit, p)
	}
}

func TestFastProbabilisticSearchNewLayout(t *testing.T) {
	root := t.TempDir()
	sess := "2e1228eda4243db429eb18d0ab385df5"
	p := filepath.Join(root, sess, "2026-08", "Img", "abc.dat")
	if err := os.MkdirAll(filepath.Dir(p), 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(p, []byte("x"), 0o644); err != nil {
		t.Fatal(err)
	}
	hit := fastProbabilisticSearch(root, "abc", 1785600000) // ~2026-08
	if hit != p {
		t.Errorf("new-layout search = %q, want %q", hit, p)
	}
}

func TestSessionStorageDirs(t *testing.T) {
	// CipherTalk resolveSessionStorageDirs: for an md5-like id it pushes the
	// raw lower value AND md5(raw); for wxid/chatroom ids it pushes md5(raw)
	// and md5(lower(raw)).
	dirs := sessionStorageDirs("2e1228eda4243db429eb18d0ab385df5")
	if len(dirs) == 0 || dirs[0] != "2e1228eda4243db429eb18d0ab385df5" {
		t.Errorf("sessionStorageDirs raw first = %v", dirs)
	}
	if len(dirs) != 2 || !looksLikeMd5(dirs[1]) {
		t.Errorf("sessionStorageDirs for md5-like id = %v, want [raw, md5(raw)]", dirs)
	}

	// A chatroom/wxid id gets its md5 and lower-md5 variants.
	dirs2 := sessionStorageDirs("wxid_c03p833r8a4422_bfe8")
	if len(dirs2) < 2 {
		t.Errorf("wxid dirs = %v, want md5 variants", dirs2)
	}
	if !looksLikeMd5(dirs2[1]) {
		t.Errorf("second candidate %q not md5", dirs2[1])
	}
}
