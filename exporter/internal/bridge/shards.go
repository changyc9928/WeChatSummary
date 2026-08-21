package bridge

import (
	"crypto/md5"
	"encoding/hex"
	"fmt"
	"os"
	"path/filepath"
	"sort"
	"strings"

	"wechatsummary/exporter/internal/extract"
	"wechatsummary/exporter/internal/sqlcipher"
	"wechatsummary/exporter/internal/sqlite"
)

// siblingMsgDBs returns every chat-log database shard in the same directory
// as dbPath. WeChat 4.1 splits one account's chat history across
// message_0.db / message_1.db / ... (each shard holds a different time slice
// of the SAME Msg_<md5> tables), so a complete export must open them all.
func siblingMsgDBs(dbPath string) []string {
	dir := filepath.Dir(dbPath)
	entries, err := os.ReadDir(dir)
	if err != nil {
		return []string{dbPath}
	}
	seen := map[string]bool{}
	var out []string
	for _, e := range entries {
		if e.IsDir() {
			continue
		}
		if !isMsgDbName(e.Name()) {
			continue
		}
		p := filepath.Join(dir, e.Name())
		if !seen[p] {
			seen[p] = true
			out = append(out, p)
		}
	}
	if len(out) == 0 {
		return []string{dbPath}
	}
	sort.Strings(out)
	return out
}

// openMsgShards opens dbPath plus every sibling shard with the same key,
// returning one sqlite.DB per successfully opened shard. Shards that fail to
// open with the key are skipped with a warning (a sibling may be encrypted
// differently or be a leftover). The caller closes the returned files.
// Each shard is opened through OpenWal so rows that live only in the live
// -wal (WeChat running) are visible; the overlay degrades to the main file
// when no usable WAL exists.
func openMsgShards(dbPath string, secret []byte) (files []*sqlcipher.WalSource, dbs []*sqlite.DB, _ error) {
	paths := siblingMsgDBs(dbPath)
	var opened int
	for _, p := range paths {
		f, oerr := sqlcipher.OpenWal(p, secret, sqlcipher.ModeRaw, false)
		if oerr != nil {
			bridgeLog.Add("warn", "shards: skip %s (open failed: %v)", filepath.Base(p), oerr)
			continue
		}
		if fr := f.Frames(); fr > 0 {
			bridgeLog.Add("info", "shards: %s wal overlay applied (%d frame(s))", filepath.Base(p), fr)
			if d := f.Diag(); d != "" {
				bridgeLog.Add("debug", "shards: %s wal diag: %s", filepath.Base(p), d)
			}
		} else if we := f.WalError(); we != nil {
			bridgeLog.Add("warn", "shards: %s -wal could not be applied (%v) — newest rows may be missing", filepath.Base(p), we)
		} else if d := f.Diag(); d != "" {
			bridgeLog.Add("debug", "shards: %s wal diag: %s", filepath.Base(p), d)
		}
		db, derr := sqlite.Open(f)
		if derr != nil {
			f.Close()
			bridgeLog.Add("warn", "shards: skip %s (parse failed: %v)", filepath.Base(p), derr)
			continue
		}
		files = append(files, f)
		dbs = append(dbs, db)
		opened++
	}
	if opened == 0 {
		for _, f := range files {
			f.Close()
		}
		return nil, nil, fmt.Errorf("cannot open %s or any sibling shard with the given key", filepath.Base(dbPath))
	}
	bridgeLog.Add("info", "shards: opened %d chat-log database(s) (incl. %s)", opened, filepath.Base(dbPath))
	// The shards above were opened through the WAL overlay, so rows that live
	// only in un-checkpointed -wal files (WeChat running) are already visible.
	// Count the live WALs purely for diagnostics.
	walCount := 0
	if entries, err := os.ReadDir(filepath.Dir(dbPath)); err == nil {
		for _, e := range entries {
			if !e.IsDir() && strings.HasSuffix(strings.ToLower(e.Name()), "-wal") {
				if fi, serr := e.Info(); serr == nil && fi.Size() > 0 {
					walCount++
				}
			}
		}
	}
	if walCount > 0 {
		bridgeLog.Add("info", "shards: %d non-empty -wal file(s) read through the WAL overlay — newest messages included (WeChat may be running).", walCount)
	}
	return files, dbs, nil
}

