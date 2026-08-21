package bridge

import (
	"os"
	"path/filepath"
	"testing"
)

func TestWalWarningIsNonBlocking(t *testing.T) {
	// Since the bridge reads -wal rows through the WAL overlay, live WAL files
	// are handled and are surfaced as log info lines, not user-facing
	// warnings. walWarning must therefore never emit a warning regardless of
	// -wal siblings.
	dir := t.TempDir()
	dbPath := filepath.Join(dir, "msg_0.db")
	if err := os.WriteFile(dbPath+"-wal", []byte("frame data"), 0o644); err != nil {
		t.Fatal(err)
	}
	hl := filepath.Join(dir, "db_storage", "hardlink")
	if err := os.MkdirAll(hl, 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(hl, "hardlink.db-wal"), []byte("x"), 0o644); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(hl, "hardlink.db"), []byte("y"), 0o644); err != nil {
		t.Fatal(err)
	}
	if w := walWarning(dbPath, dir); len(w) != 0 {
		t.Fatalf("walWarning must return no warnings (overlay handles WAL), got %v", w)
	}
}

func TestWalSize(t *testing.T) {
	dir := t.TempDir()
	db := filepath.Join(dir, "x.db")
	if walSize(db) != 0 {
		t.Fatal("absent wal should be 0")
	}
	if err := os.WriteFile(db+"-wal", []byte("abc"), 0o644); err != nil {
		t.Fatal(err)
	}
	if walSize(db) != 3 {
		t.Fatalf("wal size = %d, want 3", walSize(db))
	}
}

// TestFileNameFor: the hardlink index maps a message md5 to the on-disk .dat
// file name; fileNameFor must return it even when the predicted attach path
// would miss, so the legacy fallback can search the full index by that name.
func TestFileNameFor(t *testing.T) {
	idx := &hardlinkIndex{byMD5: map[string]hardlinkEntry{}}
	idx.byMD5["285c8a16deadbeef285c8a16deadbeef"] = hardlinkEntry{
		dir1:     7,
		dir2:     42,
		fileName: "33862c726ffe8009d1e3dbb793379236.dat",
	}
	if got := idx.fileNameFor("285c8a16DEADBEEF285c8a16DEADBEEF"); got != "33862c726ffe8009d1e3dbb793379236.dat" {
		t.Fatalf("fileNameFor = %q, want the known .dat name", got)
	}
	if got := idx.fileNameFor("00000000000000000000000000000000"); got != "" {
		t.Fatalf("fileNameFor absent md5 = %q, want ''", got)
	}
	if idx.fileNameFor("") != "" {
		t.Fatal("fileNameFor('') should be empty")
	}
	var nilIdx *hardlinkIndex
	if nilIdx.fileNameFor("x") != "" {
		t.Fatal("nil index fileNameFor should be empty")
	}
}