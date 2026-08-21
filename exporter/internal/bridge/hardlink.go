package bridge

import (
	"fmt"
	"os"
	"path/filepath"
	"sort"
	"strings"

	"wechatsummary/exporter/internal/datdecrypt"
	"wechatsummary/exporter/internal/sqlcipher"
	"wechatsummary/exporter/internal/sqlite"
)

// WeChat 4.1 (Windows) keeps image media in a hardlink index database
// (hardlink.db) whose tables are `image_hardlink_info_*` (md5, dir1, dir2,
// file_name) and `dir2id*` (rowid -> directory name). The actual files live
// under <account>/msg/attach/<dir1Name>/<dir2Name>/Img/<file_name> — a layout
// completely different from the 3.x md5-hash session dirs. This is the
// CipherTalk resolveHardlinkPath flow, ported to Go.

// hardlinkIndex is the md5 -> (dir1, dir2, file_name) map for one account,
// loaded once per export run.
type hardlinkIndex struct {
	imageTable string
	dirTable   string
	byMD5      map[string]hardlinkEntry
	dirNames   map[int64]string
	reason     string
	dbPath     string

	// videoByMD5 maps md5(file content) -> file_name for the account's
	// video_hardlink_info_* tables (CipherTalk queries these per message;
	// we index them once per export). Empty when no video table exists.
	videoByMD5 map[string]string
	// videoBySize maps file_size -> file_name for the rare video rows that
	// carry no md5 but do carry a size usable against the XML length attr.
	videoBySize map[int64][]string
	// videoTable is the comma-joined video_hardlink_info_* table names.
	videoTable string
}

type hardlinkEntry struct {
	dir1, dir2 int64
	fileName   string
}

// findHardlinkDB locates the account's hardlink.db: <accountDir>/hardlink.db,
// <accountDir>/db_storage/hardlink.db, then a bounded walk for any hardlink.db.
func findHardlinkDB(accountDir string) string {
	cands := []string{
		filepath.Join(accountDir, "hardlink.db"),
		filepath.Join(accountDir, "db_storage", "hardlink.db"),
		filepath.Join(accountDir, "db_storage", "hardlink", "hardlink.db"),
	}
	for _, c := range cands {
		if fi, err := os.Stat(c); err == nil && !fi.IsDir() {
			return c
		}
	}
	found := ""
	walkBounded(accountDir, 5, func(p string, info os.FileInfo) bool {
		if info != nil && !info.IsDir() && strings.EqualFold(info.Name(), "hardlink.db") {
			found = p
			return false
		}
		return true
	})
	return found
}

// walSize returns the size of the sibling "<db>-wal" file, or 0 when absent.
// A non-zero value means the database has un-checkpointed frames that a raw
// (non-WAL) reader cannot see — the usual cause of an incomplete index while
// WeChat is running.
func walSize(dbPath string) int64 {
	fi, err := os.Stat(dbPath + "-wal")
	if err != nil || fi.IsDir() {
		return 0
	}
	return fi.Size()
}

// loadHardlinkIndex opens hardlink.db (same SQLCipher key as the message DB),
// reads the image_hardlink_info_* md5 index and the dir2id* rowid->name map.
// Returns a nil index (with reason) when the DB/table is absent so callers
// can fall back to the legacy session-dir search.
func loadHardlinkIndex(accountDir string, secret []byte) (*hardlinkIndex, string) {
	p := findHardlinkDB(accountDir)
	if p == "" {
		return nil, "no hardlink.db found"
	}
	bridgeLog.Add("info", "hardlink.db: found %s", displayDir(p))
	// Open through the WAL overlay so the md5 index rows that live only in the
	// live -wal (WeChat running) are included — this is what makes the media
	// export complete without closing WeChat.
	f, err := sqlcipher.OpenWal(p, secret, sqlcipher.ModeRaw, false)
	if err != nil {
		return nil, fmt.Sprintf("hardlink.db open failed: %v", err)
	}
	if we := f.WalError(); we != nil {
		bridgeLog.Add("warn", "hardlink.db: -wal could not be applied (%v) — using the checkpointed index only; close WeChat and re-run for a complete image index.", we)
	} else if fr := f.Frames(); fr > 0 {
		bridgeLog.Add("info", "hardlink.db: wal overlay applied (%d frame(s)) — full image index including live rows", fr)
		if d := f.Diag(); d != "" {
			bridgeLog.Add("debug", "hardlink.db: wal diag: %s", d)
		}
	} else if d := f.Diag(); d != "" {
		bridgeLog.Add("debug", "hardlink.db: wal diag: %s", d)
	}
	db, derr := sqlite.Open(f)
	if derr != nil {
		f.Close()
		return nil, fmt.Sprintf("hardlink.db sqlite open failed: %v", derr)
	}
	defer f.Close()
	idx, reason := indexHardlinkDB(db)
	if idx == nil {
		return nil, reason
	}
	idx.reason = reason
	idx.dbPath = p
	bridgeLog.Add("info", "hardlink.db: %s (%s): %d md5 row(s), %d dir name(s), video %d md5/%d size", idx.imageTable, idx.dirTable, len(idx.byMD5), len(idx.dirNames), len(idx.videoByMD5), len(idx.videoBySize))
	return idx, ""
}