// walWarning is retained for callers that want WAL-overlay status as
// informational strings. The bridge now reads -wal rows through the WAL
// overlay, so live -wal files are handled — the per-file info lines are
// logged as "shards: N non-empty -wal file(s) read through the WAL overlay"
// instead of being surfaced as user-facing warnings. No warning is emitted.
func walWarning(dbPath, accountDir string) []string {
	return nil
}

// mergeName2Id merges the Name2Id maps of every opened shard (each shard can
// reference a different subset of user names).
func mergeName2Id(dbs []*sqlite.DB) map[int64]string {
	out := map[int64]string{}
	for _, db := range dbs {
		for k, v := range extract.LoadName2Id(db) {
			out[k] = v
		}
	}
	return out
}

// sessionName is the resolved identity of one chat table.
type sessionName struct {
	ID      string // session id: wxid or room id ("" = unresolved)
	Display string // human name: remark/nickname, else the session id
}

// resolveSessionNames maps each msg_<md5> table name to its session id (the
// md5 in the table name is md5(sessionId); Name2Id holds every user_name, so
// matching md5(user_name) recovers the id) and, when the contact DB is
// readable with the same key, to a human display name (remark > nickname).
func resolveSessionNames(accountDir string, secret []byte, dbs []*sqlite.DB, tables []string) map[string]sessionName {
	out := map[string]sessionName{}
	if len(tables) == 0 {
		return out
	}

	// md5(user_name) -> user_name. Two sources, merged:
	//  1. session.db SessionTable — CipherTalk's authoritative index of every
	//     chat the account has (this is the "indexing file": it lists chats
	//     even when the session id never appears as a message sender, which
	//     is exactly why Name2Id alone left many Msg_<md5> tables unresolved).
	//  2. Name2Id across all shards — names referenced as real_sender_id.
	md5ToName, sessionTitles := loadSessionIDs(accountDir, secret)
	// Some WeChat 4.1 builds keep the session index inside the message shards
	// instead of a separate session.db (or the session.db name differs).
	for _, db := range dbs {
		sids := sessionTableIDs(db)
		for k, v := range sids.byMD5 {
			if _, ok := md5ToName[k]; !ok {
				md5ToName[k] = v
			}
			if title := sids.titles[k]; title != "" && sessionTitles[k] == "" {
				sessionTitles[k] = title
			}
		}
	}
	for _, name := range mergeName2Id(dbs) {
		if name == "" {
			continue
		}
		if _, ok := md5ToName[name]; !ok {
			sum := md5.Sum([]byte(name))
			md5ToName[hex.EncodeToString(sum[:])] = name
		}
	}

	// Contact DB: userName -> display name (remark first, then nickname).
	// ALSO index every contact userName by its md5: official accounts
	// (gh_...), service accounts and bots often have no SessionTable row and
	// never appear in Name2Id, so their Msg_<md5> tables were left unresolved
	// even though contact.db holds a real display name.
	contact := loadContactNames(accountDir, secret)
	for user := range contact {
		if user == "" {
			continue
		}
		sum := md5.Sum([]byte(user))
		m := hex.EncodeToString(sum[:])
		if _, ok := md5ToName[m]; !ok {
			md5ToName[m] = user
		}
	}

	for _, t := range tables {
		base := strings.ToLower(t)
		base = strings.TrimPrefix(base, "msg_")
		id := md5ToName[base]
		disp := id
		if id != "" {
			if c := contact[id]; c != "" {
				disp = c
			} else if title := sessionTitles[base]; title != "" && title != id {
				// Chats the contact DB has no row for (groups, removed
				// contacts): SessionNoContactInfoTable's session_title is the
				// display name WeChat itself shows. Contact names win.
				disp = title
			}
		}
		if disp == "" {
			bridgeLog.Add("debug", "names: %s -> unresolved (no SessionTable/Name2Id/contact hit for %s)", t, base)
		}
		out[t] = sessionName{ID: id, Display: disp}
	}
	return out
}

