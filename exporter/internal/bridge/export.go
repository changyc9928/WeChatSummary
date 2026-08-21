package bridge

import (
	"archive/zip"
	"bytes"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"sort"
	"strconv"
	"strings"
	"time"

	"wechatsummary/exporter/internal/datdecrypt"
	"wechatsummary/exporter/internal/extract"
	"wechatsummary/exporter/internal/sqlite"
	"wechatsummary/exporter/internal/util"
)

// fmtTime formats a unix-seconds timestamp for log lines (empty for 0).
func fmtTime(unix int64) string {
	if unix <= 0 {
		return "never"
	}
	return time.Unix(unix, 0).Format("2006-01-02 15:04:05")
}

// exportRequest asks the bridge to decrypt MSG.db and produce the chat export
// ZIP the WeChatSummary backend upload step accepts. includeMedia requests the
// image/emoji .dat files be resolved+decrypted into the ZIP with the relative
// path embedded in each image message's content ([图片] images/...).
type exportRequest struct {
	DBPath       string   `json:"dbPath"`
	Key          string   `json:"key"`
	IncludeMedia bool     `json:"includeMedia"`
	AccountDir   string   `json:"accountDir"` // optional override for media lookup
	XorKey       string   `json:"xorKey"`     // hex; auto from templates when empty
	AesKey       string   `json:"aesKey"`     // 16 ASCII chars; auto from DLL when empty
	DLLPath      string   `json:"dllPath"`    // optional key-tool DLL for auto AES recovery
	Tables       []string `json:"tables"`     // optional: msg table names to export (empty = all)
	From         int64    `json:"from"`       // optional: include messages at/after this unix time
	To           int64    `json:"to"`         // optional: include messages at/before this unix time
	OutDir       string   `json:"outDir"`     // optional: directory to write the zip into
}

// exportResult carries the export ZIP plus diagnostics. For large exports the
// ZIP is written to disk and ZipPath is returned (zipBase64 is omitted to
// avoid base64-bloating the JSON body); small exports still inline base64.
type exportResult struct {
	FileName     string   `json:"fileName"`
	ZipPath      string   `json:"zipPath,omitempty"`
	ZipSize      int64    `json:"zipSize,omitempty"`
	ZipBase64    string   `json:"zipBase64,omitempty"`
	MessageCount int      `json:"messageCount"`
	Mode         string   `json:"mode"`
	Table        string   `json:"table"`
	Columns      []string `json:"columns"`
	Nickname     string   `json:"nickname"`
	MediaCount   int      `json:"mediaCount"` // image files saved into the zip
	MediaFailed  int      `json:"mediaFailed"`
	VoiceCount   int      `json:"voiceCount,omitempty"` // voice WAVs saved into the zip
	VoiceFailed  int      `json:"voiceFailed,omitempty"`
	Shards       int      `json:"shards"` // message-DB shards merged into this export
	AccountDir   string   `json:"accountDir,omitempty"`
	MediaReason  string   `json:"mediaReason,omitempty"`
	Warnings     []string `json:"warnings,omitempty"` // e.g. WeChat running → WAL masked data
}

// maxInlineZipBytes: above this the zip is written to disk instead of base64.
const maxInlineZipBytes = 64 << 20 // 64 MiB

