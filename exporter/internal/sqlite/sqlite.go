// Package sqlite implements a minimal read-only SQLite file-format reader
// sufficient for WeChat 4.x databases: page access, b-tree traversal, record
// decoding and a schema-driven table scan. It deliberately implements no SQL
// engine — callers scan known tables directly by root page.
package sqlite

import (
	"encoding/binary"
	"errors"
	"fmt"
	"sort"
	"strconv"
	"strings"
)

// PageSource yields SQLite page images (already decrypted).
type PageSource interface {
	ReadPage(pgno int64) ([]byte, error)
	NumPages() int64
}

// ValueKind discriminates record column values.
type ValueKind int

const (
	VNull ValueKind = iota
	VInt
	VFloat
	VText
	VBlob
)

// Value is a decoded record column.
type Value struct {
	Kind  ValueKind
	Int   int64
	Float float64
	Text  string
	Blob  []byte
}

// TableInfo describes one entry in sqlite_master.
type TableInfo struct {
	Type     string // table | index | view | trigger
	Name     string
	Table    string
	RootPage int64
	SQL      string
}

// DB is an opened database image.
type DB struct {
	src    PageSource
	pageSz int
	usable int
	tables []TableInfo
	byName map[string]*Table
}

// Open reads the (decrypted) page 1 header and parses sqlite_master.
func Open(src PageSource) (*DB, error) {
	p1, err := src.ReadPage(1)
	if err != nil {
		return nil, err
	}
	db := &DB{src: src, byName: map[string]*Table{}}
	if len(p1) < 100 {
		return nil, errors.New("sqlite: page 1 too short")
	}
	if string(p1[0:16]) != "SQLite format 3\x00" {
		return nil, errors.New("sqlite: not a database (magic mismatch)")
	}
	db.pageSz = int(binary.BigEndian.Uint16(p1[16:18]))
	if db.pageSz == 1 {
		db.pageSz = 65536
	}
	if db.pageSz < 512 || db.pageSz > 65536 {
		return nil, fmt.Errorf("sqlite: invalid page size %d", db.pageSz)
	}
	reserved := int(p1[20])
	db.usable = db.pageSz - reserved

	master, err := db.ReadTableRoot(1)
	if err != nil {
		return nil, fmt.Errorf("sqlite: cannot read sqlite_master: %w", err)
	}
	rows, err := master.Scan(0)
	if err != nil {
		return nil, err
	}
	for _, r := range rows {
		ti := TableInfo{}
		if len(r) > 0 {
			ti.Type = colStr(r[0])
		}
		if len(r) > 1 {
			ti.Name = colStr(r[1])
		}
		if len(r) > 2 {
			ti.Table = colStr(r[2])
		}
		if len(r) > 3 {
			ti.RootPage = colInt(r[3])
		}
		if len(r) > 4 {
			ti.SQL = colStr(r[4])
		}
		db.tables = append(db.tables, ti)
	}
	sort.Slice(db.tables, func(i, j int) bool { return db.tables[i].Name < db.tables[j].Name })
	return db, nil
}

func colStr(v Value) string {
	switch v.Kind {
	case VText:
		return v.Text
	case VBlob:
		return string(v.Blob)
	}
	return ""
}

func colInt(v Value) int64 {
	if v.Kind == VInt {
		return v.Int
	}
	return 0
}

// Tables lists all schema objects.
func (db *DB) Tables() []TableInfo { return db.tables }

// Table returns a scan-able handle for a table by name.
func (db *DB) Table(name string) (*Table, error) {
	if t, ok := db.byName[name]; ok {
		return t, nil
	}
	for i := range db.tables {
		ti := &db.tables[i]
		if ti.Type == "table" && strings.EqualFold(ti.Name, name) {
			t := &Table{db: db, root: ti.RootPage, name: ti.Name}
			db.byName[name] = t
			return t, nil
		}
	}
	return nil, fmt.Errorf("sqlite: no table %q", name)
}

// HasTable reports whether a table exists.
func (db *DB) HasTable(name string) bool {
	for i := range db.tables {
		if strings.EqualFold(db.tables[i].Name, name) {
			return true
		}
	}
	return false
}

// PageSize exposes the logical page size.
func (db *DB) PageSize() int { return db.pageSz }

