package sqlite_test

import (
	"encoding/hex"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"testing"

	"wechatsummary/exporter/internal/sqlcipher"
	"wechatsummary/exporter/internal/sqlite"
)

func sqlcipherBin() string {
	if b, err := exec.LookPath("sqlcipher"); err == nil {
		return b
	}
	if _, err := os.Stat("/opt/homebrew/opt/sqlcipher/bin/sqlcipher"); err == nil {
		return "/opt/homebrew/opt/sqlcipher/bin/sqlcipher"
	}
	return ""
}

// makeWeChatStyleDB creates a SQLCipher database matching WeChat 4.x settings
// (SHA512, 256000 iterations, 4096-byte pages, raw 32-byte key) with a
// Msg_<md5>-style table and returns its path. stmts are extra SQL statements
// executed after table creation.
func makeWeChatStyleDB(t *testing.T, key string, stmts ...string) string {
	t.Helper()
	bin := sqlcipherBin()
	if bin == "" {
		t.Skip("sqlcipher CLI not installed")
	}
	path := filepath.Join(t.TempDir(), "wc.db")
	var sql []string
	sql = append(sql, `PRAGMA key = "x'`+key+`'";`)
	sql = append(sql, "PRAGMA cipher_page_size = 4096;")
	sql = append(sql, "PRAGMA cipher_hmac_algorithm = HMAC_SHA512;")
	sql = append(sql, "PRAGMA cipher_kdf_algorithm = PBKDF2_HMAC_SHA512;")
	sql = append(sql, "PRAGMA kdf_iter = 256000;")
	sql = append(sql, "PRAGMA cipher_use_hmac = ON;")
	sql = append(sql, "CREATE TABLE Msg_abc(content BLOB);")
	sql = append(sql, stmts...)
	cmd := exec.Command(bin, path)
	cmd.Stdin = strings.NewReader(strings.Join(sql, "\n"))
	if out, err := cmd.CombinedOutput(); err != nil {
		t.Fatalf("sqlcipher create failed: %v\n%s", err, out)
	}
	return path
}

func openWeChatDB(t *testing.T, path, key string) *sqlite.DB {
	t.Helper()
	keyBytes, err := hex.DecodeString(key)
	if err != nil {
		t.Fatal(err)
	}
	f, err := sqlcipher.Open(path, keyBytes, sqlcipher.ModeRaw, false)
	if err != nil {
		t.Fatalf("sqlcipher.Open: %v", err)
	}
	t.Cleanup(func() { f.Close() })
	db, err := sqlite.Open(f)
	if err != nil {
		t.Fatalf("sqlite.Open: %v", err)
	}
	return db
}

// TestOverflowZeroTail is the regression for the 422 export failure on
// WeChat 4.1.12.26 ("sqlite: payload truncated (4012 bytes missing)").
// Records whose payload ends in 0x00 bytes span overflow pages; the sqlcipher
// layer used to trim trailing zeros from decrypted pages, eating the record's
// tail. The plaintext page is exactly PageSize-reserveSize bytes; zeros at
// the end are free space inside the b-tree page and must be preserved.
func TestOverflowZeroTail(t *testing.T) {
	const key = "1122334455667788112233445566778811223344556677881122334455667788"
	// content = 1000 'x' + 512 trailing zeros: the tail must survive
	// overflow reads.
	path := makeWeChatStyleDB(t, key,
		"INSERT INTO Msg_abc VALUES(x'"+strings.Repeat("78", 1000)+strings.Repeat("00", 512)+"');",
	)
	db := openWeChatDB(t, path, key)
	tbl, err := db.Table("Msg_abc")
	if err != nil {
		t.Fatal(err)
	}
	rows, err := tbl.Scan(0)
	if err != nil {
		t.Fatalf("Scan: %v", err)
	}
	if len(rows) != 1 {
		t.Fatalf("rows = %d, want 1", len(rows))
	}
	if got, want := len(rows[0][0].Blob), 1512; got != want {
		t.Fatalf("blob length = %d, want %d", got, want)
	}
	if rows[0][0].Blob[1000] != 0 || rows[0][0].Blob[1511] != 0 {
		t.Fatalf("zero tail not preserved: last bytes = % x", rows[0][0].Blob[1000:])
	}
}

// TestOverflowSizeSweep scans records of many sizes (local-only and
// multi-chunk overflow) to catch usable-page-size drift.
func TestOverflowSizeSweep(t *testing.T) {
	const key = "1122334455667788112233445566778811223344556677881122334455667788"
	sizes := []int{1000, 2000, 3000, 3800, 3900, 3950, 3980, 4000, 4012, 4020,
		4050, 4060, 4061, 4062, 4080, 4100, 4200, 4400, 4500, 4600, 5000,
		6000, 8000, 10000, 16000}
	var stmts []string
	for _, n := range sizes {
		stmts = append(stmts, "INSERT INTO Msg_abc VALUES(x'"+strings.Repeat("78", n)+"');")
	}
	db := openWeChatDB(t, makeWeChatStyleDB(t, key, stmts...), key)
	tbl, err := db.Table("Msg_abc")
	if err != nil {
		t.Fatal(err)
	}
	rows, err := tbl.Scan(0)
	if err != nil {
		t.Fatalf("Scan: %v", err)
	}
	if len(rows) != len(sizes) {
		t.Fatalf("rows = %d, want %d", len(rows), len(sizes))
	}
	for i, n := range sizes {
		if got := len(rows[i][0].Blob); got != n {
			t.Fatalf("row %d: blob length = %d, want %d", i, got, n)
		}
	}
}