// handleExport decrypts MSG.db with the verified key and returns the chat
// export ZIP (messages.json inside, media embedded when requested) — as
// base64 for small exports, or written to disk with the path returned for
// large ones. dbPath may be omitted when a unique database can be discovered.
func (s *server) handleExport(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		s.fail(w, http.StatusMethodNotAllowed, "method_not_allowed", "use POST")
		return
	}
	var req exportRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		s.fail(w, http.StatusBadRequest, "bad_request", "invalid JSON body: %v", err)
		return
	}
	dbPath := req.DBPath
	if dbPath == "" {
		roots := s.cfg.DBRoots
		if len(roots) == 0 {
			roots = defaultDBRoots()
		}
		found, derr := findWeChatDBs(roots)
		if derr != nil {
			s.fail(w, http.StatusInternalServerError, "db_search_failed", "%v", derr)
			return
		}
		if len(found) == 0 {
			s.fail(w, http.StatusBadRequest, "no_database", "no WeChat database found; provide dbPath")
			return
		}
		bridgeLog.Add("info", "export: discovered DB %s", displayDir(found[0]))
		dbPath = found[0]
	}
	if req.Key == "" {
		s.fail(w, http.StatusBadRequest, "bad_request", "key is required")
		return
	}
	secret, _, _, kerr := util.ParseKeyInput(req.Key)
	if kerr != nil {
		s.fail(w, http.StatusBadRequest, "bad_key", "cannot parse key: %v", kerr)
		return
	}
	files, dbs, oerr := openMsgShards(dbPath, secret)
	if oerr != nil {
		s.fail(w, http.StatusBadRequest, "bad_key", "cannot open database (wrong key?): %v", oerr)
		return
	}
	defer func() {
		for _, f := range files {
			f.Close()
		}
	}()
	opts := exportMediaOptions{
		Enabled:    req.IncludeMedia,
		AccountDir: strings.TrimSpace(req.AccountDir),
		XorKey:     strings.TrimSpace(req.XorKey),
		AesKey:     strings.TrimSpace(req.AesKey),
		DLLPath:    strings.TrimSpace(req.DLLPath),
		cfg:        s.cfg,
	}
	if cn, ok := s.cachedSessionNames(accountDirOf(dbPath), dbPath, secret); ok {
		opts.CachedNames = cn
	}
	sel := exportSelection{
		Tables: req.Tables,
		From:   req.From,
		To:     req.To,
		OutDir: strings.TrimSpace(req.OutDir),
	}
	res, berr := buildExportOptsShards(dbs, dbPath, secret, opts, sel)
	if berr != nil {
		s.fail(w, http.StatusUnprocessableEntity, "export_failed", "%v", berr)
		return
	}
	res.Warnings = walWarning(dbPath, accountDirOf(dbPath))
	res.Mode = files[0].Mode().String()
	res.Shards = len(dbs)
	if res.ZipPath != "" {
		s.setLastExport(res.ZipPath, res.ZipSize)
	}

	if res.ZipPath != "" {
		bridgeLog.Add("info", "export: zip kept on disk (%d bytes): %s", res.ZipSize, res.ZipPath)
	}
	bridgeLog.Add("info", "export: done mediaCount=%d mediaFailed=%d voiceCount=%d voiceFailed=%d zip=%dB path=%s",
		res.MediaCount, res.MediaFailed, res.VoiceCount, res.VoiceFailed, res.ZipSize, res.ZipPath)
	s.writeJSON(w, http.StatusOK, Envelope{Ok: true, Data: res})
}

// exportMediaOptions controls media inclusion in an export run.
type exportMediaOptions struct {
	Enabled    bool
	AccountDir string
	XorKey     string
	AesKey     string
	DLLPath    string
	cfg        Config
	// CachedNames, when non-nil, is the memoized session-name map for this
	// account (from the sessions-list cache). When non-nil it replaces the
	// per-export resolveSessionNames call that re-opens session.db/contact.db
	// and re-reads every name on every export request.
	CachedNames map[string]sessionName
}

// exportSelection narrows an export to a subset of chat tables and/or a
// createTime window (unix seconds; 0 = unbounded), and may force the ZIP to
// disk into OutDir.
type exportSelection struct {
	Tables []string
	From   int64
	To     int64
	OutDir string
}

// buildExport is the media-less, no-filter variant, kept for existing
// callers/tests.
func buildExport(db *sqlite.DB, dbPath string) (exportResult, error) {
	return buildExportOpts(db, dbPath, exportMediaOptions{}, exportSelection{})
}

// tableRange binds one chat-log table (in one shard) to its index span in the
// merged message slice, so media resolution can process each table's messages
// exactly once while mutating the slice messages.json marshals (the previous
// all-tables × all-messages loop was O(T*M)).
type tableRange struct {
	mt        extract.MessageTable
	start, en int
}

// buildExportOpts extracts messages from an already-open decrypted database
// and produces the export ZIP (messages.json at the ZIP root; image media
// embedded when opts.Enabled). Merges every selected chat-log table (4.1
// keeps one msg_<md5> table per conversation; 4.0 has a single MSG table),
// optionally restricted by selection (table names + createTime window).
// dbPath is used for account-dir discovery only (media lookup).
func buildExportOpts(db *sqlite.DB, dbPath string, opts exportMediaOptions, sel exportSelection) (exportResult, error) {
	return buildExportOptsShards([]*sqlite.DB{db}, dbPath, nil, opts, sel)
}