// Usable exposes the usable bytes per page (page size minus reserved bytes).
func (db *DB) Usable() int { return db.usable }

// ReadPage proxies to the source.
func (db *DB) ReadPage(pgno int64) ([]byte, error) { return db.src.ReadPage(pgno) }

// Table is a handle for scanning a single table's rows.
type Table struct {
	db   *DB
	root int64
	name string
}

// Name returns the table name.
func (t *Table) Name() string { return t.name }

// ReadTableRoot constructs a scan handle for an arbitrary root page
// (used for sqlite_master and index tables).
func (db *DB) ReadTableRoot(root int64) (*Table, error) {
	if root < 1 {
		return nil, fmt.Errorf("sqlite: invalid root page %d", root)
	}
	return &Table{db: db, root: root}, nil
}

// Row is one record.
type Row []Value

var errStop = errors.New("sqlite: scan stopped")

// Scan extracts all rows of the table, ascending by rowid. limit>0 caps rows.
func (t *Table) Scan(limit int) ([]Row, error) {
	var out []Row
	err := t.walk(limit, func(_ int64, r Row) error {
		out = append(out, r)
		return nil
	})
	if err != nil && err != errStop {
		return nil, err
	}
	return out, nil
}

// ScanRowids extracts all rows ascending by rowid, along with each row's
// rowid. Needed when a table's key column is an INTEGER PRIMARY KEY alias
// (the record stores NULL and only the cell key carries the value), e.g.
// WeChat 4.1 Name2Id. limit>0 caps rows.
func (t *Table) ScanRowids(limit int) ([]Row, []int64, error) {
	var out []Row
	var ids []int64
	err := t.walk(limit, func(id int64, r Row) error {
		out = append(out, r)
		ids = append(ids, id)
		return nil
	})
	if err != nil && err != errStop {
		return nil, nil, err
	}
	return out, ids, nil
}

// CountRows returns the total number of leaf records in the table without
// decoding record payloads (fast: only b-tree page headers are visited).
func (t *Table) CountRows() (int64, error) {
	var n int64
	err := t.walkPages(t.root, map[int64]bool{}, func(int64, Row) error {
		n++
		return nil
	})
	if err != nil && err != errStop {
		return 0, err
	}
	return n, nil
}

// ScanColumn decodes a single column of every row (ascending by rowid),
// skipping payload assembly for the other columns — useful for aggregate
// scans (min/max time) over tables with large blob columns. limit>0 caps
// rows. Returns values plus each row's rowid.
func (t *Table) ScanColumn(col int, limit int) ([]Value, []int64, error) {
	var out []Value
	var ids []int64
	visited := map[int64]bool{}
	err := t.walkPages(t.root, visited, func(id int64, r Row) error {
		if limit > 0 && len(out) >= limit {
			return errStop
		}
		if col >= 0 && col < len(r) {
			out = append(out, r[col])
			ids = append(ids, id)
		}
		return nil
	})
	if err != nil && err != errStop {
		return nil, nil, err
	}
	return out, ids, nil
}

// ScanColumnTimes walks the whole table and calls fn for each row's value of
// column col, decoding only that column (blob columns are never assembled).
// Cols holding integers or numeric text work; non-numeric values are skipped.
func (t *Table) ScanColumnTimes(col int, fn func(unix int64)) error {
	return t.scanColumnOnly(col, func(_ int64, v Value) error {
		switch v.Kind {
		case VInt:
			fn(v.Int)
		case VFloat:
			fn(int64(v.Float))
		case VText:
			if n, err := strconv.ParseInt(strings.TrimSpace(v.Text), 10, 64); err == nil {
				fn(n)
			}
		}
		return nil
	})
}

// CountAndTimeRange walks the whole table once, counting rows and collecting
// the min/max of column col (createTime) in a single pass. This replaces the
// previous CountRows + ScanColumnTimes pair that walked every page twice —
// the dominant cost of the sessions list on accounts with many chat tables.
func (t *Table) CountAndTimeRange(col int) (count int64, minT, maxT int64, err error) {
	_ = t.scanColumnOnly(col, func(_ int64, v Value) error {
		count++
		var ts int64
		switch v.Kind {
		case VInt:
			ts = v.Int
		case VFloat:
			ts = int64(v.Float)
		case VText:
			if n, perr := strconv.ParseInt(strings.TrimSpace(v.Text), 10, 64); perr == nil {
				ts = n
			}
		}
		if ts > 0 {
			if minT == 0 || ts < minT {
				minT = ts
			}
			if ts > maxT {
				maxT = ts
			}
		}
		return nil
	})
	return count, minT, maxT, nil
}

