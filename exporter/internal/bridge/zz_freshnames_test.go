package bridge

import (
	"crypto/md5"
	"encoding/hex"
	"fmt"
	"testing"

	"wechatsummary/exporter/internal/extract"
	"wechatsummary/exporter/internal/sqlcipher"
	"wechatsummary/exporter/internal/sqlite"
)

// TestZZFreshNamesBruteforce proves the 5-6 "nameless" chats truly have no
// name anywhere: md5 of EVERY candidate id (SessionTable usernames, Name2Id
// values, contact userNames) compared against the Msg_<md5> table names.
func TestZZFreshNamesBruteforce(t *testing.T) {
	keyHex := "0917ebfc952c4cf99fe9641b496071cdd3a45688d37e497a8f8b72fbc42395c6"
	key, _ := hex.DecodeString(keyHex)
	root := "/Users/yincheng.chang/WeChatSummary/raw-extract/wxid_c03p833r8a4422_bfe8/db_storage"

	nameless := []string{
		"08e134140f4ad6ce3ebc4ac8d6a922cc",
		"c373ffd4b52cb3e89a25500bd6b27714",
		"c156d1a38aa55c427b37494e8f31104c",
		"7b875b5f04924ae0352080a125d7ea99",
		"55d531167d2a62d5fa612d099bcfd4a0",
		"c21863fd1e856a9c88ff7068dece0562",
	}
	want := map[string]bool{}
	for _, u := range nameless {
		want[u] = true
	}

	md5hex := func(s string) string {
		sum := md5.Sum([]byte(s))
		return hex.EncodeToString(sum[:])
	}

	candidates := map[string]string{} // md5hex -> source id
	add := func(id string, src string) {
		if id == "" {
			return
		}
		m := md5hex(id)
		if want[m] && candidates[m] == "" {
			candidates[m] = id + " [" + src + "]"
		}
	}

	// 1. session.db SessionTable/SessionNoContactInfoTable usernames + titles
	openAnd := func(rel string, fn func(*sqlite.DB)) {
		f, err := sqlcipher.OpenWal(root+rel, key, sqlcipher.ModeDerived, false)
		if err != nil {
			t.Fatalf("open %s: %v", rel, err)
		}
		defer f.Close()
		db, derr := sqlite.Open(f)
		if derr != nil {
			t.Fatalf("sqlite %s: %v", rel, derr)
		}
		fn(db)
	}
	openAnd("/session/session.db", func(db *sqlite.DB) {
		for _, ti := range db.Tables() {
			if ti.Type != "table" {
				continue
			}
			cols := parseCreateColumns(ti.SQL)
			for _, wantCol := range []string{"username", "user_name", "userName", "session_title", "md2_id", "user_name_id"} {
				ci := indexFold(cols, wantCol)
				if ci < 0 {
					continue
				}
				tbl, _ := db.Table(ti.Name)
				vals, _, rerr := tbl.ScanColumn(ci, 0)
				if rerr != nil {
					continue
				}
				for _, v := range vals {
					add(trimStr(valStr(v)), ti.Name+"."+wantCol)
				}
			}
		}
	})

	// 2. contact.db contact userNames + remarks + nicknames + any text col
	openAnd("/contact/contact.db", func(db *sqlite.DB) {
		for _, ti := range db.Tables() {
			if ti.Type != "table" || !stringsEqualFold(ti.Name, "contact") {
				continue
			}
			cols := parseCreateColumns(ti.SQL)
			for ci, c := range cols {
				tbl, _ := db.Table(ti.Name)
				vals, _, rerr := tbl.ScanColumn(ci, 0)
				if rerr != nil {
					continue
				}
				for _, v := range vals {
					add(trimStr(valStr(v)), "contact."+c)
				}
			}
		}
	})

	// 3. Name2Id across all message shards (values are real user ids)
	for _, sh := range []string{
		"/message/biz_message_0.db", "/message/biz_message_1.db",
		"/message/message_0.db", "/message/message_1.db",
		"/message/message_2.db", "/message/message_3.db", "/message/message_4.db",
	} {
		p := root + sh
		f, err := sqlcipher.OpenWal(p, key, sqlcipher.ModeDerived, false)
		if err != nil {
			fmt.Printf("  (shard %s open fail: %v)\n", sh, err)
			continue
		}
		db, derr := sqlite.Open(f)
		f.Close()
		if derr != nil {
			continue
		}
		for _, v := range extract.LoadName2Id(db) {
			add(trimStr(v), "Name2Id@"+sh)
		}
	}

	fmt.Printf("md5 matches among the %d nameless chats:\n", len(nameless))
	found := 0
	for _, u := range nameless {
		if src, ok := candidates[u]; ok {
			fmt.Printf("  %s -> %s\n", u, src)
			found++
		} else {
			fmt.Printf("  %s -> NO MATCH anywhere (ghost chat)\n", u)
		}
	}
	fmt.Printf("=> %d resolved, %d confirmed nameless\n", found, len(nameless)-found)
}

func stringsEqualFold(a, b string) bool {
	if len(a) != len(b) {
		return false
	}
	for i := 0; i < len(a); i++ {
		c1, c2 := a[i], b[i]
		if c1 >= 'A' && c1 <= 'Z' {
			c1 += 32
		}
		if c2 >= 'A' && c2 <= 'Z' {
			c2 += 32
		}
		if c1 != c2 {
			return false
		}
	}
	return true
}