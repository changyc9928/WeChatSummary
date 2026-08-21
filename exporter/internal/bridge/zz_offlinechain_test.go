package bridge

import (
	"encoding/hex"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"wechatsummary/exporter/internal/extract"
	"wechatsummary/exporter/internal/sqlcipher"
	"wechatsummary/exporter/internal/sqlite"
)

// TestZZOfflineImageChain scans the transferred message shards for
// LocalType==3 (image) rows in the two photo-heavy chats and, for the first
// N, reports: xmlMd5, packed dat name, hardlink-index fileName, and whether
// the on-disk transfer actually contains any matching .dat under the msg/
// attach dirs. This is the offline version of the live image resolution.
func TestZZOfflineImageChain(t *testing.T) {
	keyHex := "0917ebfc952c4cf99fe9641b496071cdd3a45688d37e497a8f8b72fbc42395c6"
	key, _ := hex.DecodeString(keyHex)
	accountDir := "/Users/yincheng.chang/WeChatSummary/raw-extract/wxid_c03p833r8a4422_bfe8"

	idx, reason := loadHardlinkIndex(accountDir, key)
	if idx == nil {
		t.Fatalf("hardlink index: %s", reason)
	}

	shards := []string{
		"db_storage/message/biz_message_0.db", "db_storage/message/message_0.db",
		"db_storage/message/message_4.db",
	}
	// check the exact row from the live log first (rowid 1959 in the export)
	// we can't know its shard offline; scan all for LocalType 3 rows.
	attachRoot := filepath.Join(accountDir, "msg", "attach")
	onDisk := map[string]bool{}
	filepath.Walk(attachRoot, func(p string, info os.FileInfo, err error) error {
		if err != nil {
			return nil
		}
		if info.IsDir() {
			return nil
		}
		name := filepath.Base(p)
		if strings.HasSuffix(name, ".dat") || strings.HasSuffix(name, ".jpg") ||
			strings.HasSuffix(name, ".png") || strings.HasSuffix(name, ".webp") {
			onDisk[name] = true
		}
		return nil
	})
	fmt.Printf("on-disk candidates in msg/attach: %d distinct names\n", len(onDisk))

	wantTables := map[string]bool{
		"Msg_0472210ab6a1205a52c57f290abcdb3a": true,
		"Msg_2e1228eda4243db429eb18d0ab385df5": true,
	}
	var imageRows int
	var resolvedChain int
	var withPack int
	for _, rel := range shards {
		f, err := sqlcipher.OpenWal(accountDir+"/"+rel, key, sqlcipher.ModeDerived, false)
		if err != nil {
			fmt.Printf("  skip %s: %v\n", rel, err)
			continue
		}
		db, derr := sqlite.Open(f)
		if derr != nil {
			f.Close()
			fmt.Printf("  skip %s sqlite: %v\n", rel, derr)
			continue
		}
		packedAll := map[int64]string{}
		for _, ti := range db.Tables() {
			if ti.Type != "table" || !wantTables[ti.Name] {
				continue
			}
			tbl, terr := db.Table(ti.Name)
			if terr != nil {
				fmt.Printf("  table %s: %v\n", ti.Name, terr)
				continue
			}
			cols := parseCreateColumns(ti.SQL)
			pm, _, perr := extract.ScanPackedInfo(tbl, cols)
			if perr == nil {
				for k, v := range pm {
					packedAll[k] = v
				}
			}
		}
		for _, ti := range db.Tables() {
			if ti.Type != "table" || !wantTables[ti.Name] {
				continue
			}
			tbl, terr := db.Table(ti.Name)
			if terr != nil {
				continue
			}
			cols := parseCreateColumns(ti.SQL)
			messages, merr := extract.ExtractMessages(tbl, cols, 0, nil)
			if merr != nil {
				fmt.Printf("  extract %s: %v\n", ti.Name, merr)
				continue
			}
			var shown int
			for _, m := range messages {
				if m.LocalType != 3 {
					continue
				}
				imageRows++
				xmlMd5 := extract.MediaMd5FromXML(m.RawContent)
				packed := packedAll[m.RowID] // already the parsed dat base (production path)
				idxName := idx.fileNameFor(xmlMd5)
				hit := false
				used := ""
				if idxName != "" {
					used = "hardlink"
					hit = onDisk[idxName] || onDisk[strings.ToLower(idxName)]
				} else if packed != "" {
					used = "packed->hardlink"
					hit = idx.fileNameFor(packed) != "" || onDisk[packed] || onDisk[strings.ToLower(packed)]
				}
				if packed != "" {
					withPack++
				}
				if hit {
					resolvedChain++
				}
				if shown < 6 {
					fmt.Printf("  %s rowid=%d xmlMd5=%s packed=%q hardlinkName=%q chain=%s onDisk=%v\n",
						ti.Name, m.RowID, xmlMd5, packed, idxName, used, hit)
					shown++
				}
			}
		}
		f.Close()
	}
	fmt.Printf("\nSUMMARY: imageRows=%d withPacked=%d chainResolved=%d (hardlink-or-packed match on disk)\n",
		imageRows, withPack, resolvedChain)
}

var _ = hex.EncodeToString