// scanColumnOnly visits every table-leaf row but decodes only column col,
// reading the record header and skipping straight to that column's data
// region without assembling the other (possibly huge blob) columns.
func (t *Table) scanColumnOnly(col int, fn func(id int64, v Value) error) error {
	visited := map[int64]bool{}
	var walk func(pgno int64) error
	walk = func(pgno int64) error {
		if pgno < 1 || pgno > t.db.src.NumPages() || visited[pgno] {
			return nil
		}
		visited[pgno] = true
		page, err := t.db.src.ReadPage(pgno)
		if err != nil {
			return err
		}
		hdr := 0
		if pgno == 1 {
			if len(page) < 100 {
				return fmt.Errorf("sqlite: page 1 too short")
			}
			hdr = 100
		}
		if len(page) < hdr+12 {
			return fmt.Errorf("sqlite: page %d too short", pgno)
		}
		kind := page[hdr]
		ncells := int(binary.BigEndian.Uint16(page[hdr+3 : hdr+5]))
		switch kind {
		case 0x02:
			for _, off := range cellPointers(page, hdr+8, ncells) {
				if len(page) < off+4 {
					return fmt.Errorf("sqlite: corrupted interior index page %d", pgno)
				}
				child := int64(binary.BigEndian.Uint32(page[off : off+4]))
				if err := walk(child); err != nil {
					return err
				}
			}
			return nil
		case 0x05:
			rightMost := int64(binary.BigEndian.Uint32(page[hdr+8 : hdr+12]))
			for _, off := range cellPointers(page, hdr+12, ncells) {
				if len(page) < off+4 {
					return fmt.Errorf("sqlite: corrupted interior table page %d", pgno)
				}
				child := int64(binary.BigEndian.Uint32(page[off : off+4]))
				if err := walk(child); err != nil {
					return err
				}
			}
			return walk(rightMost)
		case 0x0D:
			for _, off := range cellPointers(page, hdr+8, ncells) {
				id, v, ok, err := t.readCellColumn(page, off, col)
				if err != nil {
					return err
				}
				if ok {
					if err := fn(id, v); err != nil {
						return err
					}
				}
			}
			return nil
		}
		// interior index / leaf index pages: skip (table b-trees only)
		return nil
	}
	return walk(t.root)
}

// readCellColumn decodes one column from a table-leaf cell. It parses the
// record header (always local: header length varint + serial types live at
// the start of the payload), computes the target column's data span, and
// only reads those bytes — overflow pages are followed only if the column
// itself spans them (never for small int/text columns).
func (t *Table) readCellColumn(page []byte, off, col int) (int64, Value, bool, error) {
	pos := off
	plen, n1, err := varintAt(page, pos)
	if err != nil {
		return 0, Value{}, false, err
	}
	pos += n1
	rowid, n2, err := varintAt(page, pos)
	if err != nil {
		return 0, Value{}, false, err
	}
	payloadOff := pos + n2

	u := t.db.usable
	x := u - 35
	m := (u-12)*32/255 - 23
	k := m + (int(plen)-m)%(u-4)
	local := int(plen)
	if int(plen) > x {
		if k <= x {
			local = k
		} else {
			local = m
		}
	}
	if local < 0 {
		local = 0
	}
	avail := len(page) - payloadOff
	if avail < 0 {
		avail = 0
	}
	if local > avail {
		local = avail
	}

	// Record header (header-length varint + serial types) always fits in the
	// local region for realistic schemas; guard defensively.
	hdrBytes := page[payloadOff : payloadOff+local]
	hlen, hn, err := varintAt(hdrBytes, 0)
	if err != nil {
		return rowid, Value{}, false, nil // malformed row: skip
	}
	if int(hlen) > len(hdrBytes) {
		return rowid, Value{}, false, nil // header spans overflow: fall back to full decode
	}
	// serial types
	typesPos := hn
	var types []int64
	for typesPos < int(hlen) {
		st, tn, err := varintAt(hdrBytes, typesPos)
		if err != nil {
			return rowid, Value{}, false, nil
		}
		types = append(types, st)
		typesPos += tn
	}
	if col < 0 || col >= len(types) {
		return rowid, Value{}, true, nil // column absent: skip without error
	}
	// byte offset of column col within the payload data
	dataStart := int(hlen)
	off2 := dataStart
	for i := 0; i < col; i++ {
		off2 += serialTypeSize(int(types[i]))
	}
	sz := serialTypeSize(int(types[col]))
	if off2+sz <= local {
		v, _, err := decodeColumn(types[col], page[payloadOff+off2:payloadOff+off2+sz])
		return rowid, v, true, err
	}
	// Column spans overflow: assemble the full record the slow way.
	rec, _, err := t.assembleRecord(page, payloadOff, int(plen))
	if err != nil {
		return rowid, Value{}, false, err
	}
	if col < len(rec) {
		return rowid, rec[col], true, nil
	}
	return rowid, Value{}, true, nil
}