// loadSessionIDs opens the account's session database(s) (db_storage/session
// /session.db or session_*.db — WeChat 4.1 keeps the session index there) with
// the same SQLCipher key and returns md5(username) -> username for every row
// of the SessionTable / Session / session table. This is CipherTalk's primary
// chat-name index. Discovery mirrors CipherTalk's findSessionDbPath: exact
// session.db anywhere under db_storage first (scored), then session*.db
// under db_storage/session/, then a bounded recursive walk for session*.db.
// Every step is logged so a missing file vs an open/table failure is visible.
func loadSessionIDs(accountDir string, secret []byte) (byMD5 map[string]string, titles map[string]string) {
	out := map[string]string{}
	titles = map[string]string{}
	if accountDir == "" || secret == nil {
		bridgeLog.Add("info", "session.db: skipped (accountDir=%q secret=%v)", accountDir, secret != nil)
		return out, titles
	}
	var files []string
	seen := map[string]bool{}
	add := func(p string) {
		ap, err := filepath.Abs(p)
		if err != nil {
			ap = p
		}
		if !seen[ap] {
			seen[ap] = true
			files = append(files, ap)
		}
	}
	allKnownMiss := true
	storageRoot := filepath.Join(accountDir, "db_storage")
	for _, parts := range sessionDBKnownPaths {
		p := filepath.Join(append([]string{accountDir}, parts...)...)
		if fi, err := os.Stat(p); err == nil && !fi.IsDir() {
			add(p)
			allKnownMiss = false
		}
	}
	if !allKnownMiss {
		bridgeLog.Add("info", "session.db: found %d exact candidate(s) under %s (no tree walk)", len(files), displayDir(accountDir))
	} else {
		// Nothing at the known spots: walk. Only walk when the exact paths all
		// missed — the tree walk is O(every file under db_storage + account),
		// which on accounts with tens of thousands of cached media files takes
		// minutes (observed: 133s for 74K files under msg/attach).
		walkBounded(storageRoot, 6, func(p string, info os.FileInfo) bool {
			if info != nil && !info.IsDir() && strings.EqualFold(info.Name(), "session.db") {
				add(p)
			}
			return true
		})
		// 2) session*.db under db_storage/session/
		sessionDir := filepath.Join(accountDir, "db_storage", "session")
		if entries, err := os.ReadDir(sessionDir); err == nil {
			for _, e := range entries {
				if e.IsDir() {
					continue
				}
				l := strings.ToLower(e.Name())
				if (strings.HasPrefix(l, "session") || strings.HasPrefix(l, "sessions")) && strings.HasSuffix(l, ".db") {
					add(filepath.Join(sessionDir, e.Name()))
				}
			}
		}
		// 3) fallback: session*.db anywhere under the account root (bounded).
		walkBounded(accountDir, 6, func(p string, info os.FileInfo) bool {
			if info != nil && !info.IsDir() {
				l := strings.ToLower(info.Name())
				if (strings.HasPrefix(l, "session") || strings.HasPrefix(l, "sessions")) && strings.HasSuffix(l, ".db") {
					add(p)
				}
			}
			return true
		})
	}
	if len(files) == 0 {
		bridgeLog.Add("info", "session.db: no session*.db found under %s (checked db_storage/session, db_storage, account root)", displayDir(accountDir))
		return out, titles
	}
	sort.Strings(files)
	for _, p := range files {
		f, oerr := sqlcipher.OpenWal(p, secret, sqlcipher.ModeRaw, false)
		if oerr != nil {
			bridgeLog.Add("warn", "session.db: open %s failed: %v", displayDir(p), oerr)
			continue
		}
		db, derr := sqlite.Open(f)
		if derr != nil {
			bridgeLog.Add("warn", "session.db: sqlite open %s failed: %v", displayDir(p), derr)
			f.Close()
			continue
		}
		if fr := f.Frames(); fr > 0 {
			bridgeLog.Add("info", "session.db: %s wal overlay applied (%d frame(s))", displayDir(p), fr)
			if d := f.Diag(); d != "" {
				bridgeLog.Add("debug", "session.db: %s wal diag: %s", displayDir(p), d)
			}
		} else if we := f.WalError(); we != nil {
			bridgeLog.Add("warn", "session.db: %s -wal could not be applied (%v)", displayDir(p), we)
		} else if d := f.Diag(); d != "" {
			bridgeLog.Add("debug", "session.db: %s wal diag: %s", displayDir(p), d)
		}
		m := sessionTableIDs(db)
		f.Close()
		if len(m.byMD5) == 0 {
			var names []string
			var schemas []string
			for _, ti := range db.Tables() {
				if ti.Type != "table" {
					continue
				}
				names = append(names, ti.Name)
				l := strings.ToLower(ti.Name)
				if strings.Contains(l, "session") {
					schemas = append(schemas, ti.Name+"("+strings.Join(parseCreateColumns(ti.SQL), ",")+")")
				}
			}
			bridgeLog.Add("info", "session.db: %s opened but no SessionTable/Session rows with a username column (tables: %v %v)", displayDir(p), names, schemas)
			continue
		}
		bridgeLog.Add("info", "session.db: %s -> %d session id(s)", displayDir(p), len(m.byMD5))
		for k, v := range m.byMD5 {
			if out[k] == "" {
				out[k] = v
			}
		}
		for k, v := range m.titles {
			if titles[k] == "" {
				titles[k] = v
			}
		}
	}
	if len(out) == 0 {
		bridgeLog.Add("info", "session.db: no session id(s) resolved from %d candidate file(s)", len(files))
	}
	return out, titles
}