// buildExportOptsShards is buildExportOpts over several message-DB shards.
// WeChat 4.1 splits one account's chat history across message_0.db,
// message_1.db, ... holding the SAME Msg_<md5> tables in each shard (each a
// different time slice), so a complete export must merge every shard.
func buildExportOptsShards(dbs []*sqlite.DB, dbPath string, secret []byte, opts exportMediaOptions, sel exportSelection) (exportResult, error) {
	if len(dbs) == 0 {
		return exportResult{}, fmt.Errorf("no database shards to export")
	}
	// First DB's column shape is used for the result columns.
	var colsFor []string

	// Collect tables per shard, filter by selection (union across shards).
	var all []extract.MessageTable
	tableNamesSet := map[string]bool{}
	for _, db := range dbs {
		tables, err := extract.FindMessageTables(db)
		if err != nil {
			return exportResult{}, err
		}
		for _, mt := range tables {
			all = append(all, mt)
			tableNamesSet[mt.Table.Name()] = true
			if len(colsFor) == 0 {
				colsFor = mt.Columns
			}
		}
	}
	if len(all) == 0 {
		return exportResult{}, fmt.Errorf("no chat-log table found in any database shard")
	}
	if len(sel.Tables) > 0 {
		want := map[string]bool{}
		for _, t := range sel.Tables {
			want[t] = true
		}
		bridgeLog.Add("info", "export: selection requests %d table name(s): %s", len(sel.Tables), strings.Join(sel.Tables, ", "))
		var kept []extract.MessageTable
		for _, mt := range all {
			if want[mt.Table.Name()] {
				kept = append(kept, mt)
			}
		}
		if len(kept) == 0 {
			var have []string
			for n := range tableNamesSet {
				have = append(have, n)
			}
			sort.Strings(have)
			return exportResult{}, fmt.Errorf("no selected chat tables matched; database has: %s", strings.Join(have, ", "))
		}
		bridgeLog.Add("info", "export: %d of %d requested table(s) found across shards", len(kept), len(sel.Tables))
		all = kept
	}
	name2id := mergeName2Id(dbs)

	// Extract per (shard, table) so the media loop can process each table's
	// own rows (the previous all-tables x all-messages loop was O(T*M)), then
	// merge into one slice with per-table index ranges so media mutation lands
	// in the same slice that messages.json marshals.
	var msgs []extract.Message
	var ranges []tableRange
	for _, mt := range all {
		part, err := extract.ExtractMessagesRange(mt.Table, mt.Columns, 0, name2id, sel.From, sel.To)
		if err != nil {
			return exportResult{}, fmt.Errorf("table %s: %v", mt.Table.Name(), err)
		}
		if len(part) == 0 {
			continue
		}
		ranges = append(ranges, tableRange{mt: mt, start: len(msgs), en: len(msgs) + len(part)})
		msgs = append(msgs, part...)
	}
	if len(msgs) == 0 {
		return exportResult{}, fmt.Errorf("no messages matched the selection (tables=%v from=%d to=%d)", sel.Tables, sel.From, sel.To)
	}

	res := exportResult{
		FileName:   "wechat-chat-export.zip",
		Nickname:   inferExportNickname(dbPath),
		AccountDir: opts.AccountDir,
	}
	if opts.AccountDir == "" {
		res.AccountDir = accountDirOf(dbPath)
	}
	// Chat name parity with CipherTalk: the zip's session block carries the
	// resolved display name (session.db + contact.db via resolveSessionNames),
	// not the enclosing DB-directory placeholder ("message" for message_0.db).
	// The sessions list already uses the same resolution, so the export now
	// names the chat exactly as the UI does.
	var exportTables []string
	for _, mt := range all {
		exportTables = append(exportTables, mt.Table.Name())
	}
	sort.Strings(exportTables)
	var names map[string]sessionName
	// Prefer the memoized map (sessions-list cache): resolveSessionNames opens
	// session.db + contact.db and reads every name; on repeat exports the file
	// fingerprint is unchanged, so the cached map is free.
	if len(opts.CachedNames) > 0 {
		names = opts.CachedNames
		bridgeLog.Add("debug", "export: session names from cache (%d table(s))", len(exportTables))
	} else if n := resolveSessionNames(res.AccountDir, secret, dbs, exportTables); len(n) > 0 {
		names = n
		preferred := ""
		for _, t := range exportTables {
			if sn := names[t]; sn.Display != "" {
				preferred = sn.Display
				break
			}
		}
		if preferred == "" {
			for _, t := range exportTables {
				if sn := names[t]; sn.ID != "" {
					preferred = sn.ID
					break
				}
			}
		}
		if preferred != "" {
			res.Nickname = preferred
		}
	}

	// Voice matching candidates: the resolved session id(s) of the exported
	// tables (the actual wxid/chatroom, not the md5 table-name hash) — the
	// VoiceInfo chat_name_id column joins via Name2Id rowid. CipherTalk uses
	// [sessionId] + myWxid; resolved names give sessionId, and the account's
	// wxid is the fallback myWxid.
	var sessionCands []string
	{
		seen := map[string]bool{}
		if names != nil {
			for _, t := range exportTables {
				if sn := names[t]; sn.ID != "" && !seen[sn.ID] {
					seen[sn.ID] = true
					sessionCands = append(sessionCands, sn.ID)
				}
			}
		}
		if wxid := wxidTokenFromAccountDir(res.AccountDir); wxid != "" && !seen[wxid] {
			seen[wxid] = true
			sessionCands = append(sessionCands, wxid)
		}
	}

	var buf bytes.Buffer
	zw := zip.NewWriter(&buf)

	// Normalize first (Content -> bracket tokens / readable text); the media
	// loop then appends the resolved relative path for media it could decrypt.
	// RawContent is preserved from extraction, so md5 lookup still sees the
	// original XML. CipherTalk 1:1: type names come from the XML <type> tag
	// (聊天记录/引用消息/链接/文件/小程序/转账/红包/群公告/微信礼物/音乐),
	// content is parsed via parseType49/parseMessageContent (no more raw appmsg
	// XML or "wxid_xxx:\n" sender prefixes), and each message carries
	// senderDisplayName resolved from the contact DB.
	senderNames := loadContactNames(res.AccountDir, secret)
	normalizeExportMessages(msgs, senderNames)
	if opts.Enabled {
		me := resolveMediaExport(dbPath, opts, secret)
		res.MediaReason = me.reason
		bridgeLog.Add("info", "export media: accountDir=%s xor=0x%02x aesLen=%d %s",
			displayDir(me.accountDir), me.xorKey, len(me.aesKey), me.reason)
		saved, failed := exportMediaPerTable(zw, me.accountDir, msgs, ranges, me)
		res.MediaCount, res.MediaFailed = saved, failed
		bridgeLog.Add("info", "export media: found=%d saved=%d failed=%d skipped=%d",
			me.found, saved, failed, me.skipped)
		// Voice export (CipherTalk parity, LocalType 34): reads VoiceInfo%
		// tables from the opened message shards, decodes the SILK blobs to
		// 24 kHz WAV, writes voices/<date>/<ts>_<localId>.wav and rewrites
		// the message content to "[语音消息] voices/...".
		vsaved, vfailed := exportVoice(zw, dbPath, secret, dbs, msgs, sessionCands)
		res.VoiceCount, res.VoiceFailed = vsaved, vfailed
		bridgeLog.Add("info", "export voice: found=%d saved=%d failed=%d", int32(vsaved)+int32(vfailed), vsaved, vfailed)
	}

	doc := extract.BuildExport(msgs, res.Nickname)
	b, err := extract.Marshal(doc)
	if err != nil {
		return exportResult{}, err
	}
	// The zip's JSON entry is named after the resolved chat display name
	// (CipherTalk parity: "<safeName>.json"), so the uploaded dataset shows
	// the chat's real name instead of the generic "messages.json" placeholder.
	// The backend's ZipExtractionService finds the first *.json regardless of
	// name, so this is purely cosmetic — but it matches what the user sees in
	// the DatasetSelector title.
	jsonEntry := sanitizeJSONEntryName(res.Nickname)
	if err := writeZipEntry(zw, jsonEntry, b); err != nil {
		return exportResult{}, fmt.Errorf("zip %s: %v", jsonEntry, err)
	}
	if err := zw.Close(); err != nil {
		return exportResult{}, err
	}

	res.MessageCount = len(msgs)
	bridgeLog.Add("info", "export: summary messages=%d tables=%d from=%d to=%d", res.MessageCount, len(all), sel.From, sel.To)
	var tableNames []string
	for _, mt := range all {
		tableNames = append(tableNames, mt.Table.Name())
	}
	sort.Strings(tableNames)
	res.Table = strings.Join(tableNames, ", ")
	res.Columns = colsFor

	// Delivery: inline base64 for small exports; write to disk otherwise (or
	// always to disk when the caller requested an outDir).
	zipBytes := buf.Bytes()
	forceDisk := sel.OutDir != ""
	if int64(len(zipBytes)) <= maxInlineZipBytes && !forceDisk {
		res.ZipBase64 = base64.StdEncoding.EncodeToString(zipBytes)
	} else {
		outDir := strings.TrimSpace(sel.OutDir)
		if outDir == "" {
			outDir = strings.TrimSpace(opts.cfg.ExportDir)
		}
		if outDir == "" {
			outDir, _ = os.Getwd()
		}
		if outDir == "" {
			outDir, _ = os.UserHomeDir()
		}
		if err := os.MkdirAll(outDir, 0o755); err != nil {
			return exportResult{}, fmt.Errorf("cannot create export dir %s: %v", outDir, err)
		}
		stamp := timeNow().Format("20060102-150405")
		path := filepath.Join(outDir, fmt.Sprintf("wechat-chat-export-%s.zip", stamp))
		if err := os.WriteFile(path, zipBytes, 0o644); err != nil {
			return exportResult{}, fmt.Errorf("cannot write zip: %v", err)
		}
		res.ZipPath = path
		res.ZipSize = int64(len(zipBytes))
	}
	bridgeLog.Add("info", "export: %d messages from %d table(s) into %s", len(msgs), len(tableNames), res.FileName)
	return res, nil
}

