package bridge

import (
	"bytes"
	"crypto/aes"
	"encoding/hex"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

// fakeMemory turns synthetic byte chunks into a memorySource (the Windows
// driver would read a real process; tests substitute these).
func fakeMemory(chunks ...[]byte) memorySource {
	return func(fn func(pid uint32, name string, addr uintptr, data []byte) error) (int64, []error) {
		var n int64
		for i, c := range chunks {
			n += int64(len(c))
			if err := fn(1, "test", uintptr(i), c); err != nil {
				return n, nil
			}
		}
		return n, nil
	}
}

// encryptedRawDB creates a SQLCipher database with a raw 32-byte page key via
// the sqlcipher CLI (skipped when not installed).
func encryptedRawDB(t *testing.T, key32 []byte) string {
	t.Helper()
	bin := ""
	if b, err := exec.LookPath("sqlcipher"); err == nil {
		bin = b
	} else if _, err := os.Stat("/opt/homebrew/opt/sqlcipher/bin/sqlcipher"); err == nil {
		bin = "/opt/homebrew/opt/sqlcipher/bin/sqlcipher"
	} else {
		t.Skip("sqlcipher CLI not installed")
	}
	path := filepath.Join(t.TempDir(), "enc.db")
	cmd := exec.Command(bin, path)
	cmd.Stdin = strings.NewReader("PRAGMA key = \"x'" + hex.EncodeToString(key32) + "'\";\nCREATE TABLE m(a INTEGER);\nINSERT INTO m VALUES(1),(2);\n")
	if out, err := cmd.CombinedOutput(); err != nil {
		t.Fatalf("sqlcipher create failed: %v\n%s", err, out)
	}
	return path
}

func TestDBKeyScanHexRun(t *testing.T) {
	key := bytes.Repeat([]byte{0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77, 0x88}, 4) // 32 bytes
	dbPath := encryptedRawDB(t, key)
	probe, err := dbKeyPage1Probe(dbPath)
	if err != nil {
		t.Fatal(err)
	}
	if probe(key) == "" || probe(bytes.Repeat([]byte{0xaa}, 32)) != "" {
		t.Fatal("probe sanity failed: right key must pass, wrong key must not")
	}
	hexKey := hex.EncodeToString(key)
	src := fakeMemory(
		[]byte{0x00, 0x01, 0xff, 0xfe, 0x00, 0x00},
		append([]byte("wcdb engine marker ... "), []byte(hexKey)...),
		[]byte{0x00, 0x01, 0x02},
	)
	k, label, hexCs, rawCs := scanDBKeyMemory(src, probe, "", func(string) {})
	if k != hexKey {
		t.Fatalf("found %q, want %q", k, hexKey)
	}
	if label != "mac" && label != "magic" {
		t.Fatalf("label = %q", label)
	}
	if hexCs == 0 {
		t.Fatal("no hex-window candidates were attempted")
	}
	// The raw sweep is exercised by TestDBKeyScanRawNearWxid (which relies on
	// it finding the key); here it races the hex path, so only hex is asserted.
	_ = rawCs
}

func TestDBKeyScanRawNearWxid(t *testing.T) {
	key := bytes.Repeat([]byte{0xab, 0xcd}, 16) // 32 bytes, not hex text anywhere
	dbPath := encryptedRawDB(t, key)
	probe, err := dbKeyPage1Probe(dbPath)
	if err != nil {
		t.Fatal(err)
	}
	// The key is stored as raw bytes inside a chunk that also carries the
	// account wxid text (a 4.1 global_config style layout).
	chunk := append([]byte("wxid_abcdef12345 token;json data;"), key...)
	chunk = append(chunk, []byte(";more text")...)
	src := fakeMemory([]byte{0xde, 0xad, 0x00}, chunk, []byte{0xff})
	k, label, hexCs, rawCs := scanDBKeyMemory(src, probe, "wxid_abcdef", func(string) {})
	if k != hex.EncodeToString(key) {
		t.Fatalf("found %q, want %q", k, hex.EncodeToString(key))
	}
	if label != "mac" && label != "magic" {
		t.Fatalf("label = %q", label)
	}
	if rawCs == 0 {
		t.Fatal("raw sweep never ran")
	}
	if hexCs != 0 {
		t.Fatalf("unexpected hex candidates (%d) for a raw-bytes layout", hexCs)
	}
}

func TestScanAesKeyMemoryFindsString(t *testing.T) {
	const keyStr = "Kk3UEhnR1r1DIkOXs6RTTZf8iFyNhLQX"
	probe := func(k16 []byte) string {
		if string(k16) == keyStr[:16] {
			return "test"
		}
		return ""
	}
	src := fakeMemory(
		[]byte{0x00, 0xff, 0xfe, 0x01, 0x00},
		append([]byte("imei cache: "), []byte(keyStr)...),
		[]byte(" tail \x01\x02"),
	)
	k, attempts, err := scanAesKeyMemory(src, probe, "", aesMaxKeyAttempts, time.Now().Add(120*time.Second), func(string) {})
	if err != nil {
		t.Fatal(err)
	}
	if k != keyStr {
		t.Fatalf("got %q, want %q", k, keyStr)
	}
	if attempts == 0 {
		t.Fatal("no attempts recorded")
	}
}

func TestScanAesKeyMemoryNotFound(t *testing.T) {
	probe := func(k16 []byte) string { return "" }
	src := fakeMemory(
		[]byte("some printable text with plenty of bytes to slide over"),
		[]byte{0x00, 0x01, 0x02},
	)
	k, attempts, err := scanAesKeyMemory(src, probe, "", aesMaxKeyAttempts, time.Now().Add(120*time.Second), func(string) {})
	if err != nil {
		t.Fatal(err)
	}
	if k != "" {
		t.Fatalf("expected no key, got %q", k)
	}
	if attempts == 0 {
		t.Fatal("expected the window walk to run")
	}
}

// TestScanAesKeyMemoryWxidAnchor verifies the anchored first pass: when the
// key lives in a chunk that also contains the account token, the scan finds it
// with a handful of probes instead of walking the whole (dense) address space.
// It also guards the O(chunk) memory property of the anchor probe.
func TestScanAesKeyMemoryWxidAnchor(t *testing.T) {
	const keyStr = "Kk3UEhnR1r1DIkOXs6RTTZf8iFyNhLQX"
	const wxid = "wxid_c03p833r8a4422_bfe8"
	probe := func(k16 []byte) string {
		if string(k16) == keyStr[:16] {
			return "test"
		}
		return ""
	}
	// Place the key run inside a chunk that also carries the wxid token (the
	// account keyring region), and surround it with dense junk.
	anchorChunk := append([]byte("keyring ... "+wxid+" ... "), []byte(keyStr)...)
	// A chunk containing the token but no key must not terminate the scan.
	decoy := append([]byte("other "+wxid+" config payload "), bytes.Repeat([]byte("xxxx"), 64)...)
	src := fakeMemory(
		bytes.Repeat([]byte("aaaaaaaaaaaaaaabbbbbbbbbbbbbbb"), 512), // junk pass 1
		decoy,
		anchorChunk,
		bytes.Repeat([]byte("ccccccccccccccdddddddddd"), 512), // junk pass 2
	)
	var msgs []string
	k, attempts, err := scanAesKeyMemory(src, probe, wxid, aesMaxKeyAttempts, time.Now().Add(120*time.Second), func(m string) { msgs = append(msgs, m) })
	if err != nil {
		t.Fatal(err)
	}
	if k != keyStr {
		t.Fatalf("got %q, want %q", k, keyStr)
	}
	if attempts > 2000 {
		t.Fatalf("anchored pass should find the key with few probes (a full sweep over the junk would burn tens of thousands), got %d", attempts)
	}
	foundAnchored := false
	for _, m := range msgs {
		if strings.Contains(m, "wxid-anchored") {
			foundAnchored = true
		}
	}
	if !foundAnchored {
		t.Fatalf("expected wxid-anchored confirmation log, got %v", msgs)
	}
}

func TestRunCollectorCrossChunk(t *testing.T) {
	col := &runCollector{}
	chunks := [][]byte{
		{0x00, 'a', 'b'},
		{'c', 'd', 'e', 'f', 0x00},
		{'P', 'A', 'Y'},
		{0x00},
	}
	var runs []asciiRun
	for i, c := range chunks {
		runs = append(runs, col.feed(uintptr(100+i), c)...)
	}
	runs = append(runs, col.flush()...)
	if len(runs) != 2 || string(runs[0].data) != "abcdef" || string(runs[1].data) != "PAY" {
		t.Fatalf("runs = %v", runs)
	}
	if runs[0].addr != uintptr(101) {
		t.Fatalf("run start address = %#x, want 0x65", runs[0].addr)
	}
}

// TestDBKeyScanWideHexRun verifies the UTF-16LE hex-string source: the key
// rendered as wide text ("3\x000\x00f\x00...") must be found even though
// the interleaved NULs hide it from the ASCII hex-span scanner.
func TestDBKeyScanWideHexRun(t *testing.T) {
	key := bytes.Repeat([]byte{0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77, 0x88}, 4)
	dbPath := encryptedRawDB(t, key)
	probe, err := dbKeyPage1Probe(dbPath)
	if err != nil {
		t.Fatal(err)
	}
	hexKey := hex.EncodeToString(key)
	var wide []byte
	for _, c := range []byte(hexKey) {
		wide = append(wide, c, 0x00)
	}
	chunk := append([]byte("cfg wide key: "), wide...)
	chunk = append(chunk, []byte(" tail")...)
	k, label, _, _ := scanDBKeyMemory(fakeMemory(chunk), probe, "", func(string) {})
	if k != hexKey {
		t.Fatalf("found %q, want %q", k, hexKey)
	}
	if label != "mac" && label != "magic" {
		t.Fatalf("label = %q", label)
	}
}

func TestWxidTokenFromAccountDir(t *testing.T) {	cases := map[string]string{
		"wxid_abcdef123456":           "wxid_abcdef1234", // wxid_ + first 10 chars (15 total)
		"wxid_short":                  "wxid_short",      // shorter than cap, kept whole
		`C:\xwechat_files\wxid_qrs_0`: "wxid_qrs_0",      // windows path; base shorter than cap
		"abc":                         "",                // too short to anchor
		"":                            "",
	}
	for in, want := range cases {
		if got := wxidTokenFromAccountDir(in); got != want {
			t.Errorf("wxidTokenFromAccountDir(%q) = %q, want %q", in, got, want)
		}
	}
}

// writeV2Template writes a real V2-format template file (sig + [aesSize][xorSize]
// + AES-ECB payload block + raw + XOR tail), the same layout the on-disk
// _t.dat files use, so verifyAesKey / templateCiphertext work against it.
func writeV2Template(t *testing.T, attachDir, name string, aesKey []byte, xorKey byte) {
	t.Helper()
	payload := append([]byte{0xff, 0xd8, 0xff, 0xe0, 0, 0x10, 'J', 'F', 'I', 'F', 0, 1, 1, 0, 0, 1},
		bytes.Repeat([]byte{0x42}, 80)...)
	payload = append(payload, 0xff, 0xd9) // JPEG EOI tail -> template XOR vote

	const aesSize, xorSize = 46, 12
	aesPlain := append(append([]byte{}, payload[:aesSize]...), 0x02, 0x02) // PKCS7 pad -> 48B
	aesEnc := aesECBEncrypt(t, aesKey, aesPlain)
	rawPart := payload[aesSize : len(payload)-xorSize]
	xorPart := make([]byte, xorSize)
	for i, b := range payload[len(payload)-xorSize:] {
		xorPart[i] = b ^ xorKey
	}
	rest := append(append(append([]byte{}, aesEnc...), rawPart...), xorPart...)

	sig := []byte{0x07, 0x08, 0x56, 0x32, 0x08, 0x07} // V2
	le := func(v int) []byte { return []byte{byte(v), byte(v >> 8), byte(v >> 16), byte(v >> 24)} }
	file := append(append(append(append(sig, le(aesSize)...), le(xorSize)...), 0x00), rest...)
	if err := os.WriteFile(filepath.Join(attachDir, name), file, 0o600); err != nil {
		t.Fatal(err)
	}
}

func aesECBEncrypt(t *testing.T, key, plain []byte) []byte {
	t.Helper()
	c, err := aes.NewCipher(key)
	if err != nil {
		t.Fatal(err)
	}
	if len(plain)%aes.BlockSize != 0 {
		t.Fatalf("plain not block aligned: %d", len(plain))
	}
	out := make([]byte, len(plain))
	for i := 0; i < len(plain); i += aes.BlockSize {
		c.Encrypt(out[i:], plain[i:i+aes.BlockSize])
	}
	return out
}

func TestImageAesKeyNativeScanWithRealTemplate(t *testing.T) {
	const keyStr = "Kk3UEhnR1r1DIkOXs6RTTZf8iFyNhLQX"
	const xorKey = 0x23 // like the user's install
	dir := t.TempDir()
	accountDir := filepath.Join(dir, "xwechat_files", "wxid_abc")
	attach := filepath.Join(accountDir, "msg", "attach", "sessionhash", "2025-06", "Img")
	if err := os.MkdirAll(attach, 0o755); err != nil {
		t.Fatal(err)
	}
	writeV2Template(t, attach, "ab12cd34ef56ab12cd34ef56ab12cd34_t.dat", []byte(keyStr[:16]), xorKey)

	files, err := findTemplateDatFiles(accountDir)
	if err != nil || len(files) != 1 {
		t.Fatalf("template scan: files=%v err=%v", files, err)
	}
	ct, cerr := templateCiphertext(files)
	if cerr != nil {
		t.Fatal(cerr)
	}
	if _, ok := xorKeyFromTemplateFiles(files); !ok {
		t.Fatal("XOR key not derivable from the template tail")
	}
	// The memory window walk + the real probe (block magic + full template
	// decrypt) must recover the exact key string.
	probe := func(k16 []byte) string {
		if aesKeyProbe(ct)(k16) == "" {
			return ""
		}
		if ok, how := verifyAesKey(k16, files); ok {
			return how
		}
		return ""
	}
	if probe([]byte(keyStr[:16])) == "" {
		t.Fatal("the true key must pass the probe")
	}
	if probe([]byte("WrongKey12345678")) != "" {
		t.Fatal("a wrong key must not pass the probe")
	}
	src := fakeMemory(
		bytes.Repeat([]byte{0x00}, 64),
		append([]byte("session store ... "), []byte(keyStr)...),
		[]byte{0x00, 0x01},
	)
	k, attempts, err := scanAesKeyMemory(src, probe, "", aesMaxKeyAttempts, time.Now().Add(120*time.Second), func(string) {})
	if err != nil {
		t.Fatal(err)
	}
	if k != keyStr {
		t.Fatalf("recovered %q, want %q", k, keyStr)
	}
	if attempts == 0 {
		t.Fatal("no attempts recorded")
	}
}

// TestScanAesKeyMemoryDensePrintableBounded guards the inline-probe design:
// WeChatAppEx heaps are dense with printable text (millions of short runs),
// which a collect-then-probe design buffered into an unbounded runs slice
// and OOM'd the bridge (observed: 18.5 GB growslice on a 2.4 GB walk). The
// inline design walks such input with O(chunk) memory and terminates via the
// attempt budget instead of accumulating anything.
func TestScanAesKeyMemoryDensePrintableBounded(t *testing.T) {
	chunk := bytes.Repeat([]byte("abcdefghijklmnopqrstuvwxyz012345"), 2048) // 64 KiB printable
	var chunks [][]byte
	for i := 0; i < 4096; i++ { // 256 MiB of pure printable text
		chunks = append(chunks, chunk)
	}
	src := fakeMemory(chunks...)
	start := time.Now()
	k, attempts, err := scanAesKeyMemory(src, func(k16 []byte) string { return "" }, "", aesMaxKeyAttempts, time.Now().Add(120*time.Second), func(string) {})
	if err != nil {
		t.Fatal(err)
	}
	if k != "" {
		t.Fatalf("unexpected key %q", k)
	}
	if attempts == 0 {
		t.Fatal("expected window probing to run on printable data")
	}
	if attempts > aesMaxKeyAttempts {
		t.Fatalf("attempts %d exceed probe budget %d", attempts, aesMaxKeyAttempts)
	}
	if d := time.Since(start); d > 20*time.Second {
		t.Fatalf("scan too slow: %v", d)
	}
}

// TestDbKeyScanRejectsWrongDBKey ensures verification is strict: a plausible
// 64-hex run for a different database must not be accepted.
func TestDbKeyScanRejectsWrongKey(t *testing.T) {
	key := bytes.Repeat([]byte{0x12, 0x34}, 16)
	dbPath := encryptedRawDB(t, key)
	other := bytes.Repeat([]byte{0x56, 0x78}, 16)
	probe, err := dbKeyPage1Probe(dbPath)
	if err != nil {
		t.Fatal(err)
	}
	src := fakeMemory(append([]byte("global_config ... "), []byte(hex.EncodeToString(other))...))
	k, label, _, _ := scanDBKeyMemory(src, probe, "", func(string) {})
	if k != "" || label != "" {
		t.Fatalf("wrong key accepted: %q (%q)", k, label)
	}
}

// TestKeyCacheRememberLookupInvalidate covers the verified-key cache: keys
// persist across cache reloads (the file), lookup hits only exact dbPaths,
// and invalidate removes a stale entry.
func TestKeyCacheRememberLookupInvalidate(t *testing.T) {
	oldPath := keyCachePathOverride
	oldCache := keyCache
	t.Cleanup(func() {
		keyCachePathOverride = oldPath
		keyCacheMu.Lock()
		keyCache = oldCache
		keyCacheMu.Unlock()
	})
	keyCachePathOverride = filepath.Join(t.TempDir(), "key_cache.json")
	keyCacheMu.Lock()
	keyCache = nil
	keyCacheMu.Unlock()

	c := loadKeyCache()
	const keyHex = "0917ebfc952c4cf99fe9641b496071cdd3a45688d37e497a8f8b72fbc42395c6"
	const dbA = `G:\xwechat_files\wxid_c03p833r8a4422_bfe8\db_storage\message\message_0.db`
	const dbB = `G:\xwechat_files\wxid_other_0000\db_storage\message\message_0.db`
	if k, ok := c.lookup(dbA); ok {
		t.Fatalf("fresh cache unexpectedly has %s", k)
	}
	c.remember(dbA, keyHex)
	if k, ok := c.lookup(dbA); !ok || k != keyHex {
		t.Fatalf("lookup after remember = %q, %v", k, ok)
	}
	if _, ok := c.lookup(dbB); ok {
		t.Fatal("cache leaked across dbPaths")
	}

	// A fresh load (simulating a bridge restart) must still see the key.
	keyCacheMu.Lock()
	keyCache = nil
	keyCacheMu.Unlock()
	c2 := loadKeyCache()
	if k, ok := c2.lookup(dbA); !ok || k != keyHex {
		t.Fatalf("reload lookup = %q, %v; want persisted %q", k, ok, keyHex)
	}
	c2.invalidate(dbA)
	if _, ok := c2.lookup(dbA); ok {
		t.Fatal("invalidate did not remove the entry")
	}

	// Garbage hex must never be cached.
	c2.remember(dbA, "nothex")
	if _, ok := c2.lookup(dbA); ok {
		t.Fatal("non-hex key was cached")
	}
}

// TestFindDBKeyInMemoryCacheHit simulates a repeat scan: after a key is
// remembered for a dbPath, the next call must return it without scanning
// memory. Verified with a real encrypted database and the real page-1 probe.
func TestFindDBKeyInMemoryCacheHit(t *testing.T) {
	oldPath := keyCachePathOverride
	oldCache := keyCache
	t.Cleanup(func() {
		keyCachePathOverride = oldPath
		keyCacheMu.Lock()
		keyCache = oldCache
		keyCacheMu.Unlock()
	})
	keyCachePathOverride = filepath.Join(t.TempDir(), "key_cache.json")
	keyCacheMu.Lock()
	keyCache = nil
	keyCacheMu.Unlock()

	key := bytes.Repeat([]byte{0x77, 0x88}, 16)
	dbPath := encryptedRawDB(t, key)
	loadKeyCache().remember(dbPath, hex.EncodeToString(key))
	k, label, attempts, err := findDBKeyInMemory(dbPath, "", func(string) {})
	if err != nil {
		t.Fatal(err)
	}
	if k != hex.EncodeToString(key) {
		t.Fatalf("cache hit returned %q, want %q", k, hex.EncodeToString(key))
	}
	if !strings.Contains(label, "cache") {
		t.Fatalf("label = %q, want a cache label", label)
	}
	if attempts != 1 {
		t.Fatalf("attempts = %d, want 1 (cache verification only)", attempts)
	}

	// A wrong cached key must fall through and not be returned. The fall
	// through re-enters the full scan, which needs the Windows memory driver;
	// off-Windows the platform guard returns an error instead — either way a
	// wrong key is never accepted.
	loadKeyCache().remember(dbPath, hex.EncodeToString(bytes.Repeat([]byte{0x99}, 32)))
	k, _, _, err = findDBKeyInMemory(dbPath, "", func(string) {})
	if k != "" {
		t.Fatalf("wrong cached key accepted: %q", k)
	}
	if err != nil && !strings.Contains(err.Error(), "GOOS=windows") {
		t.Fatalf("unexpected error: %v", err)
	}
}
