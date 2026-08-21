package bridge

import (
	"encoding/hex"
	"fmt"
	"testing"

	"wechatsummary/exporter/internal/sqlcipher"
	"wechatsummary/exporter/internal/sqlite"
)

// TestZZVerifyNameFix runs the production resolveSessionNames against the
// transferred account files and prints the resolved names for the 6 chats
// that previously showed as bare Msg_<md5>.
func TestZZVerifyNameFix(t *testing.T) {
	keyHex := "0917ebfc952c4cf99fe9641b496071cdd3a45688d37e497a8f8b72fbc42395c6"
	key, _ := hex.DecodeString(keyHex)
	accountDir := "/Users/yincheng.chang/WeChatSummary/raw-extract/wxid_c03p833r8a4422_bfe8"

	// open the 7 message shards as production does (OpenWal derived)
	shards := []string{
		"db_storage/message/biz_message_0.db", "db_storage/message/biz_message_1.db",
		"db_storage/message/message_0.db", "db_storage/message/message_1.db",
		"db_storage/message/message_2.db", "db_storage/message/message_3.db",
		"db_storage/message/message_4.db",
	}
	var dbs []*sqlite.DB
	for _, rel := range shards {
		f, err := sqlcipher.OpenWal(accountDir+"/"+rel, key, sqlcipher.ModeDerived, false)
		if err != nil {
			fmt.Printf("  skip %s: %v\n", rel, err)
			continue
		}
		db, derr := sqlite.Open(f)
		f.Close()
		if derr != nil {
			fmt.Printf("  skip %s sqlite: %v\n", rel, derr)
			continue
		}
		dbs = append(dbs, db)
	}
	fmt.Printf("opened %d shard(s)\n", len(dbs))

	tables := []string{
		"Msg_08e134140f4ad6ce3ebc4ac8d6a922cc",
		"Msg_c373ffd4b52cb3e89a25500bd6b27714",
		"Msg_c156d1a38aa55c427b37494e8f31104c",
		"Msg_7b875b5f04924ae0352080a125d7ea99",
		"Msg_55d531167d2a62d5fa612d099bcfd4a0",
		"Msg_c21863fd1e856a9c88ff7068dece0562",
	}
	names := resolveSessionNames(accountDir, key, dbs, tables)
	fmt.Printf("\nresolved names (production path):\n")
	for _, t := range tables {
		n := names[t]
		fmt.Printf("  %-36s id=%-20q display=%q\n", t, n.ID, n.Display)
	}
	testID := names["Msg_55d531167d2a62d5fa612d099bcfd4a0"]
	if testID.Display != "编程指北" {
		t.Errorf("expected 编程指北, got %q", testID.Display)
	}
}
