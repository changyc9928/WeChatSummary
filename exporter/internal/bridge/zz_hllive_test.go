package bridge

import (
	"encoding/hex"
	"fmt"
	"testing"
)

// TestZZOfflineHardlinkIndex loads the transferred account's hardlink index
// via the production loader and reports its coverage vs the LIVE log
// (image_hardlink_info_v4: 994 md5 row(s), 31 dir(s); video 13 md5/0 size).
func TestZZOfflineHardlinkIndex(t *testing.T) {
	keyHex := "0917ebfc952c4cf99fe9641b496071cdd3a45688d37e497a8f8b72fbc42395c6"
	key, _ := hex.DecodeString(keyHex)
	accountDir := "/Users/yincheng.chang/WeChatSummary/raw-extract/wxid_c03p833r8a4422_bfe8"
	idx, reason := loadHardlinkIndex(accountDir, key)
	if idx == nil {
		t.Fatalf("hardlink index: %s", reason)
	}
	fmt.Printf("reason=%q dbPath=%s\n", reason, idx.dbPath)
	fmt.Printf("images: %d md5 rows, %d dir names\n", len(idx.byMD5), len(idx.dirNames))
	fmt.Printf("videos: %d md5, %d size keys\n", len(idx.videoByMD5), len(idx.videoBySize))
}