// serialTypeSize returns the data byte size of a SQLite serial type.
func serialTypeSize(st int) int {
	switch {
	case st == 0 || (st >= 8 && st <= 11):
		return 0
	case st >= 1 && st <= 4:
		return st
	case st == 5:
		return 6 // 48-bit int
	case st == 6 || st == 7:
		return 8 // 64-bit int / float
	case st%2 == 0:
		return (st - 12) / 2
	default:
		return (st - 13) / 2
	}
}

// walk visits every table-leaf row of the table's b-tree in ascending rowid
// order, invoking fn with each (rowid, decoded record). limit>0 caps the
// number of rows passed to fn.
func (t *Table) walk(limit int, fn func(rowid int64, r Row) error) error {
	visited := map[int64]bool{}
	count := 0
	return t.walkPages(t.root, visited, func(id int64, r Row) error {
		if limit > 0 && count >= limit {
			return errStop
		}
		count++
		return fn(id, r)
	})
}

func (t *Table) walkPages(pgno int64, visited map[int64]bool, fn func(int64, Row) error) error {
	if pgno < 1 || pgno > t.db.src.NumPages() || visited[pgno] {
		return nil
	}
	visited[pgno] = true
	page, err := t.db.src.ReadPage(pgno)
	if err != nil {
		return err
	}
	hdr := 0
	if pgno == 1 {
		// page 1 carries the 100-byte database header before the b-tree header
		if len(page) < 100 {
			return fmt.Errorf("sqlite: page 1 too short")
		}
		hdr = 100
	}
	if len(page) < hdr+12 {
		return fmt.Errorf("sqlite: page %d too short", pgno)
	}
	kind := page[hdr]
	ncells := int(binary.BigEndian.Uint16(page[hdr+3 : hdr+5]))
	switch kind {
	case 0x02: // interior index: cell = [4B child pointer][key payload]
		for _, off := range cellPointers(page, hdr+8, ncells) {
			if len(page) < off+4 {
				return fmt.Errorf("sqlite: corrupted interior index page %d", pgno)
			}
			child := int64(binary.BigEndian.Uint32(page[off : off+4]))
			if err := t.walkPages(child, visited, fn); err != nil {
				return err
			}
		}
		return nil
	case 0x05: // interior table: cell = [4B child pointer][rowid varint]
		rightMost := int64(binary.BigEndian.Uint32(page[hdr+8 : hdr+12]))
		for _, off := range cellPointers(page, hdr+12, ncells) {
			if len(page) < off+4 {
				return fmt.Errorf("sqlite: corrupted interior table page %d", pgno)
			}
			child := int64(binary.BigEndian.Uint32(page[off : off+4]))
			if err := t.walkPages(child, visited, fn); err != nil {
				return err
			}
		}
		return t.walkPages(rightMost, visited, fn)
	case 0x0A: // leaf index: cell = [varint plen][record]
		for _, off := range cellPointers(page, hdr+8, ncells) {
			rec, _, err := t.readIndexCell(page, off)
			if err != nil {
				return err
			}
			if err := fn(-1, rec); err != nil {
				return err
			}
		}
		return nil
	case 0x0D: // leaf table
		for _, off := range cellPointers(page, hdr+8, ncells) {
			rec, id, err := t.readCellRecordRowid(page, off)
			if err != nil {
				return err
			}
			if err := fn(id, rec); err != nil {
				return err
			}
		}
		return nil
	}
	return fmt.Errorf("sqlite: unknown page type 0x%02X at page %d", kind, pgno)
}

