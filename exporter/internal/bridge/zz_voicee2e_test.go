package bridge

import (
	"archive/zip"
	"bytes"
	"encoding/hex"
	"fmt"
	"strings"
	"testing"

	"wechatsummary/exporter/internal/extract"
	"wechatsummary/exporter/internal/sqlcipher"
	"wechatsummary/exporter/internal/sqlite"
)

// TestZZVoiceE2E runs the fixed exportVoice against the transferred account:
// it opens the media_*.db VoiceInfo tables itself and must save wav entries
// for real voice rows. Refs come from the real VoiceInfo rows of media_1.db.
func TestZZVoiceE2E(t *testing.T) {
	keyHex := "0917ebfc952c4cf99fe9641b496071cdd3a45688d37e497a8f8b72fbc42395c6"
	key, _ := hex.DecodeString(keyHex)
	accountDir := "/Users/yincheng.chang/WeChatSummary/raw-extract/wxid_c03p833r8a4422_bfe8"
	dbPath := accountDir + "/db_storage/message/message_4.db"

	// read real VoiceInfo rows from media_1.db
	f, err := sqlcipher.OpenWal(accountDir+"/db_storage/message/media_1.db", key, sqlcipher.ModeDerived, false)
	if err != nil {
		t.Fatalf("media_1 open: %v", err)
	}
	defer f.Close()
	db, derr := sqlite.Open(f)
	if derr != nil {
		t.Fatal(derr)
	}
	var tbl *sqlite.Table
	var cols []string
	for _, ti := range db.Tables() {
		if ti.Type == "table" && strings.EqualFold(ti.Name, "VoiceInfo") {
			tbl, _ = db.Table(ti.Name)
			cols = parseCreateColumns(ti.SQL)
		}
	}
	if tbl == nil {
		t.Fatal("no VoiceInfo table")
	}
	// columns: chat_name_id, create_time, local_id, svr_id, voice_data, data_index
	_ = indexFold(cols, "chat_name_id")
	ti := indexFold(cols, "create_time")
	li := indexFold(cols, "local_id")
	si := indexFold(cols, "svr_id")
	rows, ids, serr := tbl.ScanRowids(0)
	if serr != nil {
		t.Fatal(serr)
	}
	fmt.Printf("VoiceInfo rows=%d cols=%v\n", len(rows), cols)
	var msgs []extract.Message
	asInt := func(r []sqlite.Value, i int) int64 {
		if i < 0 || i >= len(r) {
			return 0
		}
		v := r[i]
		switch v.Kind {
		case sqlite.VInt:
			return v.Int
		case sqlite.VText:
			var n int64
			fmt.Sscanf(strings.TrimSpace(v.Text), "%d", &n)
			return n
		}
		return 0
	}
	for i, r := range rows {
		if i >= 40 {
			break
		}
		var rowid int64
		if i < len(ids) {
			rowid = ids[i]
		}
		_ = rowid
		msgs = append(msgs, extract.Message{
			LocalType:         34,
			CreateTime:        asInt(r, ti),
			LocalID:           asInt(r, li),
			PlatformMessageID: asInt(r, si),
		})
	}
	if len(msgs) == 0 {
		t.Skip("no voice rows")
	}
	fmt.Printf("crafted %d voice msg refs\n", len(msgs))

	var buf bytes.Buffer
	zw := zip.NewWriter(&buf)
	saved, failed := exportVoice(zw, dbPath, key, []*sqlite.DB{}, msgs, nil)
	if cerr := zw.Close(); cerr != nil {
		t.Fatal(cerr)
	}
	fmt.Printf("voice export: saved=%d failed=%d zipBytes=%d\n", saved, failed, buf.Len())
	if saved == 0 {
		t.Fatalf("voice pipeline saved 0 despite %d VoiceInfo rows", len(msgs))
	}
	t.Logf("voice pipeline OK: saved=%d failed=%d", saved, failed)
}