// indexHardlinkDB builds the hardlinkIndex from an already-open (decrypted)
// hardlink DB — separated out so tests can feed a plain DB. Every
// image_hardlink_info_* and dir2id* table is merged (WeChat rotates these
// names with version suffixes; matching only the first would miss rows).
func indexHardlinkDB(db *sqlite.DB) (*hardlinkIndex, string) {
	idx := &hardlinkIndex{byMD5: map[string]hardlinkEntry{}, dirNames: map[int64]string{}}
	var imageTables, dirTables, videoTables []string
	infoByName := map[string]sqlite.TableInfo{}
	for _, ti := range db.Tables() {
		if ti.Type != "table" {
			continue
		}
		infoByName[ti.Name] = ti
		l := strings.ToLower(ti.Name)
		if strings.HasPrefix(l, "image_hardlink_info") {
			imageTables = append(imageTables, ti.Name)
		}
		if strings.HasPrefix(l, "video_hardlink_info") {
			videoTables = append(videoTables, ti.Name)
		}
		if strings.HasPrefix(l, "dir2id") {
			dirTables = append(dirTables, ti.Name)
		}
	}
	if len(imageTables) == 0 && len(videoTables) == 0 {
		return nil, "hardlink.db: no image/video_hardlink_info table"
	}
	sort.Strings(imageTables)
	sort.Strings(dirTables)
	sort.Strings(videoTables)
	idx.imageTable = strings.Join(imageTables, ",")
	idx.dirTable = strings.Join(dirTables, ",")
	idx.videoTable = strings.Join(videoTables, ",")
	idx.byMD5 = map[string]hardlinkEntry{}
	idx.dirNames = map[int64]string{}
	idx.videoByMD5 = map[string]string{}
	idx.videoBySize = map[int64][]string{}

	// md5 index: merge every image_hardlink_info_* table.
	md5Seen := map[string]bool{}
	for _, tn := range imageTables {
		ti, err := db.Table(tn)
		if err != nil {
			continue
		}
		cols := parseCreateColumns(infoByName[tn].SQL)
		md5Idx := -1
		for _, want := range []string{"md5", "image_md5"} {
			if i := indexFold(cols, want); i >= 0 {
				md5Idx = i
				break
			}
		}
		dir1Idx := indexFold(cols, "dir1")
		dir2Idx := indexFold(cols, "dir2")
		fileIdx := -1
		for _, want := range []string{"file_name", "filename", "fileName", "name"} {
			if i := indexFold(cols, want); i >= 0 {
				fileIdx = i
				break
			}
		}
		if md5Idx < 0 || dir1Idx < 0 || dir2Idx < 0 || fileIdx < 0 {
			bridgeLog.Add("warn", "hardlink.db: %s columns md5/dir1/dir2/file_name not found (got %v); skipping", tn, cols)
			continue
		}
		rows, _, rerr := ti.ScanRowids(0)
		if rerr != nil {
			bridgeLog.Add("warn", "hardlink.db: %s scan failed: %v", tn, rerr)
			continue
		}
		for _, r := range rows {
			if md5Idx >= len(r) || dir1Idx >= len(r) || dir2Idx >= len(r) || fileIdx >= len(r) {
				continue
			}
			md5v := strings.ToLower(strings.TrimSpace(rowStrVal(r[md5Idx])))
			if md5v == "" || md5Seen[md5v] {
				continue
			}
			md5Seen[md5v] = true
			idx.byMD5[md5v] = hardlinkEntry{
				dir1:     rowIntVal(r[dir1Idx]),
				dir2:     rowIntVal(r[dir2Idx]),
				fileName: rowStrVal(r[fileIdx]),
			}
		}
	}

	// dir2id -> directory name: merge every dir2id* table.
	for _, tn := range dirTables {
		ti, err := db.Table(tn)
		if err != nil {
			continue
		}
		dcols := parseCreateColumns(infoByName[tn].SQL)
		uIdx := -1
		for _, want := range []string{"username", "user_name", "userName", "dir_name", "dirname", "dir"} {
			if i := indexFold(dcols, want); i >= 0 {
				uIdx = i
				break
			}
		}
		if uIdx < 0 {
			bridgeLog.Add("warn", "hardlink.db: %s has no username/dir_name column (got %v); skipping", tn, dcols)
			continue
		}
		drows, drowids, derr3 := ti.ScanRowids(0)
		if derr3 != nil {
			bridgeLog.Add("warn", "hardlink.db: %s scan failed: %v", tn, derr3)
			continue
		}
		for i, dr := range drows {
			if uIdx >= len(dr) {
				continue
			}
			var rid int64
			if i < len(drowids) {
				rid = drowids[i]
			} else {
				rid = rowIntVal(dr[0])
			}
			if v := rowStrVal(dr[uIdx]); v != "" {
				if _, ok := idx.dirNames[rid]; !ok {
					idx.dirNames[rid] = v
				}
			}
		}
	}

	// video_hardlink_info_*: md5 -> file_name (+ file_size -> file_name for
	// rows without an md5). Merged across every versioned table, does not
	// require dir tables. Mirrors CipherTalk's
	// queryVideoFileNames (videoService.ts): per-md5 exact match, then a
	// per-size fallback query for rows the XML length attr can reach.
	for _, tn := range videoTables {
		ti, err := db.Table(tn)
		if err != nil {
			continue
		}
		cols := parseCreateColumns(infoByName[tn].SQL)
		vmd5Idx := -1
		for _, want := range []string{"md5", "video_md5"} {
			if i := indexFold(cols, want); i >= 0 {
				vmd5Idx = i
				break
			}
		}
		sizeIdx := indexFold(cols, "file_size")
		vfileIdx := -1
		for _, want := range []string{"file_name", "filename", "fileName", "name", "path"} {
			if i := indexFold(cols, want); i >= 0 {
				vfileIdx = i
				break
			}
		}
		if vmd5Idx < 0 && sizeIdx < 0 {
			bridgeLog.Add("warn", "hardlink.db: %s has neither md5 nor file_size column (got %v); skipping video index", tn, cols)
			continue
		}
		rows, _, rerr := ti.ScanRowids(0)
		if rerr != nil {
			bridgeLog.Add("warn", "hardlink.db: %s scan failed: %v", tn, rerr)
			continue
		}
		md5Seen := map[string]bool{}
		for _, r := range rows {
			name := ""
			if vfileIdx >= 0 && vfileIdx < len(r) {
				name = rowStrVal(r[vfileIdx])
			}
			if vmd5Idx >= 0 && vmd5Idx < len(r) {
				md5v := strings.ToLower(strings.TrimSpace(rowStrVal(r[vmd5Idx])))
				if md5v != "" && !md5Seen[md5v] {
					md5Seen[md5v] = true
					idx.videoByMD5[md5v] = name
					continue
				}
			}
			if sizeIdx >= 0 && sizeIdx < len(r) {
				if sz := rowIntVal(r[sizeIdx]); sz > 0 {
					idx.videoBySize[sz] = append(idx.videoBySize[sz], name)
				}
			}
		}
		bridgeLog.Add("info", "hardlink.db: %s video index done: %d md5 -> fileName, %d size -> fileName", tn, len(idx.videoByMD5), len(idx.videoBySize))
	}
	return idx, ""
}