// walkBounded walks root recursively up to maxDepth, calling fn for every file
// (fn returns false to stop the whole walk early).
func walkBounded(root string, maxDepth int, fn func(path string, info os.FileInfo) bool) {
	filepath.Walk(root, func(path string, info os.FileInfo, err error) error {
		if err != nil {
			return nil
		}
		if path != root {
			rel, rerr := filepath.Rel(root, path)
			if rerr == nil {
				depth := strings.Count(rel, string(filepath.Separator))
				if info.IsDir() && depth >= maxDepth {
					return filepath.SkipDir
				}
			}
		}
		if !fn(path, info) {
			return filepath.SkipAll
		}
		return nil
	})
}

// sessionDBKnownPaths are the deterministic locations of the session index
// DB, checked before any filesystem walk. The walks in loadSessionIDs crawl
// the WHOLE account tree (one run took 133s just discovering session.db while
// WeChat's msg/attach held 74K files); on every account we have seen, one of
// these exact paths exists, so try them first and walk only as a fallback.
var sessionDBKnownPaths = func() [][]string {
	names := []string{"session.db", "session_0.db", "sessions.db"}
	var out [][]string
	for _, n := range names {
		out = append(out, []string{"db_storage", "session", n})
		out = append(out, []string{"db_storage", n})
		out = append(out, []string{n})
	}
	return out
}()

// walkBounded walks root recursively up to maxDepth, calling fn for every file
// has no Contact row: WeChat 4.1 keeps group/unknown-chat titles in
// SessionNoContactInfoTable(session_title) (CipherTalk falls back to the raw
// session id when the contact DB misses; session_title is a strictly better
// display name and we log it, but never let it replace a contact name).
var sessionTitleColumns = []string{"session_title", "title", "sessionName", "session_name", "conversationName"}

