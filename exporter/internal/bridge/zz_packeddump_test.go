package bridge

import (
	"encoding/hex"
	"fmt"
	"strings"
	"testing"

	"wechatsummary/exporter/internal/extract"
	"wechatsummary/exporter/internal/sqlcipher"
	"wechatsummary/exporter/internal/sqlite"
)

func TestZZPackedDump(t *testing.T) {
	keyHex := "0917ebfc952c4cf99fe9641b496071cdd3a45688d37e497a8f8b72fbc42395c6"
	key, _ := hex.DecodeString(keyHex)
	accountDir := "/Users/yincheng.chang/WeChatSummary/raw-extract/wxid_c03p833r8a4422_bfe8"
	f, err := sqlcipher.OpenWal(accountDir+"/db_storage/message/message_4.db", key, sqlcipher.ModeDerived, false)
	if err != nil {
		t.Fatalf("open: %v", err)
	}
	defer f.Close()
	db, derr := sqlite.Open(f)
	if derr != nil {
		t.Fatalf("sqlite: %v", derr)
	}
	var tbl *sqlite.Table
	var cols []string
	for _, ti := range db.Tables() {
		if ti.Type == "table" && strings.EqualFold(ti.Name, "Msg_2e1228eda4243db429eb18d0ab385df5") {
			tbl, _ = db.Table(ti.Name)
			cols = parseCreateColumns(ti.SQL)
			break
		}
	}
	if tbl == nil {
		t.Fatalf("table not found")
	}
	pt := -1
	for i, c := range cols {
		if strings.EqualFold(c, "packed_info_data") {
			pt = i
		}
	}
	fmt.Printf("packed col idx=%d, total cols=%d\n", pt, len(cols))
	rows, ids, rerr := tbl.ScanRowids(0)
	if rerr != nil {
		t.Fatal(rerr)
	}
	shown := 0
	for ri, r := range rows {
		if pt < 0 || pt >= len(r) {
			continue
		}
		v := r[pt]
		var buf []byte
		switch v.Kind {
		case sqlite.VBlob:
			buf = v.Blob
		case sqlite.VText:
			buf = []byte(v.Text)
		default:
			continue
		}
		rowid := int64(0)
		if ri < len(ids) {
			rowid = ids[ri]
		}
		if shown < 10 {
			fmt.Printf("rowid=%d kind=%d hex=%s ascii=%q parsed=%q\n",
				rowid, v.Kind, hex.EncodeToString(buf), printable(buf), extract.ParseImageDatName(v))
			shown++
		}
	}
	_ = hex.EncodeToString
}

func printable(b []byte) string {
	var sb strings.Builder
	for _, c := range b {
		if c >= 0x20 && c <= 0x7e {
			sb.WriteByte(c)
		} else {
			sb.WriteByte('.')
		}
	}
	if sb.Len() > 120 {
		return sb.String()[:120]
	}
	return sb.String()
}