// fileNameFor returns the on-disk file name the hardlink index maps md5hex to
// ("" when absent). The legacy fallback needs it because the message XML md5
// is NOT the .dat base name on WeChat 4.1: the index knows the real file name
// (e.g. "33862c72....dat") even when the predicted attach path misses.
func (idx *hardlinkIndex) fileNameFor(md5hex string) string {
	if idx == nil || md5hex == "" {
		return ""
	}
	e, ok := idx.byMD5[strings.ToLower(md5hex)]
	if !ok {
		return ""
	}
	return e.fileName
}

// resolve looks up md5 in the index and returns the decrypted media bytes +
// the on-disk path. Returns an error when the file isn't found or doesn't
// decrypt.
func (idx *hardlinkIndex) resolve(accountDir string, md5hex string, xorKey byte, aesKey []byte) (string, []byte, error) {
	if idx == nil || md5hex == "" {
		return "", nil, fmt.Errorf("hardlink: missing index/md5")
	}
	e, ok := idx.byMD5[strings.ToLower(md5hex)]
	if !ok {
		return "", nil, fmt.Errorf("hardlink: md5 %s not in %s", md5hex, idx.imageTable)
	}
	dir1Name := idx.dirNames[e.dir1]
	dir2Name := idx.dirNames[e.dir2]
	if dir1Name == "" || dir2Name == "" {
		return "", nil, fmt.Errorf("hardlink: dir names unresolved (dir1=%d dir2=%d, dirTable=%s)", e.dir1, e.dir2, idx.dirTable)
	}
	attachRoot := filepath.Join(accountDir, "msg", "attach")
	// fileName may be a bare file, or already contain a relative subpath
	// (e.g. "Img/xxx.jpg"); always try the direct join first.
	candidates := []string{
		filepath.Join(attachRoot, dir1Name, dir2Name, e.fileName),
	}
	for _, sub := range []string{"Img", "mg", "Image", "image"} {
		candidates = append(candidates,
			filepath.Join(attachRoot, dir1Name, dir2Name, sub, e.fileName),
			filepath.Join(attachRoot, dir1Name, dir2Name, sub, strings.ToLower(e.fileName)),
		)
	}
	// Some 4.1 installs equal dir1 to the session hash and dir2 to the
	// YYYY-MM month: msg/attach/<sessionHash>/<YYYY-MM>/{Img,mg}/<file>.
	// Try the month-subdir layout with and without the Img level, and also
	// swap dir1/dir2 in case the index stores them in opposite order.
	month := dir2Name
	if len(month) == 4 { // dir2Name might be the year, dir1 the month
		month = dir1Name
	}
	if len(month) == 7 && month[4] == '-' || len(month) == 6 {
		for _, sub := range []string{"", "Img", "mg", "Image", "image"} {
			candidates = append(candidates,
				filepath.Join(attachRoot, dir1Name, month, sub, e.fileName),
				filepath.Join(attachRoot, dir2Name, month, sub, e.fileName),
			)
		}
	}
	// File-name variants: some indexes store the HD name (…_h.dat) while the
	// on-disk file is the plain md5, or vice versa; also try lowercase.
	base := e.fileName
	if i := strings.LastIndex(base, "."); i > 0 {
		base = base[:i]
	}
	for _, suffix := range []string{"", "_h", "_t", "_hd", "_thumb"} {
		for _, ext := range []string{".dat", ".jpg", ".png", ".gif", ".webp", ".jpeg", ""} {
			variant := base + suffix + ext
			if variant == e.fileName {
				continue
			}
			candidates = append(candidates,
				filepath.Join(attachRoot, dir1Name, dir2Name, variant),
				filepath.Join(attachRoot, dir1Name, dir2Name, "Img", variant),
				filepath.Join(attachRoot, dir1Name, dir2Name, "mg", variant),
			)
		}
	}
	// Fallback: the classic 3.x layout sometimes coexists:
	// msg/attach/<md5-ish>/<YYYY-MM>/Img/<fileName>.
	for _, c := range candidates {
		data, rerr := os.ReadFile(c)
		if rerr != nil {
			continue
		}
		dec, derr := datdecrypt.Decrypt(data, xorKey, aesKey)
		if derr == nil && datdecrypt.LooksLikeStrongMedia(dec) {
			return c, dec, nil
		}
		// V3 whole-file XOR fallback
		if datdecrypt.DetectVersion(data) == 0 {
			if k, ok := datdecrypt.DetectXorKeyFromHeader(data); ok {
				if d2 := datdecrypt.DecryptV3(data, k); datdecrypt.LooksLikeStrongMedia(d2) {
					return c, d2, nil
				}
			}
		}
	}
	return "", nil, fmt.Errorf("hardlink: no decryptable file for %s (%s/%s/%s)", md5hex, dir1Name, dir2Name, e.fileName)
}

// rowIntVal converts a sqlite.Value to an int64 leniently.
func rowIntVal(v sqlite.Value) int64 {
	switch v.Kind {
	case sqlite.VInt:
		return v.Int
	case sqlite.VFloat:
		return int64(v.Float)
	case sqlite.VText:
		var n int64
		fmt.Sscanf(strings.TrimSpace(v.Text), "%d", &n)
		return n
	}
	return 0
}