// normalizeExportMessages converts the raw message rows to the display shape
// the backend expects — numeric localType -> Chinese type name (with the
// CipherTalk XML <type> override for appmsg rows), content -> readable text
// (bracket tokens for media, parseType49 for appmsg, no sender prefix), and
// senderDisplayName resolved from the contact DB (falling back to the raw
// username, which the backend's buildUserMap then shows as-is).
func normalizeExportMessages(msgs []extract.Message, senderNames map[string]string) {
	for i := range msgs {
		m := &msgs[i]
		m.Type = messageTypeName(m.LocalType, m.RawContent)
		switch m.LocalType {
		case 3:
			m.Content = "[图片]"
		case 34:
			m.Content = "[语音消息]"
		case 43:
			m.Content = "[视频]"
		case 47:
			m.Content = "[动画表情]"
		default:
			m.Content = parseMessageContent(m.RawContent, m.LocalType)
		}
		if senderNames != nil {
			if d, ok := senderNames[m.SenderUsername]; ok && d != "" {
				m.SenderDisplayName = d
			}
		}
	}
}

// mediaExportRuntime is the resolved media-decrypt context for one export.
type mediaExportRuntime struct {
	accountDir string
	secret     []byte
	xorKey     byte
	haveXor    bool
	aesKey     []byte
	reason     string
	found      int
	saved      int
	failed     int
	skipped    int

	// datIndex is CipherTalk's full msg/attach .dat index, built lazily once
	// per export on the first cache-key miss (buildFullDatIndex).
	datIndex      *fullDatIndex
	datIndexBuilt bool

	// packedDiag counts how many image-row packed-name diagnostics have been
	// logged (bounded to avoid log spam) — shows the packed dat base for real
	// image rows so we can confirm the on-disk-name mapping in 4.1.12.26.
	packedDiag int

	// emojiFetch memoizes emoji fetch results per export run, keyed by the
	// CipherTalk cacheKey (md5 or hashString(cdnUrl)); nil values mean the
	// whole chain missed and must not be retried for later messages.
	emojiFetch map[string][]byte

	// videoIndex lists every candidate video file under msg/video/ (base name,
	// dir, absolute path, size), built lazily once per export on first lookup
	// and reused for the size+md5 fallback scan (CipherTalk's videoSizeIndex,
	// 60s TTL cache).
	videoIndex      []videoFileEntry
	videoIndexBuilt bool
}

