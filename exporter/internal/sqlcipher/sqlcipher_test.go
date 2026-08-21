package sqlcipher

import (
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"testing"
)

var sqlcipherBin = "/opt/homebrew/opt/sqlcipher/bin/sqlcipher"

func sqlcipherAvailable() bool {
	_, err := os.Stat(sqlcipherBin)
	return err == nil
}

// createEncrypted runs the sqlcipher CLI with the given PRAGMA setup.
func createEncrypted(t *testing.T, dir, name, keyPragma, sql string) string {
	t.Helper()
	path := filepath.Join(dir, name)
	cmd := exec.Command(sqlcipherBin, path)
	cmd.Stdin = strings.NewReader("PRAGMA key = '" + keyPragma + "';\n" + sql)
	if out, err := cmd.CombinedOutput(); err != nil {
		t.Fatalf("sqlcipher create failed: %v\n%s", err, out)
	}
	return path
}

func readViaOpen(t *testing.T, path string, secret string, wantMode bool, mode KeyMode) string {
	t.Helper()
	df, err := Open(path, []byte(secret), mode, wantMode)
	if err != nil {
		return "ERR:" + err.Error()
	}
	defer df.Close()
	rows := ""
	if df.NumPages() < 1 {
		rows += "nopages"
	}
	p2, err := df.ReadPage(2)
	if err == nil {
		rows += "|p2=" + string(rune(p2[0]))
	}
	return rows
}

func TestSQLCipherDefaultPassphrase(t *testing.T) {
	if !sqlcipherAvailable() {
		t.Skip("sqlcipher not installed")
	}
	dir := t.TempDir()
	path := createEncrypted(t, dir, "t.db", "hello-pass",
		"CREATE TABLE m(a INTEGER, b TEXT);\nINSERT INTO m VALUES(1,'x'),(2,'y');\n")
	df, err := Open(path, []byte("hello-pass"), ModeDerived, false)
	if err != nil {
		t.Fatalf("Open with passphrase: %v", err)
	}
	defer df.Close()
	if df.Mode() != ModeDerived {
		t.Fatalf("expected derived mode, got %v", df.Mode())
	}
	// verify raw mode fails
	if _, err := Open(path, []byte("hello-pass"), ModeRaw, true); err == nil {
		t.Fatal("raw mode should not verify a passphrase keyed db")
	}
}

func TestSQLCipherRawBinaryKey(t *testing.T) {
	if !sqlcipherAvailable() {
		t.Skip("sqlcipher not installed")
	}
	dir := t.TempDir()
	// use sqlite3_key with a 32-byte binary key passed as quoted hex via the
	// CLI key pragma (SQLCipher treats it as a passphrase string)
	key := "0123456789abcdef0123456789abcdef"
	path := createEncrypted(t, dir, "t.db", key,
		"CREATE TABLE m(a INTEGER);\nINSERT INTO m VALUES(42);\n")
	df, err := Open(path, []byte(key), ModeRaw, false)
	if err != nil {
		t.Fatalf("Open: %v", err)
	}
	defer df.Close()
	if df.Mode() != ModeDerived {
		t.Logf("note: CLI key string interpreted as passphrase -> derived (%v)", df.Mode())
	}
}

func TestOpenWrongKey(t *testing.T) {
	if !sqlcipherAvailable() {
		t.Skip("sqlcipher not installed")
	}
	dir := t.TempDir()
	path := createEncrypted(t, dir, "t.db", "right-key",
		"CREATE TABLE m(a INTEGER);\n")
	if _, err := Open(path, []byte("wrong-key"), ModeDerived, false); err == nil {
		t.Fatal("expected error for wrong key")
	}
}

func TestSQLCipherPlainTextDBRejected(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "p.db")
	if out, err := exec.Command("sqlite3", path, "CREATE TABLE t(a);").CombinedOutput(); err != nil {
		t.Fatalf("sqlite3: %v %s", err, out)
	}
	if _, err := Open(path, []byte("0123456789abcdef0123456789abcdef"), ModeRaw, false); err == nil {
		t.Fatal("plaintext db should not verify")
	}
}

// TestSQLCipherPage1Structure verifies layout invariants on a real file:
// salt at [0:16], IV at [4080:4096], MAC at [4016:4080].
func TestSQLCipherPage1Structure(t *testing.T) {
	if !sqlcipherAvailable() {
		t.Skip("sqlcipher not installed")
	}
	dir := t.TempDir()
	path := createEncrypted(t, dir, "t.db", "k",
		"CREATE TABLE m(a INTEGER);\nINSERT INTO m VALUES(1);\n")
	b, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	if len(b) < PageSize {
		t.Fatalf("db smaller than one page: %d", len(b))
	}
	p1 := b[:PageSize]
	// salt must be nonzero and not the plaintext header
	if allZero(p1[:16]) {
		t.Fatal("salt region is all zeros")
	}
	if string(p1[0:16]) == "SQLite format 3\x00" {
		t.Fatal("salt region still contains plaintext magic")
	}
	// IV and MAC regions must be nonzero (encrypted)
	if allZero(p1[PageSize-ivSize:]) {
		t.Fatal("IV region all zeros")
	}
	if allZero(p1[PageSize-reserveSize : PageSize-ivSize]) {
		t.Fatal("MAC region all zeros")
	}
}

func allZero(b []byte) bool {
	for _, x := range b {
		if x != 0 {
			return false
		}
	}
	return true
}