// sessionIDs is the resolved per-table identifier and optional display title
// of one session DB: md5(username) -> (username, session_title).
type sessionIDs struct {
	// byMD5 maps md5(username) -> username for every readable session row.
	byMD5 map[string]string
	// titles maps md5(username) -> session_title for rows that carry one
	// (SessionNoContactInfoTable). Consumed as a display-name fallback only.
	titles map[string]string
}

// sessionTableIDs reads every session-ish table of one session DB — NOT just
// the first name-sorted match (the SessionDeleteTable trap: it sorts before
// SessionTable but is an empty deletion log, so a break-on-first-match loop
// never reaches the real session index and every chat name collapses). Returns
// md5(username) -> username merged across all tables, preferring exact
// SessionTable/Session names, plus md5(username) -> session_title display
// names where a title column exists. Logs per-table row counts so a
// naming/column mismatch is diagnosable from the bridge log.
func sessionTableIDs(db *sqlite.DB) sessionIDs {
	out := sessionIDs{byMD5: map[string]string{}, titles: map[string]string{}}
	var candidates []struct {
		name string
		cols []string
	}
	for _, ti := range db.Tables() {
		if ti.Type != "table" {
			continue
		}
		cols := parseCreateColumns(ti.SQL)
		if !containsFold(cols, "username") && !containsFold(cols, "userName") && !containsFold(cols, "user_name") {
			continue
		}
		l := strings.ToLower(ti.Name)
		if l == "sessiontable" || l == "session" || strings.HasPrefix(l, "session") || strings.Contains(l, "session") {
			candidates = append(candidates, struct {
				name string
				cols []string
			}{ti.Name, cols})
		}
	}
	if len(candidates) == 0 {
		return out
	}
	// Exact SessionTable/Session first; session-prefixed tables next; any
	// other name containing "session" last. Never break early: the deletion
	// log (SessionDeleteTable) sorts first but must not shadow the index.
	sort.SliceStable(candidates, func(i, j int) bool {
		return sessionTableRank(candidates[i].name) < sessionTableRank(candidates[j].name)
	})
	for _, c := range candidates {
		userIdx := -1
		for _, want := range []string{"username", "user_name", "userName"} {
			if i := indexFold(c.cols, want); i >= 0 {
				userIdx = i
				break
			}
		}
		if userIdx < 0 {
			continue
		}
		t, err := db.Table(c.name)
		if err != nil {
			bridgeLog.Add("warn", "session.db: table %s lookup failed: %v (raw reader may not see it; is WeChat running?)", c.name, err)
			continue
		}
		rows, _, err := t.ScanRowids(0)
		if err != nil {
			bridgeLog.Add("warn", "session.db: scan %s failed: %v (raw reader may not see the rows; is WeChat running?)", c.name, err)
			continue
		}
		titleIdx := -1
		for _, want := range sessionTitleColumns {
			if i := indexFold(c.cols, want); i >= 0 {
				titleIdx = i
				break
			}
		}
		count := 0
		for _, r := range rows {
			if userIdx >= len(r) {
				continue
			}
			name := rowStrVal(r[userIdx])
			if name == "" {
				continue
			}
			sum := md5.Sum([]byte(name))
			key := hex.EncodeToString(sum[:])
			if out.byMD5[key] == "" {
				out.byMD5[key] = name
				count++
			}
			if titleIdx >= 0 && titleIdx < len(r) {
				if title := rowStrVal(r[titleIdx]); title != "" && out.titles[key] == "" {
					out.titles[key] = title
				}
			}
		}
		bridgeLog.Add("info", "session.db: %s scanned, %d new id(s) (%d row(s) readable via raw reader)", c.name, count, len(rows))
	}
	return out
}