// videoFileEntry is one on-disk video candidate under msg/video/<month>/.
type videoFileEntry struct {
	base string // on-disk base name (e.g. "4ac95565...._raw.mp4")
	dir  string // month directory name (YYYY-MM or YYYYMM)
	path string // absolute path
	size int64  // file size in bytes
}

// resolveMediaExport determines the account dir and the XOR/AES media keys for
// an export run: manual request overrides > bridge defaults > auto-derivation
// (XOR from _t.dat templates; AES via the key-tool DLL from WeChat memory).
func resolveMediaExport(dbPath string, opts exportMediaOptions, secret []byte) *mediaExportRuntime {
	rt := &mediaExportRuntime{accountDir: opts.AccountDir, secret: secret}
	if rt.accountDir == "" {
		rt.accountDir = accountDirOf(dbPath)
	}
	var notes []string

	// XOR key
	haveXor, xorKey, note := resolveXorKey(rt.accountDir, opts)
	rt.haveXor, rt.xorKey = haveXor, xorKey
	if note != "" {
		notes = append(notes, note)
	}

	// AES key
	aes, anote := resolveAesKey(rt.accountDir, opts, rt.haveXor, rt.xorKey)
	rt.aesKey = aes
	if anote != "" {
		notes = append(notes, anote)
	}
	rt.reason = strings.Join(notes, "; ")
	return rt
}

