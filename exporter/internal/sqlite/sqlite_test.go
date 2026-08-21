package sqlite

import (
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"testing"
)

// makeDb builds a test database with /usr/bin/sqlite3 (or sqlite3 on PATH).
func makeDb(t *testing.T, sql string) string {
	t.Helper()
	dir := t.TempDir()
	path := filepath.Join(dir, "t.db")
	cmd := exec.Command("sqlite3", path)
	cmd.Stdin = strings.NewReader(sql)
	if out, err := cmd.CombinedOutput(); err != nil {
		t.Fatalf("sqlite3 create failed: %v\n%s", err, out)
	}
	return path
}

func openPlain(t *testing.T, path string) *DB {
	t.Helper()
	src, err := NewFileSource(path)
	if err != nil {
		t.Fatalf("NewFileSource: %v", err)
	}
	db, err := Open(src)
	if err != nil {
		t.Fatalf("Open: %v", err)
	}
	return db
}

func TestSchemaParse(t *testing.T) {
	p := makeDb(t, `
CREATE TABLE t1(a INTEGER PRIMARY KEY, b TEXT, c REAL);
CREATE TABLE t2(x BLOB);
INSERT INTO t1 VALUES (1,'hello',1.5),(2,'world',-2.25);
`)
	db := openPlain(t, p)
	var names []string
	for _, ti := range db.Tables() {
		names = append(names, ti.Name)
	}
	got := strings.Join(names, ",")
	if !strings.Contains(got, "t1") || !strings.Contains(got, "t2") {
		t.Fatalf("tables missing: %v", names)
	}
	if len(names) != 2 {
		// modern sqlite omits the sqlite_master self-row on disk
		t.Fatalf("expected exactly t1,t2, got: %v", names)
	}

	t1, err := db.Table("t1")
	if err != nil {
		t.Fatal(err)
	}
	rows, err := t1.Scan(0)
	if err != nil {
		t.Fatal(err)
	}
	if len(rows) != 2 {
		t.Fatalf("want 2 rows, got %d", len(rows))
	}
	r := rows[0]
	// INTEGER PRIMARY KEY aliases rowid: stored as NULL in the record
	if r[0].Kind != VNull || r[1].Text != "hello" || r[2].Float != 1.5 {
		t.Fatalf("bad row0: %+v", r)
	}
	r = rows[1]
	if r[0].Kind != VNull || r[1].Text != "world" || r[2].Float != -2.25 {
		t.Fatalf("bad row1: %+v", r)
	}
}

func TestTypesAndEdgeCases(t *testing.T) {
	big := strings.Repeat("A", 9000) // forces overflow
	p := makeDb(t, fmt.Sprintf(`
CREATE TABLE e(
  i8 INTEGER, u64 INTEGER, neg INTEGER, f REAL, txt TEXT,
  bl BLOB, n NULL, big TEXT, one INTEGER);
INSERT INTO e VALUES(-128, 9223372036854775807, -42, 3.14159,
  'a''quote', X'0102FE', NULL, '%s', 1);
`, big))
	db := openPlain(t, p)
	e, _ := db.Table("e")
	rows, err := e.Scan(0)
	if err != nil {
		t.Fatal(err)
	}
	if len(rows) != 1 {
		t.Fatalf("want 1 row got %d", len(rows))
	}
	r := rows[0]
	checks := []struct {
		kind ValueKind
		want int64
	}{
		{VInt, -128},
		{VInt, 9223372036854775807},
		{VInt, -42},
	}
	for i, c := range checks {
		if r[i].Kind != c.kind || r[i].Int != c.want {
			t.Fatalf("col %d: got %+v want %d", i, r[i], c.want)
		}
	}
	if r[3].Kind != VFloat || r[3].Float != 3.14159 {
		t.Fatalf("float col: %+v", r[3])
	}
	if r[4].Text != "a'quote" {
		t.Fatalf("text col: %+v", r[4])
	}
	if len(r[5].Blob) != 3 || r[5].Blob[0] != 1 || r[5].Blob[2] != 0xFE {
		t.Fatalf("blob col: %+v", r[5])
	}
	if r[6].Kind != VNull {
		t.Fatalf("null col: %+v", r[6])
	}
	if r[7].Text != big {
		t.Fatalf("big text mismatch: got %d bytes want %d", len(r[7].Text), len(big))
	}
	if r[8].Kind != VInt || r[8].Int != 1 {
		t.Fatalf("one col: %+v", r[8])
	}
}

func TestManyRowsAndOrdering(t *testing.T) {
	var sb strings.Builder
	sb.WriteString("CREATE TABLE m(i INTEGER);\n")
	for i := 1; i <= 5000; i++ {
		fmt.Fprintf(&sb, "INSERT INTO m VALUES(%d);\n", i)
	}
	p := makeDb(t, sb.String())
	db := openPlain(t, p)
	m, _ := db.Table("m")
	rows, err := m.Scan(0)
	if err != nil {
		t.Fatal(err)
	}
	if len(rows) != 5000 {
		t.Fatalf("want 5000 rows got %d", len(rows))
	}
	if rows[0][0].Int != 1 || rows[4999][0].Int != 5000 {
		t.Fatalf("order wrong: first=%d last=%d", rows[0][0].Int, rows[4999][0].Int)
	}
}

func TestEmptyTable(t *testing.T) {
	p := makeDb(t, "CREATE TABLE z(a INTEGER);")
	db := openPlain(t, p)
	z, _ := db.Table("z")
	rows, err := z.Scan(0)
	if err != nil {
		t.Fatal(err)
	}
	if len(rows) != 0 {
		t.Fatalf("want 0 rows got %d", len(rows))
	}
}

func TestRowidIndexScan(t *testing.T) {
	// exercises leaf-index page walking via an index on a larger table
	p := makeDb(t, `
CREATE TABLE idx(a TEXT, b INTEGER);
CREATE INDEX idx_ab ON idx(a, b);
INSERT INTO idx VALUES ('x', 1), ('y', 2), ('z', 3);
`)
	db := openPlain(t, p)
	// the index b-tree must be reachable via sqlite_schema
	var found bool
	for _, ti := range db.Tables() {
		if ti.Name == "idx_ab" {
			found = true
			ix, err := db.ReadTableRoot(ti.RootPage)
			if err != nil {
				t.Fatal(err)
			}
			rows, err := ix.Scan(0)
			if err != nil {
				t.Fatal(err)
			}
			if len(rows) != 3 {
				t.Fatalf("index rows: %d", len(rows))
			}
			if rows[0][0].Text != "x" || rows[2][1].Int != 3 {
				t.Fatalf("index content: %+v", rows)
			}
		}
	}
	if !found {
		t.Fatal("index not found in schema")
	}
}

// NewFileSource is a test helper; production uses sqlcipher.File.
type fileSource struct {
	data []byte
}

func NewFileSource(path string) (*fileSource, error) {
	b, err := os.ReadFile(path)
	if err != nil {
		return nil, err
	}
	return &fileSource{data: b}, nil
}

func (fs *fileSource) ReadPage(pgno int64) ([]byte, error) {
	off := (pgno - 1) * 4096
	if off < 0 || off >= int64(len(fs.data)) {
		return nil, fmt.Errorf("page %d out of range", pgno)
	}
	end := off + 4096
	if end > int64(len(fs.data)) {
		end = int64(len(fs.data))
	}
	return fs.data[off:end], nil
}

func (fs *fileSource) NumPages() int64 { return int64(len(fs.data) / 4096) }