// sessionTableRank orders session-ish tables so the real index is scanned
// before the deletion log: exact SessionTable/Session = 0, Session* = 1,
// anything else containing "session" = 2.
func sessionTableRank(name string) int {
	l := strings.ToLower(name)
	if l == "sessiontable" || l == "session" {
		return 0
	}
	if strings.HasPrefix(l, "session") {
		return 1
	}
	return 2
}

// contactNameColumns are accepted display-name columns of the Contact table,
// in preference order (CipherTalk reads remark then nick_name; both camelCase
// and snake_case spellings appear across WeChat versions).
var contactNameColumns = []string{
	"dbContactRemark", "dbContactRemarkText", "remark",
	"dbContactNickName", "dbContactNickname", "nickName", "nickname", "nick_name", "dbContactName",
	"alias", "dbContactAlias",
}

// loadContactNames opens the account's contact database(s) (same SQLCipher
// key as the message DB) and returns userName -> display name. Returns an
// empty map when no readable contact DB / Contact table is found.
func loadContactNames(accountDir string, secret []byte) map[string]string {
	out := map[string]string{}
	if accountDir == "" || secret == nil {
		return out
	}
	contactDir := filepath.Join(accountDir, "db_storage", "contact")
	entries, err := os.ReadDir(contactDir)
	if err != nil {
		return out
	}
	var files []string
	for _, e := range entries {
		if e.IsDir() {
			continue
		}
		l := strings.ToLower(e.Name())
		if strings.HasPrefix(l, "contact") && strings.HasSuffix(l, ".db") {
			files = append(files, filepath.Join(contactDir, e.Name()))
		}
	}
	sort.Strings(files)
	if len(files) == 0 {
		return out
	}

	for _, p := range files {
		f, oerr := sqlcipher.OpenWal(p, secret, sqlcipher.ModeRaw, false)
		if oerr != nil {
			continue
		}
		if fr := f.Frames(); fr > 0 {
			bridgeLog.Add("info", "contact.db: %s wal overlay applied (%d frame(s))", displayDir(p), fr)
			if d := f.Diag(); d != "" {
				bridgeLog.Add("debug", "contact.db: %s wal diag: %s", displayDir(p), d)
			}
		} else if we := f.WalError(); we != nil {
			bridgeLog.Add("warn", "contact.db: %s -wal could not be applied (%v)", displayDir(p), we)
		} else if d := f.Diag(); d != "" {
			bridgeLog.Add("debug", "contact.db: %s wal diag: %s", displayDir(p), d)
		}
		db, derr := sqlite.Open(f)
		if derr != nil {
			f.Close()
			continue
		}
		m := contactTableNames(db)
		f.Close()
		if len(m) == 0 {
			continue
		}
		for k, v := range m {
			if out[k] == "" {
				out[k] = v
			}
		}
	}
	return out
}

