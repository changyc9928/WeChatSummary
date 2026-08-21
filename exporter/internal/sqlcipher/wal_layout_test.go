package sqlcipher

import (
	"encoding/binary"
	"io"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

// TestWalFrameLayoutReverseEngineer creates a WAL-mode encrypted DB and
// inspects the frame structure while the writer connection is still open.
// This test documents the SQLCipher WAL layout discovered empirically.
func TestWalFrameLayoutReverseEngineer(t *testing.T) {
	if !sqlcipherAvailable() {
		t.Skip("sqlcipher not installed")
	}
	dir := t.TempDir()
	dbPath := filepath.Join(dir, "w.db")
	cmd := exec.Command(sqlcipherBin, dbPath)
	stdin, err := cmd.StdinPipe()
	if err != nil {
		t.Fatal(err)
	}
	if err := cmd.Start(); err != nil {
		t.Fatal(err)
	}
	defer func() {
		stdin.Close()
		cmd.Process.Kill()
		cmd.Wait()
	}()

	feed := func(sql string) {
		if _, err := io.WriteString(stdin, sql); err != nil {
			t.Fatal(err)
		}
	}
	feed("PRAGMA key='pass';\n")
	feed("PRAGMA journal_mode=WAL;\n")
	feed("CREATE TABLE m(a INTEGER, b TEXT);\n")
	feed("INSERT INTO m VALUES(1,'one'),(2,'two');\n")
	feed("INSERT INTO m VALUES(3,'three');\n")
	time.Sleep(500 * time.Millisecond)

	walPath := dbPath + "-wal"
	wal, err := os.ReadFile(walPath)
	if err != nil {
		t.Fatalf("read wal: %v", err)
	}
	t.Logf("wal size: %d bytes", len(wal))
	if len(wal) < 32+24 {
		t.Fatalf("too small")
	}

	magic := binary.BigEndian.Uint32(wal[0:4])
	version := binary.BigEndian.Uint32(wal[4:8])
	pageSize := binary.BigEndian.Uint32(wal[8:12])
	t.Logf("magic=0x%08x version=%d pageSize=%d", magic, version, pageSize)
	// 0x377f0682/683: little-endian file; 0x82067f37/0x83067f37: big-endian
	le := magic == 0x377f0682 || magic == 0x377f0683

	// frame 0 header at 32
	var frame0Pgno uint32
	if le {
		frame0Pgno = binary.LittleEndian.Uint32(wal[32:36])
	} else {
		frame0Pgno = binary.BigEndian.Uint32(wal[32:36])
	}
	t.Logf("frame0 pgno=%d", frame0Pgno)
	frameData := wal[56 : 56+int(pageSize)]

	// Check whether the frame page data itself is encrypted: if the db and the
	// frame share the same salt, frame page 1 starts with the same 16 bytes.
	dbBytes, _ := os.ReadFile(dbPath)
	dbSalt := dbBytes[:16]
	framePrefix := frameData[:16]
	t.Logf("dbSalt=%x", dbSalt)
	t.Logf("frame  page-1 salt region=%x", framePrefix)
	sameSalt := string(dbSalt) == string(framePrefix)
	t.Logf("frame salt matches db salt: %v", sameSalt)

	iv := frameData[len(frameData)-16:]
	t.Logf("frame0 last16=%x", iv)

	// Is frame data plaintext-like anywhere? search for magic
	if strings.Contains(string(frameData), "SQLite format 3") {
		t.Log("frame contains plaintext SQLite magic")
	}
	// dump first bytes
	t.Logf("frame0 prefix bytes=%x", frameData[:32])
	t.Logf("frame0 tail bytes=%x", frameData[len(frameData)-80:])
}
