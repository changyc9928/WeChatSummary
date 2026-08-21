package bridge

import (
	"fmt"
	"path/filepath"
	"testing"

	"wechatsummary/exporter/internal/sqlcipher"
)

func TestChkContactSenders(t *testing.T) {
	acct := "/Users/yincheng.chang/WeChatSummary/raw-extract/wxid_c03p833r8a4422_bfe8"
	secret := []byte("0917ebfc952c4cf99fe9641b496071cdd3a45688d37e497a8f8b72fbc42395c6")
	names := loadContactNames(acct, secret)
	for _, u := range []string{"wxid_o8ukrtbxo1uq22", "wxid_678hsrxxh7n122", "wxid_9ohxbzfuy0x112"} {
		if d, ok := names[u]; ok {
			fmt.Printf("CONTACT %s -> %q\n", u, d)
		} else {
			fmt.Printf("CONTACT %s -> MISSING\n", u)
		}
	}
	fmt.Printf("total contacts: %d\n", len(names))
	_ = sqlcipher.OpenWal
	_ = filepath.Join
}
