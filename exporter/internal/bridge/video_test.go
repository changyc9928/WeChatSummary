package bridge

import (
	"fmt"
	"os"
	"path/filepath"
	"testing"
)

// TestVideoMd5Candidates verifies CipherTalk's candidate collection: generic
// XML md5 first, then videomsg attrs (newmd5/md5/rawmd5/originsourcemd5),
// then the same as child tags; deduped and lowercased.
func TestVideoMd5Candidates(t *testing.T) {
	raw := `<msg><videomsg aeskey="abc" cdnthumburl="x" length="1048576" 
	    newmd5="AAAA1111AAAA1111AAAA1111AAAA1111" md5="BBBB2222BBBB2222BBBB2222BBBB2222" 
	    rawmd5="CCCC3333CCCC3333CCCC3333CCCC3333" originsourcemd5="DDDD4444DDDD4444DDDD4444DDDD4444"/></msg>`
	c := videoMd5Candidates(raw, "")
	want := []string{
		"bbbb2222bbbb2222bbbb2222bbbb2222", // generic md5 attr first
		"aaaa1111aaaa1111aaaa1111aaaa1111", // newmd5 attr
		"cccc3333cccc3333cccc3333cccc3333", // rawmd5 attr
		"dddd4444dddd4444dddd4444dddd4444", // originsourcemd5 attr
	}
	if len(c) != len(want) {
		t.Fatalf("candidates = %v, want %v", c, want)
	}
	for i := range want {
		if c[i] != want[i] {
			t.Fatalf("candidates[%d] = %q, want %q (all: %v)", i, c[i], want[i], c)
		}
	}
	// Child-tag forms and dedup against already-seen values.
	raw2 := `<msg><videomsg length="1"><newmd5>EEE5555EEE5555EEE5555EEE55555555</newmd5><md5>BBBB2222BBBB2222BBBB2222BBBB2222</md5></videomsg></msg>`
	c2 := videoMd5Candidates(raw2, "")
	found := false
	for _, v := range c2 {
		if v == "eee5555eee5555eee5555eee55555555" {
			found = true
		}
	}
	if !found {
		t.Fatalf("tag candidate missing: %v", c2)
	}
	if n := len(c2); n != 2 { // EEE + (BBBB deduped)
		t.Fatalf("c2 = %v, want 2 entries (dup dropped)", c2)
	}
	if got := videoLengthFromXML(raw); got != 1048576 {
		t.Fatalf("length = %d", got)
	}
}

// TestVideoKeyCandidates verifies _raw/_hd suffix stripping and extension
// handling on hardlink file names.
func TestVideoKeyCandidates(t *testing.T) {
	got := videoKeyCandidates(`/msg/video/202608/4ac955654bc62d785bfef4a524e95071_raw.mp4`)
	want := []string{
		"4ac955654bc62d785bfef4a524e95071_raw.mp4",
		"4ac955654bc62d785bfef4a524e95071_raw",
		"4ac955654bc62d785bfef4a524e95071",
	}
	if len(got) != len(want) {
		t.Fatalf("keys = %v", got)
	}
	for i := range want {
		if got[i] != want[i] {
			t.Fatalf("keys[%d] = %q, want %q", i, got[i], want[i])
		}
	}
	if s := videoStemKey("4ac955654bc62d785bfef4a524e95071_hd.mov"); s != "4ac955654bc62d785bfef4a524e95071" {
		t.Fatalf("stem = %q", s)
	}
}

// TestResolveLocalVideoPrefixAndRaw sets up msg/video/<YYYY-MM>/ and
// msg/video/<YYYYMM>/ dirs with _raw-suffixed files and verifies the prefix
// scan in resolveLocalVideo finds them via the hardlink file name.
func TestResolveLocalVideoPrefixAndRaw(t *testing.T) {
	accountDir := t.TempDir()
	md5v := "4ac955654bc62d785bfef4a524e95071"
	// YYYY-MM variant dir + _raw suffix file.
	ym := filepath.Join(accountDir, "msg", "video", "2026-08")
	if err := os.MkdirAll(ym, 0o755); err != nil {
		t.Fatal(err)
	}
	rawPath := filepath.Join(ym, md5v+"_raw.mp4")
	if err := os.WriteFile(rawPath, []byte("fake-mp4-bytes"), 0o644); err != nil {
		t.Fatal(err)
	}
	me := &mediaExportRuntime{}
	p, key := resolveLocalVideo(accountDir, []string{md5v}, "<videomsg md5=\""+md5v+"\"/>", "", me, nil, 1787135542)
	if p != rawPath {
		t.Fatalf("path = %q, want %q", p, rawPath)
	}
	if key != md5v {
		t.Fatalf("key = %q, want %q", key, md5v)
	}

	// YYYYMM variant dir + plain name.
	ym2 := filepath.Join(accountDir, "msg", "video", "202608")
	if err := os.MkdirAll(ym2, 0o755); err != nil {
		t.Fatal(err)
	}
	plain := filepath.Join(ym2, md5v+".mp4")
	if err := os.WriteFile(plain, []byte("other-video"), 0o644); err != nil {
		t.Fatal(err)
	}
	p2, key2 := resolveLocalVideo(accountDir, []string{md5v}, "", "", &mediaExportRuntime{}, nil, 1787135542)
	// Newest month dir first ("202608" > "2026-08"); both contain the md5, so
	// the first match wins — the exact-name month wins deterministically.
	if p2 != plain {
		t.Fatalf("path2 = %q, want %q", p2, plain)
	}
	if key2 != md5v {
		t.Fatalf("key2 = %q", key2)
	}
}

// TestResolveLocalVideoSizeFallback creates a video of the XML-declared
// length and verifies the size+md5 scan finds it when the name search misses.
func TestResolveLocalVideoSizeFallback(t *testing.T) {
	accountDir := t.TempDir()
	md5v := "5f2c3d4e5f2c3d4e5f2c3d4e5f2c3d4e"
	dir := filepath.Join(accountDir, "msg", "video", "202608")
	if err := os.MkdirAll(dir, 0o755); err != nil {
		t.Fatal(err)
	}
	// Name deliberately differs from every md5 candidate (server-rename case
	// CipherTalk's size-scan exists for).
	blob := make([]byte, 4096)
	for i := range blob {
		blob[i] = byte(i % 251)
	}
	file := filepath.Join(dir, "0f0e0d0c0f0e0d0c0f0e0d0c0f0e0d0c.mp4")
	if err := os.WriteFile(file, blob, 0o644); err != nil {
		t.Fatal(err)
	}
	raw := fmt.Sprintf(`<videomsg md5="%s" length="4096"/>`, md5v)
	me := &mediaExportRuntime{}
	p, key := resolveLocalVideo(accountDir, []string{md5v}, raw, "", me, nil, 1787135542)
	if p != file {
		t.Fatalf("size fallback path = %q, want %q", p, file)
	}
	if key != "0f0e0d0c0f0e0d0c0f0e0d0c0f0e0d0c" {
		t.Fatalf("size fallback key = %q", key)
	}
	// Index is cached on me across calls.
	p2, _ := resolveLocalVideo(accountDir, []string{md5v}, raw, "", me, nil, 1787135542)
	if p2 != file {
		t.Fatalf("cached size fallback path = %q", p2)
	}
}