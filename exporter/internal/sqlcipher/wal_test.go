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

	"wechatsummary/exporter/internal/sqlite"
)

// TestWalOverlayEndToEnd verifies that rows committed into the WAL (and not
// yet checkpointed into the main file) are visible through OpenWal.
func TestWalOverlayEndToEnd(t *testing.T) {
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
	kill := func() {
		stdin.Close()
		cmd.Process.Kill()
		cmd.Wait()
	}
	feed := func(s string) {
		if _, err := io.WriteString(stdin, s); err != nil {
			kill()
			t.Fatal(err)
		}
	}
	feed("PRAGMA key='pass';\nPRAGMA journal_mode=WAL;\n")
	feed("CREATE TABLE m(a INTEGER, b TEXT);\n")
	feed("INSERT INTO m VALUES(1,'one'),(2,'two');\n")
	feed("PRAGMA wal_checkpoint(PASSIVE);\n")
	feed("INSERT INTO m VALUES(3,'three'),(4,'four');\n")
	time.Sleep(2000 * time.Millisecond)

	// checkpoint happened: rows 1-2 in main file, rows 3-4 only in wal
	walInfo, _ := os.ReadFile(dbPath + "-wal")
	t.Logf("wal size=%d nframes=%d", len(walInfo), (len(walInfo)-32)/(24+PageSize))
	ws, err := OpenWal(dbPath, []byte("pass"), ModeDerived, false)
	if err != nil {
		kill()
		t.Fatal(err)
	}
	defer ws.Close()
	t.Logf("walerr: %v", ws.WalError())
	t.Logf("frames: %d npgs: %d", len(ws.frames), ws.NumPages())

	db, err := sqlite.Open(ws)
	if err != nil {
		kill()
		t.Fatalf("sqlite open over wal: %v", err)
	}
	m, err := db.Table("m")
	if err != nil {
		kill()
		t.Fatal(err)
	}
	rows, err := m.Scan(0)
	if err != nil {
		kill()
		t.Fatal(err)
	}
	if len(rows) != 4 {
		kill()
		t.Fatalf("want 4 rows (2 from db, 2 from wal), got %d: %+v", len(rows), rows)
	}
	if rows[2][0].Int != 3 || rows[3][0].Int != 4 {
		kill()
		t.Fatalf("wal rows wrong: %+v", rows)
	}
	t.Logf("wal overlay ok: %d rows", len(rows))
	t.Logf("npgs=%d frames=%d", ws.NumPages(), len(ws.frames))
	t.Logf("wal error: %v", ws.WalError())
	kill()
}

// TestWalOverlayGenerationBehind reproduces the WeChat 4.1 WAL shape: the
// header advanced one generation past every frame still on disk (checkpoint
// restart), so strict salt=={a0,a1} matches nothing, but the frames hold the
// newest un-checkpointed rows and their page HMAC verifies with the same DB
// keys. Pass 2 must accept them via the generation-counter rule.
func TestWalOverlayGenerationBehind(t *testing.T) {
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
	kill := func() {
		stdin.Close()
		cmd.Process.Kill()
		cmd.Wait()
	}
	feed := func(s string) {
		if _, err := io.WriteString(stdin, s); err != nil {
			kill()
			t.Fatal(err)
		}
	}
	feed("PRAGMA key='pass';\nPRAGMA journal_mode=WAL;\n")
	feed("CREATE TABLE m(a INTEGER, b TEXT);\n")
	feed("INSERT INTO m VALUES(1,'one'),(2,'two');\n")
	feed("PRAGMA wal_checkpoint(PASSIVE);\n")
	feed("INSERT INTO m VALUES(3,'three'),(4,'four');\n")
	time.Sleep(2000 * time.Millisecond)

	walPath := dbPath + "-wal"
	raw, err := os.ReadFile(walPath)
	if err != nil {
		kill()
		t.Fatal(err)
	}
	frameSz := 24 + PageSize
	if len(raw) < 32+frameSz {
		kill()
		t.Fatalf("wal too small: %d bytes", len(raw))
	}
	// Keep only the header + the first frame: after the PASSIVE checkpoint the
	// WAL restarted with a new salt and wrote the newest page (4 rows) at the
	// top of the frame region; the frames below are the stale pre-checkpoint
	// cycle and must be dropped so the file is single-generation, exactly like
	// the WeChat 4.1 shape (header one generation ahead of every frame).
	raw = append([]byte(nil), raw[:32+frameSz]...)
	// Advance the header salts one generation: frame high-32 == a0 high-32 - 1.
	frameSalt := raw[32+8 : 32+16]
	hdrA0 := raw[16:24]
	hdrA1 := raw[24:32]
	binary.BigEndian.PutUint32(hdrA0[0:4], binary.BigEndian.Uint32(frameSalt[0:4])+1)
	binary.BigEndian.PutUint32(hdrA1[0:4], binary.BigEndian.Uint32(frameSalt[0:4])+1)
	if err := os.WriteFile(walPath, raw, 0o644); err != nil {
		kill()
		t.Fatal(err)
	}

	ws, err := OpenWal(dbPath, []byte("pass"), ModeDerived, false)
	if err != nil {
		kill()
		t.Fatal(err)
	}
	defer ws.Close()
	t.Logf("wal diag: %s", ws.Diag())
	db, err := sqlite.Open(ws)
	if err != nil {
		kill()
		t.Fatalf("sqlite open over wal: %v", err)
	}
	m, err := db.Table("m")
	if err != nil {
		kill()
		t.Fatal(err)
	}
	rows, err := m.Scan(0)
	if err != nil {
		kill()
		t.Fatal(err)
	}
	if len(rows) != 4 {
		kill()
		t.Fatalf("want 4 rows via generation-behind pass2, got %d: %+v (diag: %s)", len(rows), rows, ws.Diag())
	}
	if rows[2][0].Int != 3 || rows[3][0].Int != 4 {
		kill()
		t.Fatalf("wal rows wrong: %+v", rows)
	}
	t.Logf("generation-behind overlay ok: %d rows", len(rows))
	kill()
}

