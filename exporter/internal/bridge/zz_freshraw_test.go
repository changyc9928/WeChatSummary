package bridge

import (
	"encoding/hex"
	"fmt"
	"strconv"
	"strings"
	"testing"

	"wechatsummary/exporter/internal/sqlcipher"
	"wechatsummary/exporter/internal/sqlite"
)

// TestZZFreshWalOpen opens each transferred DB the exact way the bridge does
// (OpenWal, derived mode = treat the 64-hex as a passphrase) and applies the
// live-era WAL overlay, then reports what the shard-caller would see.
func TestZZFreshWalOpen(t *testing.T) {
	keyHex := "0917ebfc952c4cf99fe9641b496071cdd3a45688d37e497a8f8b72fbc42395c6"
	key, _ := hex.DecodeString(keyHex)
	root := "/Users/yincheng.chang/WeChatSummary/raw-extract/wxid_c03p833r8a4422_bfe8/db_storage"
	paths := []string{
		"message/message_0.db", "message/message_4.db", "session/session.db",
		"contact/contact.db", "hardlink/hardlink.db", "message/biz_message_0.db",
	}
	for _, rel := range paths {
		p := root + "/" + rel
		f, err := sqlcipher.OpenWal(p, key, sqlcipher.ModeDerived, false)
		if err != nil {
			fmt.Printf("%-30s OPEN FAIL: %v\n", rel, err)
			continue
		}
		fr := f.Frames()
		diag := f.Diag()
		we := f.WalError()
		fmt.Printf("%-30s OPEN OK frames=%d diag=%q walErr=%v pages=%d\n",
			rel, fr, shortDiag(diag), errOrNil(we), f.NumPages())
		f.Close()
	}
}

// TestZZFreshSessionNames answers the user's core question offline: for each
// of the 6-7 "nameless" chats, is the username present in the transferred
// session.db (SessionTable / SessionNoContactInfoTable) and contact.db?
func TestZZFreshSessionNames(t *testing.T) {
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

	// ---- session.db via the production helper (same open + parse) ----
	sess, err := sqlcipher.OpenWal(root+"/session/session.db", key, sqlcipher.ModeDerived, false)
	if err != nil {
		t.Fatalf("session.db open: %v", err)
	}
	defer sess.Close()
	sdb, err := sqlite.Open(sess)
	if err != nil {
		t.Fatalf("sqlite open session: %v", err)
	}
	sids := sessionTableIDs(sdb)
	fmt.Printf("session.db SessionTable-ish: %d md5 -> name, %d titles\n", len(sids.byMD5), len(sids.titles))

	// raw username-ish values in every table that has a username column
	rawUsers := map[string][]string{} // username/title value -> table names
	for _, ti := range sdb.Tables() {
		if ti.Type != "table" {
			continue
		}
		cols := parseCreateColumns(ti.SQL)
		idx := -1
		for _, want := range []string{"username", "user_name", "userName", "session_title"} {
			if i := indexFold(cols, want); i >= 0 {
				idx = i
				break
			}
		}
		if idx < 0 {
			continue
		}
		tbl, terr := sdb.Table(ti.Name)
		if terr != nil {
			fmt.Printf("  (table %s: %v)\n", ti.Name, terr)
			continue
		}
		vals, _, rerr := tbl.ScanColumn(idx, 0)
		if rerr != nil {
			fmt.Printf("  (scan %s: %v)\n", ti.Name, rerr)
			continue
		}
		for _, v := range vals {
			trimmed := trimStr(valStr(v))
			if trimmed != "" {
				rawUsers[trimmed] = append(rawUsers[trimmed], ti.Name)
			}
		}
	}

	// ---- contact.db ----
	contact, err := sqlcipher.OpenWal(root+"/contact/contact.db", key, sqlcipher.ModeDerived, false)
	if err != nil {
		t.Fatalf("contact.db open: %v", err)
	}
	defer contact.Close()
	cdb, err := sqlite.Open(contact)
	if err != nil {
		t.Fatalf("sqlite open contact: %v", err)
	}
	contactSet := map[string]bool{}
	contactDisplay := map[string]string{}
	ct, cerr := cdb.Table("contact")
	if cerr != nil {
		t.Fatalf("contact table: %v", cerr)
	}
	{
		cols := parseCreateColumns(tableSQL(cdb, "contact"))
		userIdx := -1
		rmkIdx := -1
		for _, want := range []string{"userName", "username", "user_name"} {
			if i := indexFold(cols, want); i >= 0 {
				userIdx = i
				break
			}
		}
		for _, want := range []string{"remark", "nickName", "nickname"} {
			if i := indexFold(cols, want); i >= 0 {
				rmkIdx = i
				break
			}
		}
		if userIdx >= 0 {
			users, _, _ := ct.ScanColumn(userIdx, 0)
			for _, v := range users {
				contactSet[trimStr(valStr(v))] = true
			}
			if rmkIdx >= 0 {
				rmks, _, _ := ct.ScanColumn(rmkIdx, 0)
				for i := range users {
					if i < len(rmks) {
						contactDisplay[trimStr(valStr(users[i]))] = trimStr(valStr(rmks[i]))
					}
				}
			}
		}
	}
	fmt.Printf("contact.db: %d distinct usernames indexed, %d display names\n",
		len(contactSet), len(contactDisplay))

	fmt.Printf("\n%-34s | inSessionTable | inNoContactTable | inContact | remark\n", "username")
	fmt.Printf("-------------------------------------------------------------------------------\n")
	for _, md5hex := range nameless {
		inSess := sids.byMD5[md5hex]
		noContact := rawUsers[md5hex]
		inContact := contactSet[md5hex]
		remark := contactDisplay[md5hex]
		fmt.Printf("%-34s | %-15q | %-16v | %-9v | %q\n",
			md5hex, inSess, noContact, inContact, remark)
	}
}

func tableSQL(db *sqlite.DB, name string) string {
	for _, ti := range db.Tables() {
		if ti.Type == "table" && strings.EqualFold(ti.Name, name) {
			return ti.SQL
		}
	}
	return ""
}

func containsFoldAny(ss []string, wants ...string) bool {
	for _, want := range wants {
		if indexFold(ss, want) >= 0 {
			return true
		}
	}
	return false
}

func errOrNil(e error) string {
	if e == nil {
		return "-"
	}
	return e.Error()
}

func shortDiag(d string) string {
	if len(d) > 90 {
		return d[:90] + "..."
	}
	return d
}

func lower(s string) string {
	out := make([]byte, len(s))
	for i := 0; i < len(s); i++ {
		c := s[i]
		if c >= 'A' && c <= 'Z' {
			c += 32
		}
		out[i] = c
	}
	return string(out)
}

func valStr(v sqlite.Value) string {
	switch v.Kind {
	case sqlite.VText:
		return v.Text
	case sqlite.VBlob:
		return string(v.Blob)
	case sqlite.VInt:
		return strconv.FormatInt(v.Int, 10)
	}
	return ""
}

func trimStr(v string) string {
	start, end := 0, len(v)
	for start < end && (v[start] == ' ' || v[start] == '\t' || v[start] == '\n') {
		start++
	}
	for end > start && (v[end-1] == ' ' || v[end-1] == '\t' || v[end-1] == '\n') {
		end--
	}
	return v[start:end]
}