// cellPointers reads the cell pointer array (cell offset 8 for leaf, 12 for
// interior-table pages, before the first cell header region).
func cellPointers(page []byte, hdrEnd int, ncells int) []int {
	if len(page) < hdrEnd+ncells*2 {
		return nil
	}
	offs := make([]int, ncells)
	for i := 0; i < ncells; i++ {
		offs[i] = int(binary.BigEndian.Uint16(page[hdrEnd+i*2:]))
	}
	return offs
}

// readCellRecordRowid decodes a table-leaf cell:
// [varint payloadLen][varint rowid]payload and returns the decoded record
// plus the row's rowid (the rowid is the cell key, which may differ from the
// stored record when the first column aliases rowid).
func (t *Table) readCellRecordRowid(page []byte, off int) (Row, int64, error) {
	pos := off
	plen, n1, err := varintAt(page, pos)
	if err != nil {
		return nil, 0, err
	}
	pos += n1
	rowid, n2, err := varintAt(page, pos)
	if err != nil {
		return nil, 0, err
	}
	rec, _, err := t.assembleRecord(page, pos+n2, int(plen))
	return rec, rowid, err
}

// readIndexCell decodes a leaf-index cell: [varint payloadLen]record.
func (t *Table) readIndexCell(page []byte, off int) (Row, []byte, error) {
	plen, n1, err := varintAt(page, off)
	if err != nil {
		return nil, nil, err
	}
	return t.assembleRecord(page, off+n1, int(plen))
}

// assembleRecord gathers payload (possibly spanning overflow pages) and
// decodes the record. Implements the exact SQLite overflow placement rules
// (fileformat2.html §1.6/§1.7):
//
//	table: X = U-35 ; index: X = (U-12)*64/255 - 23
//	M = (U-12)*32/255 - 23 ; K = M + ((P-M) % (U-4))
//	if P<=X: all local; elif K<=X: local=K; else: local=M
func (t *Table) assembleRecord(page []byte, payloadOff, plen int) (Row, []byte, error) {
	u := t.db.usable
	x := u - 35 // table leaf (we only decode table cells from leaves)
	m := (u-12)*32/255 - 23
	k := m + (plen-m)%(u-4)
	local := plen
	overflow := -1
	if plen > x {
		if k <= x {
			local = k
		} else {
			local = m
		}
	}
	if local < 0 {
		local = 0
	}
	avail := len(page) - payloadOff
	if avail < 0 {
		avail = 0
	}
	payload := make([]byte, 0, plen)
	if local > avail {
		local = avail
	}
	// The overflow pointer occupies the 4 bytes right after the local bytes;
	// if they were not stored in the page the cell would be corrupt.
	if local > 0 {
		payload = append(payload, page[payloadOff:payloadOff+local]...)
	}
	if plen > x && local+4 <= avail {
		overflow = int(binary.BigEndian.Uint32(page[payloadOff+local : payloadOff+local+4]))
	}
	remaining := plen - local
	for remaining > 0 && overflow > 0 {
		op, err := t.db.src.ReadPage(int64(overflow))
		if err != nil {
			return nil, nil, err
		}
		if len(op) < 4 {
			return nil, nil, errors.New("sqlite: short overflow page")
		}
		next := int(binary.BigEndian.Uint32(op[:4]))
		chunk := op[4:]
		if u > 0 && len(chunk) > u-4 {
			chunk = chunk[:u-4]
		}
		if len(chunk) > remaining {
			chunk = chunk[:remaining]
		}
		payload = append(payload, chunk...)
		remaining -= len(chunk)
		overflow = next
	}
	if remaining > 0 {
		return nil, nil, fmt.Errorf("sqlite: payload truncated (%d bytes missing)", remaining)
	}
	return decodeRecord(payload)
}