// TestOpenWalWithoutWalDegrades: no -wal file -> plain main file path works.
func TestOpenWalWithoutWalDegrades(t *testing.T) {
	if !sqlcipherAvailable() {
		t.Skip("sqlcipher not installed")
	}
	dir := t.TempDir()
	path := filepath.Join(dir, "t.db")
	cmd := exec.Command(sqlcipherBin, path)
	cmd.Stdin = strings.NewReader("PRAGMA key='pass';\nCREATE TABLE m(a INTEGER);\nINSERT INTO m VALUES(7);\n")
	if out, err := cmd.CombinedOutput(); err != nil {
		t.Fatalf("create: %v %s", err, out)
	}
	ws, err := OpenWal(path, []byte("pass"), ModeDerived, false)
	if err != nil {
		t.Fatal(err)
	}
	defer ws.Close()
	db, err := sqlite.Open(ws)
	if err != nil {
		t.Fatal(err)
	}
	m, _ := db.Table("m")
	rows, err := m.Scan(0)
	if err != nil || len(rows) != 1 || rows[0][0].Int != 7 {
		t.Fatalf("rows: %v err: %v", rows, err)
	}
	if ws.WalError() != nil {
		t.Logf("wal error: %v", ws.WalError())
	}
}

func mustReader(s string) io.Reader { return stringsReader(s) }

type stringsReader string

func (s stringsReader) Read(p []byte) (int, error) { return copy(p, s), nil }

// TestWalPage1InFrames: a schema change after the checkpoint rewrites page 1
// into the WAL; the overlay must serve it (page-1 layout: salt + MAC over
// [16:4032]) and the schema reader must see the new schema.
func TestWalPage1InFrames(t *testing.T) {
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
	kill := func() {
		stdin.Close()
		cmd.Process.Kill()
		cmd.Wait()
	}
	feed := func(s string) {
		if _, err := io.WriteString(stdin, s); err != nil {
			kill()
			t.Fatal(err)
		}
	}
	feed("PRAGMA key='pass';\nPRAGMA journal_mode=WAL;\n")
	feed("CREATE TABLE m(a INTEGER, b TEXT);\n")
	feed("INSERT INTO m VALUES(1,'one');\n")
	feed("PRAGMA wal_checkpoint(PASSIVE);\n")
	feed("ALTER TABLE m ADD COLUMN c TEXT;\n")
	time.Sleep(1500 * time.Millisecond)

	ws, err := OpenWal(dbPath, []byte("pass"), ModeDerived, false)
	if err != nil {
		kill()
		t.Fatal(err)
	}
	defer ws.Close()

	db, err := sqlite.Open(ws)
	if err != nil {
		kill()
		t.Fatal(err)
	}
	// The ALTERed schema lives on page 1, which was rewritten into the WAL;
	// db.Tables() must reflect the new column even though the data rows on
	// page 2 still carry the old 2-column records.
	for _, ti := range db.Tables() {
		if ti.Name == "m" {
			if !strings.Contains(ti.SQL, "c TEXT") {
				kill()
				t.Fatalf("schema from wal page-1 frame not applied: %s", ti.SQL)
			}
			kill()
			return
		}
	}
	kill()
	t.Fatal("table m not found in schema")
}