// contactTableNames scans one contact DB for a contact-like table and returns
// userName -> display name. Mirrors CipherTalk's enrichSessionsWithContacts:
// the display name is the first non-empty of remark / nick_name / alias per
// row (falling through columns, so a group chat whose remark is empty but
// nick_name holds the group name still resolves).
func contactTableNames(db *sqlite.DB) map[string]string {
	out := map[string]string{}
	var tableSQL string
	var tableName string
	for _, ti := range db.Tables() {
		if ti.Type != "table" {
			continue
		}
		cols := parseCreateColumns(ti.SQL)
		hasUser := containsFold(cols, "username") || containsFold(cols, "userName") || containsFold(cols, "user_name")
		hasDisplay := false
		for _, want := range contactNameColumns {
			if containsFold(cols, want) {
				hasDisplay = true
				break
			}
		}
		if !hasUser || !hasDisplay {
			continue
		}
		l := strings.ToLower(ti.Name)
		if l == "contact" || l == "contact_table" || strings.Contains(l, "contact") {
			tableSQL = ti.SQL
			tableName = ti.Name
			break
		}
	}
	if tableSQL == "" {
		return out
	}
	cols := parseCreateColumns(tableSQL)
	userIdx := -1
	for _, want := range []string{"username", "user_name", "userName"} {
		if i := indexFold(cols, want); i >= 0 {
			userIdx = i
			break
		}
	}
	if userIdx < 0 {
		return out
	}
	// All display columns, in preference order; per-row first non-empty wins.
	var nameIdxs []int
	for _, want := range contactNameColumns {
		if i := indexFold(cols, want); i >= 0 {
			nameIdxs = append(nameIdxs, i)
		}
	}
	if len(nameIdxs) == 0 {
		return out
	}
	t, err := db.Table(tableName)
	if err != nil {
		return out
	}
	rows, err := t.Scan(0)
	if err != nil {
		return out
	}
	named := 0
	for _, r := range rows {
		if userIdx >= len(r) {
			continue
		}
		user := rowStrVal(r[userIdx])
		if user == "" {
			continue
		}
		display := ""
		for _, ni := range nameIdxs {
			if ni >= len(r) {
				continue
			}
			if v := rowStrVal(r[ni]); displayNameUsable(v) {
				display = v
				break
			}
		}
		if display == "" {
			continue
		}
		named++
		if out[user] == "" {
			out[user] = display
		}
	}
	bridgeLog.Add("info", "contact: table %s -> %d named contact(s) of %d row(s)", tableName, named, len(rows))
	return out
}

// parseCreateColumns extracts column names from a CREATE TABLE statement.
func parseCreateColumns(sql string) []string {
	open := strings.Index(sql, "(")
	if open < 0 {
		return nil
	}
	depth := 0
	var cur strings.Builder
	var out []string
	flush := func() {
		part := strings.TrimSpace(cur.String())
		cur.Reset()
		if part == "" {
			return
		}
		name := part
		for i, r := range part {
			isIdent := r == '_' || (r >= 'a' && r <= 'z') || (r >= 'A' && r <= 'Z') || (i > 0 && r >= '0' && r <= '9')
			if !isIdent {
				name = part[:i]
				break
			}
		}
		switch strings.ToLower(name) {
		case "unique", "primary", "check", "foreign", "constraint", "index":
			return
		}
		out = append(out, name)
	}
	for i := open; i < len(sql); i++ {
		c := sql[i]
		switch c {
		case '(':
			depth++
			if depth == 1 {
				continue
			}
			cur.WriteByte(c)
		case ')':
			depth--
			if depth == 0 {
				flush()
				return out
			}
			cur.WriteByte(c)
		case ',':
			if depth == 1 {
				flush()
				continue
			}
			cur.WriteByte(c)
		default:
			if depth >= 1 {
				cur.WriteByte(c)
			}
		}
	}
	flush()
	return out
}

func containsFold(cols []string, want string) bool {
	return indexFold(cols, want) >= 0
}

func indexFold(cols []string, want string) int {
	for i, c := range cols {
		if strings.EqualFold(c, want) {
			return i
		}
	}
	return -1
}

// rowStrVal renders a sqlite Value as a string (mirrors extract.strVal).
func rowStrVal(v sqlite.Value) string {
	switch v.Kind {
	case sqlite.VText:
		return v.Text
	case sqlite.VBlob:
		return string(v.Blob)
	case sqlite.VInt:
		return fmt.Sprintf("%d", v.Int)
	}
	return ""
}

// displayNameUsable reports whether a contact display value should be shown
// as-is. WeChat stores a U+FFFC object-replacement character (and sometimes
// zero-width spaces) when a user's remark/nickname has not been synced; those
// placeholder-only values must not win over a real alias/nickname later in the
// column chain.
func displayNameUsable(s string) bool {
	if s == "" {
		return false
	}
	for _, r := range s {
		switch r {
		case '\uFFFC', '\u200B', '\u200C', '\u200D', '\uFEFF', ' ', '\t', '\n', '\r':
			continue
		default:
			return true
		}
	}
	return false
}