// decodeRecord parses a SQLite record into columns.
func decodeRecord(b []byte) (Row, []byte, error) {
	hlen, n, err := varintAt(b, 0)
	if err != nil {
		return nil, nil, err
	}
	if hlen < 0 || int(hlen) > len(b) {
		return nil, nil, errors.New("sqlite: bad record header length")
	}
	// serial type list
	pos := n
	var types []int64
	for pos < int(hlen) {
		st, n2, err := varintAt(b, pos)
		if err != nil {
			return nil, nil, err
		}
		types = append(types, st)
		pos += n2
	}
	data := b[int(hlen):]
	row := make(Row, 0, len(types))
	for _, st := range types {
		v, consumed, err := decodeColumn(st, data)
		if err != nil {
			return nil, nil, err
		}
		row = append(row, v)
		data = data[consumed:]
	}
	return row, nil, nil
}

func decodeColumn(st int64, b []byte) (Value, int, error) {
	switch {
	case st == 0:
		return Value{}, 0, nil
	case st == 1:
		return Value{Kind: VInt, Int: int64(int8(b[0]))}, 1, nil
	case st == 2:
		if len(b) < 2 {
			return Value{}, 0, errors.New("sqlite: short int16")
		}
		return Value{Kind: VInt, Int: int64(int16(binary.BigEndian.Uint16(b)))}, 2, nil
	case st == 3:
		if len(b) < 3 {
			return Value{}, 0, errors.New("sqlite: short int24")
		}
		v := int64(binary.BigEndian.Uint32([]byte{0, b[0], b[1], b[2]}))
		if b[0]&0x80 != 0 {
			v -= 1 << 24
		}
		return Value{Kind: VInt, Int: v}, 3, nil
	case st == 4:
		if len(b) < 4 {
			return Value{}, 0, errors.New("sqlite: short int32")
		}
		return Value{Kind: VInt, Int: int64(int32(binary.BigEndian.Uint32(b)))}, 4, nil
	case st == 5:
		if len(b) < 6 {
			return Value{}, 0, errors.New("sqlite: short int48")
		}
		v := int64(binary.BigEndian.Uint64(append([]byte{0, 0}, b[:6]...)))
		if b[0]&0x80 != 0 {
			v -= 1 << 48
		}
		return Value{Kind: VInt, Int: v}, 6, nil
	case st == 6:
		if len(b) < 8 {
			return Value{}, 0, errors.New("sqlite: short int64")
		}
		return Value{Kind: VInt, Int: int64(binary.BigEndian.Uint64(b))}, 8, nil
	case st == 7:
		if len(b) < 8 {
			return Value{}, 0, errors.New("sqlite: short float64")
		}
		u := binary.BigEndian.Uint64(b)
		return Value{Kind: VFloat, Float: mathFloat64(u)}, 8, nil
	case st == 8:
		return Value{Kind: VInt, Int: 0}, 0, nil
	case st == 9:
		return Value{Kind: VInt, Int: 1}, 0, nil
	case st >= 12 && st%2 == 0:
		n := int((st - 12) / 2)
		if len(b) < n {
			return Value{}, 0, errors.New("sqlite: short blob")
		}
		c := make([]byte, n)
		copy(c, b[:n])
		return Value{Kind: VBlob, Blob: c}, n, nil
	case st >= 13 && st%2 == 1:
		n := int((st - 13) / 2)
		if len(b) < n {
			return Value{}, 0, errors.New("sqlite: short text")
		}
		return Value{Kind: VText, Text: string(b[:n])}, n, nil
	}
	return Value{}, 0, fmt.Errorf("sqlite: unsupported serial type %d", st)
}

func mathFloat64(u uint64) float64 {
	return float64FromBits(u)
}

// varintAt decodes a SQLite varint at pos.
func varintAt(b []byte, pos int) (int64, int, error) {
	if pos < 0 || pos >= len(b) {
		return 0, 0, errors.New("sqlite: varint out of range")
	}
	var v int64
	for i := 0; i < 9; i++ {
		if pos+i >= len(b) {
			return 0, 0, errors.New("sqlite: truncated varint")
		}
		c := b[pos+i]
		if i == 8 {
			v = v<<8 | int64(c)
			return v, 9, nil
		}
		v = v<<7 | int64(c&0x7F)
		if c&0x80 == 0 {
			return v, i + 1, nil
		}
	}
	return 0, 0, errors.New("sqlite: varint too long")
}