// resolveXorKey picks the XOR key: explicit hex > bridge default > templates.
func resolveXorKey(accountDir string, opts exportMediaOptions) (bool, byte, string) {
	if v := strings.TrimSpace(opts.XorKey); v != "" {
		if kv, err := strconv.ParseUint(v, 16, 8); err == nil {
			// The frontend echoes the auto-recovered key back as an explicit
			// value; verify it against the _t.dat templates so the summary
			// reads "verified" instead of a confusing "manual".
			note := "xor=manual"
			if files, _ := findTemplateDatFiles(accountDir); len(files) > 0 {
				if k, ok := xorKeyFromTemplateFiles(files); ok && k == byte(kv) {
					note = "xor=verified"
				}
			}
			return true, byte(kv), note
		}
	}
	if v := strings.TrimSpace(opts.cfg.MediaXorKey); v != "" {
		if kv, err := strconv.ParseUint(v, 16, 8); err == nil {
			return true, byte(kv), "xor=config"
		}
	}
	files, _ := findTemplateDatFiles(accountDir)
	if k, ok := xorKeyFromTemplateFiles(files); ok {
		return true, k, fmt.Sprintf("xor=templates(%d)", len(files))
	}
	return false, 0, "xor=auto:none"
}

// resolveAesKey picks the AES key: explicit string > bridge default > the
// key-tool DLL scan of WeChat memory (needs V2 templates + running WeChat).
//
// Manual/config keys are used verbatim (the user may know a key that no
// template matches), but they are verified against the V2 templates when any
// exist so the log labels them trusted or not: a key that fails STRONG
// template verification decrypts every image to garbage, and the export
// accept-path (LooksLikeStrongMedia) then rejects all of them — surfacing it
// here as aes=*UNVERIFIED* beats a wall of "no .dat found".
func resolveAesKey(accountDir string, opts exportMediaOptions, haveXor bool, xorKey byte) ([]byte, string) {
	if v := strings.TrimSpace(opts.AesKey); len(v) >= 16 {
		aes := datdecrypt.AsciiKey16(v)
		if files, _ := findTemplateDatFiles(accountDir); len(files) > 0 {
			if ok, how := verifyAesKey(aes, files); ok {
				return aes, "aes=manual(verified " + how + ")"
			}
			return aes, "aes=manual UNVERIFIED against " + itoa(len(files)) + " template(s) — image decrypts may fail; garbage is rejected by the export accept-path"
		}
		return aes, "aes=manual (no V2 templates to verify against)"
	}
	if v := strings.TrimSpace(opts.cfg.MediaAesKey); len(v) >= 16 {
		aes := datdecrypt.AsciiKey16(v)
		if files, _ := findTemplateDatFiles(accountDir); len(files) > 0 {
			if ok, how := verifyAesKey(aes, files); ok {
				return aes, "aes=config(verified " + how + ")"
			}
			return aes, "aes=config UNVERIFIED against " + itoa(len(files)) + " template(s)"
		}
		return aes, "aes=config (no V2 templates to verify against)"
	}
	if !haveXor && xorKey == 0 {
		return nil, "aes=none(no xor)"
	}
	files, _ := findTemplateDatFiles(accountDir)
	key, attempts, serr := findImageAesKeyInMemory(accountDir, files, func(m string) { bridgeLogDebug("export media: %s", m) })
	if serr != nil {
		return nil, "aes=auto:scan failed (" + serr.Error() + ")"
	}
	if key == "" {
		return nil, "aes=memory:none (attempts=" + itoa(attempts) + "). Open a chat with images in WeChat so the image key is in memory, then retry."
	}
	aes := datdecrypt.AsciiKey16(key)
	if ok, how := verifyAesKey(aes, files); ok {
		return aes, "aes=memory(verified " + how + ")"
	}
	return aes, "aes=memory(unverified, attempts=" + itoa(attempts) + ")"
}

