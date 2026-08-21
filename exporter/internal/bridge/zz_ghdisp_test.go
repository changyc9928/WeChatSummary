package bridge

import (
	"encoding/hex"
	"fmt"
	"testing"

	"wechatsummary/exporter/internal/sqlcipher"
	"wechatsummary/exporter/internal/sqlite"
)

// TestZZGhDisplayNames shows what display names the contact DB holds for the
// 5 official-account chats (gh_...) using the production contactTableNames.
func TestZZGhDisplayNames(t *testing.T) {
	keyHex := "0917ebfc952c4cf99fe9641b496071cdd3a45688d37e497a8f8b72fbc42395c6"
	key, _ := hex.DecodeString(keyHex)
	root := "/Users/yincheng.chang/WeChatSummary/raw-extract/wxid_c03p833r8a4422_bfe8/db_storage"
	f, err := sqlcipher.OpenWal(root+"/contact/contact.db", key, sqlcipher.ModeDerived, false)
	if err != nil {
		t.Fatalf("open: %v", err)
	}
	defer f.Close()
	db, derr := sqlite.Open(f)
	if derr != nil {
		t.Fatalf("sqlite: %v", derr)
	}
	m := contactTableNames(db)
	fmt.Printf("contactTableNames -> %d entries\n", len(m))
	want := map[string]bool{
		"gh_9309e87e468a": true, "gh_a65b5b56802c": true, "gh_3dfda90e39d6": true,
		"gh_ec04f5079174": true, "gh_8acd8f2ceadd": true, "wxid_9ohxbzfuy0x112": true,
	}
	for id := range want {
		fmt.Printf("  %-20s display=%q\n", id, m[id])
	}
}