// handleExportDownload streams the ZIP produced by the most recent export
// (GET /api/export/download). Only that one file is served — far safer than
// accepting an arbitrary path.
func (s *server) handleExportDownload(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		s.fail(w, http.StatusMethodNotAllowed, "method_not_allowed", "use GET")
		return
	}
	s.mu.Lock()
	path := s.lastExportZip
	size := s.lastExportZipSize
	s.mu.Unlock()
	if path == "" {
		s.fail(w, http.StatusNotFound, "no_export", "no export has been produced yet")
		return
	}
	fi, err := os.Stat(path)
	if err != nil || !fi.Mode().IsRegular() {
		s.fail(w, http.StatusNotFound, "no_export", "exported zip no longer exists")
		return
	}
	f, err := os.Open(path)
	if err != nil {
		s.fail(w, http.StatusInternalServerError, "download_failed", "cannot open zip: %v", err)
		return
	}
	defer f.Close()
	w.Header().Set("Content-Type", "application/zip")
	w.Header().Set("Content-Disposition", fmt.Sprintf(`attachment; filename="%s"`, filepath.Base(path)))
	w.Header().Set("Content-Length", fmt.Sprintf("%d", size))
	w.WriteHeader(http.StatusOK)
	io.Copy(w, f)
	bridgeLog.Add("info", "export download: served %s (%d bytes)", filepath.Base(path), size)
}

// setLastExport records the most recently written export ZIP (for
// /api/export/download). Guarded by the server mutex.
func (s *server) setLastExport(path string, size int64) {
	s.mu.Lock()
	s.lastExportZip = path
	s.lastExportZipSize = size
	s.mu.Unlock()
}

// timeNow returns the current wall-clock time (indirection keeps the export
// path testable).
func timeNow() time.Time { return time.Now() }

// zipMessages is retained for compatibility with older callers.
func zipMessages(jsonBytes []byte) ([]byte, error) {
	var buf bytes.Buffer
	zw := zip.NewWriter(&buf)
	if err := writeZipEntry(zw, "messages.json", jsonBytes); err != nil {
		return nil, err
	}
	if err := zw.Close(); err != nil {
		return nil, err
	}
	return buf.Bytes(), nil
}

// inferExportNickname uses the enclosing directory name (usually the wxid).
func inferExportNickname(dbPath string) string {
	dir := filepath.Base(filepath.Dir(dbPath))
	if dir != "" && dir != "." && !strings.EqualFold(dir, "msg") {
		return dir
	}
	return filepath.Base(dbPath)
}

// sanitizeJSONEntryName builds the zip entry name for the exported JSON from
// the resolved chat display name, mirroring CipherTalk's safeName handling
// (illegal Windows path characters -> '_', trailing dots trimmed). Produces
// "<name>.json" or falls back to "messages.json" when the name is unusable.
func sanitizeJSONEntryName(displayName string) string {
	repl := strings.NewReplacer("<", "_", ">", "_", ":", "_", "\"", "_", "/", "_", "\\", "_", "|", "_", "?", "_", "*", "_")
	safe := strings.TrimSpace(repl.Replace(displayName))
	safe = strings.TrimRight(safe, ".")
	if safe == "" {
		return "messages.json"
	}
	return safe + ".json"
}

// handleExportSessions lists every chat-log table with message count and the
// createTime range, so the UI can offer per-chat selection + date filters.
func (s *server) handleExportSessions(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		s.fail(w, http.StatusMethodNotAllowed, "method_not_allowed", "use POST")
		return
	}
	var req struct {
		DBPath string `json:"dbPath"`
		Key    string `json:"key"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		s.fail(w, http.StatusBadRequest, "bad_request", "invalid JSON body: %v", err)
		return
	}
	dbPath := req.DBPath
	if dbPath == "" {
		found, derr := findWeChatDBs(s.cfg.DBRoots)
		if derr != nil {
			s.fail(w, http.StatusInternalServerError, "db_search_failed", "%v", derr)
			return
		}
		if len(found) == 0 {
			s.fail(w, http.StatusBadRequest, "no_database", "no WeChat database found; provide dbPath")
			return
		}
		dbPath = found[0]
	}
	secret, _, _, kerr := util.ParseKeyInput(req.Key)
	if kerr != nil {
		s.fail(w, http.StatusBadRequest, "bad_key", "cannot parse key: %v", kerr)
		return
	}
	files, dbs, oerr := openMsgShards(dbPath, secret)
	if oerr != nil {
		s.fail(w, http.StatusBadRequest, "bad_key", "cannot open database (wrong key?): %v", oerr)
		return
	}
	defer func() {
		for _, f := range files {
			f.Close()
		}
	}()
	accountDir := accountDirOf(dbPath)
	ck := sessionsCacheKey(accountDir, dbPath, secret)
	s.mu.Lock()
	if ent, ok := s.sessionsCache[ck]; ok {
		s.mu.Unlock()
		bridgeLog.Add("info", "export sessions: cache hit (%d chat tables, key %s)", len(ent.sessions), ck[:12])
		s.writeJSON(w, http.StatusOK, Envelope{Ok: true, Data: map[string]any{
			"dbPath":   dbPath,
			"sessions": ent.sessions,
			"warnings": walWarning(dbPath, accountDir),
		}})
		return
	}
	s.mu.Unlock()
	sessions, names, serr := listSessionsShards(dbs, accountDir, secret)
	if serr != nil {
		s.fail(w, http.StatusUnprocessableEntity, "export_failed", "%v", serr)
		return
	}
	bridgeLog.Add("info", "export sessions: %d chat tables across %d shard(s) under %s", len(sessions), len(dbs), displayDir(dbPath))
	s.mu.Lock()
	s.sessionsCache[ck] = sessionsCacheEntry{sessions: sessions, names: names}
	s.mu.Unlock()
	s.writeJSON(w, http.StatusOK, Envelope{Ok: true, Data: map[string]any{
		"dbPath":   dbPath,
		"sessions": sessions,
		"warnings": walWarning(dbPath, accountDir),
	}})
}

// sessionInfo summarizes one chat-log table for the session picker. Name is
// the resolved chat name (session id or contact display name); Table remains
// the Msg_<md5> table name used for export selection.
type sessionInfo struct {
	Table     string `json:"table"`
	Name      string `json:"name,omitempty"`
	SessionID string `json:"sessionId,omitempty"`
	Count     int64  `json:"count"`
	MinTime   int64  `json:"minTime,omitempty"`
	MaxTime   int64  `json:"maxTime,omitempty"`
}

// listSessionsShards summarizes every chat-log table across all opened shards
// (count + createTime range, merged per table name), and resolves the
// Msg_<md5> table name to the chat name where possible. Also returns the
// name-resolution map (reused by exports so the session.db/contact reads are
// not repeated per request).
func listSessionsShards(dbs []*sqlite.DB, accountDir string, secret []byte) ([]sessionInfo, map[string]sessionName, error) {
	// Merge per table name across shards: counts sum, time ranges widen.
	merged := map[string]*sessionInfo{}
	shardIdx := 0
	var order []string
	for _, db := range dbs {
		shardIdx++
		tables, err := extract.FindMessageTables(db)
		if err != nil {
			return nil, nil, err
		}
		// Per-shard diagnostic: how many tables, and the newest createTime in
		// this shard. If the newest shard's max is stale vs. the list, the
		// shard is missing/not being merged.
		shardMax, shardTables := int64(0), 0
		for _, mt := range tables {
			name := mt.Table.Name()
			shardTables++
			si := merged[name]
			if si == nil {
				si = &sessionInfo{Table: name}
				merged[name] = si
				order = append(order, name)
			}
			ix := extract.TimeColumnIndex(mt.Columns)
			cnt, minT, maxT, cerr := mt.Table.CountAndTimeRange(ix)
			if cerr != nil {
				return nil, nil, fmt.Errorf("table %s: %v", name, cerr)
			}
			si.Count += cnt
			if minT > 0 && (si.MinTime == 0 || minT < si.MinTime) {
				si.MinTime = minT
			}
			if maxT > si.MaxTime {
				si.MaxTime = maxT
			}
			if maxT > shardMax {
				shardMax = maxT
			}
		}
		bridgeLog.Add("info", "sessions: shard #%d: %d table(s), newest message at %d (%s)", shardIdx, shardTables, shardMax, fmtTime(shardMax))
	}
	sort.Strings(order)
	names := resolveSessionNames(accountDir, secret, dbs, order)
	out := make([]sessionInfo, 0, len(order))
	for _, name := range order {
		si := merged[name]
		if sn, ok := names[name]; ok {
			si.SessionID = sn.ID
			if sn.Display != "" {
				si.Name = sn.Display
			} else {
				si.Name = sn.ID
			}
		}
		if si.Name == "" {
			si.Name = si.Table
		}
		out = append(out, *si)
	}
	// Sort by latest message time descending (most recently active chats
	// first); ties fall back to chat name, then table name.
	sort.SliceStable(out, func(i, j int) bool {
		ti, tj := out[i].MaxTime, out[j].MaxTime
		if ti != tj {
			return ti > tj
		}
		if out[i].Name != out[j].Name {
			return out[i].Name < out[j].Name
		}
		return out[i].Table < out[j].Table
	})
	return out, names, nil
}
