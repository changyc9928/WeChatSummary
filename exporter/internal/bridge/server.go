// Package bridge exposes the WeChat key workflows as a small HTTP API bound
// to the loopback interface, so the browser frontend can drive a native
// memory scan without ever touching OS memory itself.
//
// Security model:
//   - The server only binds 127.0.0.1 (enforced in cmd/bridge; a non-loopback
//     address is refused).
//   - CORS is allowlisted; by default only local dev origins pass.
//   - An optional bearer token can be required for every request.
//   - Scan results are raw byte candidates; the /api/key/validate endpoint
//     verifies candidates against a real database with a cryptographic HMAC
//     check, so a wrong or stale pattern never yields a false "key".
package bridge

import (
	"crypto/sha256"
	"encoding/binary"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"hash"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"regexp"
	"runtime"
	"sort"
	"strings"
	"sync"
	"time"

	"wechatsummary/exporter/internal/logx"
	"wechatsummary/exporter/internal/scan"
	"wechatsummary/exporter/internal/sqlcipher"
	"wechatsummary/exporter/internal/util"
)

// Version of the bridge service.
const Version = "0.1.28"

// DefaultPort used by cmd/bridge.
const DefaultPort = 8787

// Envelope is the uniform JSON response wrapper.
type Envelope struct {
	Ok    bool      `json:"ok"`
	Data  any       `json:"data,omitempty"`
	Error *APIError `json:"error,omitempty"`
}

// APIError is a machine-readable error inside an Envelope.
type APIError struct {
	Code    string `json:"code"`
	Message string `json:"message"`
}

// Config configures the bridge server.
type Config struct {
	// AllowOrigins are origins permitted by CORS (exact match).
	AllowOrigins []string
	// AllowAnyOrigin echoes whatever Origin the browser sends. Only useful
	// when the page origin is unpredictable (LAN IP / hostname). The service
	// is loopback-bound, so the exposure is limited to pages running on this
	// machine — but any such page can then read scan/validate results.
	AllowAnyOrigin bool
	// Token, when non-empty, requires "Authorization: Bearer <token>".
	Token string
	// DBRoots override the default WeChat DB search roots (for /api/dbs and
	// empty dbPath in /api/key/validate).
	DBRoots []string
	// ExtraPatterns are appended to the default memory-scan patterns.
	ExtraPatterns []scan.Pattern
	// KeyToolPath points at CipherTalk's wechat_key_tool.dll for the
	// /api/key/tool flow (WeChat 4.1.x key recovery).
	KeyToolPath string
	// MediaXorKey / MediaAesKey supply the media-decrypt defaults when the
	// keys cannot be discovered (xor: 0-255 hex; aes: 16 ASCII chars).
	MediaXorKey string
	// LogLevel is the minimum level stored in the bridge log ring
	// (debug|info|warn|error; default info). debug adds scan progress detail
	// to the frontend log panel.
	LogLevel string
	// MediaAesKey is the 16-ASCII-char image dat AES key fallback.
	MediaAesKey string
	// ExportDir is where large export ZIPs are written (default: process CWD).
	ExportDir string
}

type server struct {
	cfg      Config
	started  time.Time
	patterns []scan.Pattern

	mu             sync.Mutex
	lastExportZip     string
	lastExportZipSize int64

	// sessionsCache memoizes the (expensive) sessions list keyed by a
	// fingerprint of the source DB files (paths + size + mtime). The scan is
	// the slowest endpoint (observed 14m on accounts with 7 shards × ~70 chat
	// tables); WeChat rarely touches the DB files between UI requests, so a
	// whole-result cache keyed on file identity makes repeat loads instant.
	sessionsCache map[string]sessionsCacheEntry
}

type sessionsCacheEntry struct {
	sessions []sessionInfo
	names    map[string]sessionName
}

// cachedSessionNames returns the memoized session name map when the source
// files are unchanged (same fingerprint as a previous sessions-list call).
func (s *server) cachedSessionNames(accountDir, dbPath string, secret []byte) (map[string]sessionName, bool) {
	ck := sessionsCacheKey(accountDir, dbPath, secret)
	s.mu.Lock()
	defer s.mu.Unlock()
	ent, ok := s.sessionsCache[ck]
	if !ok || len(ent.names) == 0 {
		return nil, false
	}
	return ent.names, true
}

// sessionsCacheKey builds the identity fingerprint of every file the sessions
// list reads: message shards, session.db candidates and the contact DB. Any
// size/mtime change (new message, new contact) invalidates the entry.
func sessionsCacheKey(accountDir string, dbPath string, secret []byte) string {
	h := sha256.New()
	h.Write(secret)
	paths := siblingMsgDBs(dbPath)
	for _, p := range paths {
		fingerprintFile(h, p)
	}
	// session.db exact candidates (the known spots loadSessionIDs checks).
	for _, parts := range sessionDBKnownPaths {
		fingerprintFile(h, filepath.Join(append([]string{accountDir}, parts...)...))
	}
	// contact DBs under db_storage/contact/.
	contactDir := filepath.Join(accountDir, "db_storage", "contact")
	if entries, err := os.ReadDir(contactDir); err == nil {
		for _, e := range entries {
			if e.IsDir() {
				continue
			}
			l := strings.ToLower(e.Name())
			if strings.HasPrefix(l, "contact") && strings.HasSuffix(l, ".db") {
				fingerprintFile(h, filepath.Join(contactDir, e.Name()))
			}
		}
	}
	return hex.EncodeToString(h.Sum(nil))
}

func fingerprintFile(h hash.Hash, p string) {
	fi, err := os.Stat(p)
	if err != nil {
		h.Write([]byte("x:" + p))
		return
	}
	h.Write([]byte("f:" + p + ":"))
	var sz [8]byte
	binary.BigEndian.PutUint64(sz[:], uint64(fi.Size()))
	h.Write(sz[:])
	var mt [8]byte
	binary.BigEndian.PutUint64(mt[:], uint64(fi.ModTime().UnixNano()))
	h.Write(mt[:])
}

// NewServer wires the bridge HTTP handlers.
func NewServer(cfg Config) http.Handler {
	s := &server{cfg: cfg, started: time.Now(), sessionsCache: map[string]sessionsCacheEntry{}}
	if cfg.LogLevel != "" {
		bridgeLog.SetMinLevel(strings.ToLower(strings.TrimSpace(cfg.LogLevel)))
	}
	s.patterns = append(s.patterns, scan.DefaultPatterns...)
	s.patterns = append(s.patterns, cfg.ExtraPatterns...)
	bridgeLog.Add("info",
		"wechat-key-bridge v%s started (log level: %s, scan supported: %v, auth: %v)",
		Version, bridgeLog.MinLevel(), runtime.GOOS == "windows", cfg.Token != "")
	return s.routes()
}

func (s *server) routes() http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("/api/health", s.withCORS(s.withAuth(s.handleHealth)))
	mux.HandleFunc("/api/dbs", s.withCORS(s.withAuth(s.handleDbs)))
	mux.HandleFunc("/api/scan", s.withCORS(s.withAuth(s.handleScan)))
	mux.HandleFunc("/api/key/validate", s.withCORS(s.withAuth(s.handleValidate)))
	mux.HandleFunc("/api/key/autofind", s.withCORS(s.withAuth(s.handleAutoFind)))
	mux.HandleFunc("/api/key/tool", s.withCORS(s.withAuth(s.handleKeyTool)))
	mux.HandleFunc("/api/export", s.withCORS(s.withAuth(s.handleExport)))
	mux.HandleFunc("/api/export/sessions", s.withCORS(s.withAuth(s.handleExportSessions)))
	mux.HandleFunc("/api/export/download", s.withCORS(s.withAuth(s.handleExportDownload)))
	mux.HandleFunc("/api/media/keys", s.withCORS(s.withAuth(s.handleMediaKeys)))
	mux.HandleFunc("/api/media/decrypt", s.withCORS(s.withAuth(s.handleMediaDecrypt)))
	mux.HandleFunc("/api/media/decrypt-dir", s.withCORS(s.withAuth(s.handleMediaDecryptDir)))
	mux.HandleFunc("/api/logs", s.withCORS(s.withAuth(s.handleLogs)))
	mux.HandleFunc("/api/log/webhook", s.withCORS(s.withAuth(s.handleLogWebhook)))
	mux.HandleFunc("/api/debug/module", s.withCORS(s.withAuth(s.handleDebugModule)))
	// Outer request log: every call (except the log poll itself) is recorded
	// so the frontend panel can show exactly what the bridge did and when.
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path == "/api/logs" {
			mux.ServeHTTP(w, r)
			return
		}
		start := time.Now()
		sw := &statusWriter{ResponseWriter: w, status: http.StatusOK}
		mux.ServeHTTP(sw, r)
		bridgeLog.Add("info", "http %s %s -> %d (%v)", r.Method, r.URL.Path, sw.status,
			time.Since(start).Round(time.Millisecond))
	})
}

// statusWriter captures the response status code for request logging.
type statusWriter struct {
	http.ResponseWriter
	status int
}

func (sw *statusWriter) WriteHeader(code int) {
	sw.status = code
	sw.ResponseWriter.WriteHeader(code)
}

func (s *server) writeJSON(w http.ResponseWriter, status int, env Envelope) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.WriteHeader(status)
	if err := json.NewEncoder(w).Encode(env); err != nil {
		logx.Error("write response: %v", err)
	}
}

func (s *server) fail(w http.ResponseWriter, status int, code, format string, args ...any) {
	msg := fmt.Sprintf(format, args...)
	// The error reason used to go only into the JSON body, invisible in the
	// bridge log — every 4xx/5xx is now also logged so remote debugging
	// (paste-the-log) works for all endpoints.
	bridgeLog.Add("warn", "api error: %s: %s (HTTP %d)", code, msg, status)
	s.writeJSON(w, status, Envelope{Ok: false, Error: &APIError{Code: code, Message: msg}})
}

// ---------------------------------------------------------------------------
// Middleware

func (s *server) withCORS(next http.HandlerFunc) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		origin := r.Header.Get("Origin")
		if origin != "" && s.originAllowed(origin) {
			w.Header().Set("Access-Control-Allow-Origin", origin)
			w.Header().Set("Vary", "Origin")
			w.Header().Set("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
			w.Header().Set("Access-Control-Allow-Headers", "Authorization, Content-Type")
			w.Header().Set("Access-Control-Max-Age", "600")
			// Private Network Access: Chrome/Edge preflight requests from a
			// page served on a LAN/public origin to 127.0.0.1 and abort with
			// "Failed to fetch" unless this header comes back. Without it the
			// panel cannot reach the bridge at all.
			if r.Header.Get("Access-Control-Request-Private-Network") == "true" {
				w.Header().Set("Access-Control-Allow-Private-Network", "true")
			}
		}
		if r.Method == http.MethodOptions {
			w.WriteHeader(http.StatusNoContent)
			return
		}
		next(w, r)
	}
}

func (s *server) originAllowed(origin string) bool {
	if s.cfg.AllowAnyOrigin {
		return origin != ""
	}
	for _, o := range s.cfg.AllowOrigins {
		if o == origin {
			return true
		}
	}
	return false
}

func (s *server) withAuth(next http.HandlerFunc) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		if s.cfg.Token != "" {
			got := r.Header.Get("Authorization")
			if got != "Bearer "+s.cfg.Token {
				s.fail(w, http.StatusUnauthorized, "unauthorized", "missing or invalid bearer token")
				return
			}
		}
		next(w, r)
	}
}

// ---------------------------------------------------------------------------
// Handlers

func (s *server) handleHealth(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		s.fail(w, http.StatusMethodNotAllowed, "method_not_allowed", "use GET")
		return
	}
	s.writeJSON(w, http.StatusOK, Envelope{Ok: true, Data: map[string]any{
		"service":       "wechat-key-bridge",
		"version":       Version,
		"platform":      runtime.GOOS,
		"arch":          runtime.GOARCH,
		"scanSupported": runtime.GOOS == "windows",
		"logLevel":      bridgeLog.MinLevel(),
		"pid":           os.Getpid(),
		"uptimeSec":     int64(time.Since(s.started).Seconds()),
		"patterns":      patternNames(s.patterns),
	}})
}

func (s *server) handleDbs(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		s.fail(w, http.StatusMethodNotAllowed, "method_not_allowed", "use GET")
		return
	}
	roots := s.cfg.DBRoots
	if len(roots) == 0 {
		roots = defaultDBRoots()
	}
	found, err := findWeChatDBs(roots)
	if err != nil {
		s.fail(w, http.StatusInternalServerError, "db_search_failed", "%v", err)
		return
	}
	s.writeJSON(w, http.StatusOK, Envelope{Ok: true, Data: map[string]any{
		"roots":     roots,
		"databases": found,
	}})
}

func (s *server) handleScan(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		s.fail(w, http.StatusMethodNotAllowed, "method_not_allowed", "use GET")
		return
	}
	res, err := scan.Scan(scan.Options{Patterns: s.patterns})
	if err != nil {
		if errors.Is(err, scan.ErrUnsupportedPlatform) {
			s.fail(w, http.StatusNotImplemented, "unsupported_platform",
				"memory scanning requires a Windows build of the bridge (GOOS=windows); build with GOOS=windows go build ./cmd/bridge")
			return
		}
		s.fail(w, http.StatusInternalServerError, "scan_failed", "%v", err)
		return
	}
	s.writeJSON(w, http.StatusOK, Envelope{Ok: true, Data: res})
}

// autofindRequest is the body of POST /api/key/autofind.
type autofindRequest struct {
	DBPath string `json:"dbPath"`
}

// handleAutoFind turns memory-scan hits into a verified key without the user
// having to assemble candidates by hand:
//
//  1. resolve the database (explicit dbPath or auto-discovery)
//  2. read the database's own salt from the file header (no key needed)
//  3. scan process memory for that salt (plus the community 4.x salt anchor)
//  4. extract 32-byte key candidates from the memory windows around each hit
//  5. verify each candidate against the database — HMAC check, so a wrong or
//     stale pattern can never yield a false key
//
// The result is either a verified key or an honest "not found" with a reason.
func (s *server) handleAutoFind(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		s.fail(w, http.StatusMethodNotAllowed, "method_not_allowed", "use POST")
		return
	}
	body, err := io.ReadAll(io.LimitReader(r.Body, 1<<20))
	if err != nil {
		s.fail(w, http.StatusBadRequest, "bad_request", "cannot read body: %v", err)
		return
	}
	var req autofindRequest
	if err := json.Unmarshal(body, &req); err != nil {
		s.fail(w, http.StatusBadRequest, "bad_request", "invalid JSON body: %v", err)
		return
	}
	req.DBPath = strings.TrimSpace(req.DBPath)

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
			s.writeJSON(w, http.StatusOK, Envelope{Ok: true, Data: autoFindResult{
				Found:  false,
				DBPath: "",
				Reason: errNoDatabase.Error(),
			}})
			return
		}
		dbPath = found[0]
	}

	salt, serr := readDBSalt(dbPath)
	if serr != nil {
		s.writeJSON(w, http.StatusOK, Envelope{Ok: true, Data: autoFindResult{
			Found:  false,
			DBPath: dbPath,
			Reason: fmt.Sprintf("cannot read salt from %s: %v", dbPath, serr),
		}})
		return
	}

	// Wide windows around each salt hit: 4.1.x may keep the key further from
	// the salt than the 4.0.x adjacenct layout.
	res, sErr := scan.Scan(scan.Options{Patterns: autoFindPatterns(salt), WindowBefore: 512, WindowAfter: 512})
	if sErr != nil {
		if errors.Is(sErr, scan.ErrUnsupportedPlatform) {
			s.fail(w, http.StatusNotImplemented, "unsupported_platform",
				"memory scanning requires a Windows build of the bridge (GOOS=windows); build with GOOS=windows go build ./cmd/bridge")
			return
		}
		s.fail(w, http.StatusInternalServerError, "scan_failed", "%v", sErr)
		return
	}

	windows := collectSaltWindows(res.Hits)
	rawCands, passCands := keyCandidatesFromWindows(windows, salt, 4000)
	key, mode, verification, attempts := verifyCandidates(dbPath, rawCands, passCands)
	out := autoFindResult{
		Found:        key != "",
		Key:          key,
		Mode:         mode,
		Verification: verification,
		Salt:         hex.EncodeToString(salt),
		DBPath:       dbPath,
		Attempts:     attempts,
		Hits:         len(windows),
		SaltDumps:    collectSaltDumps(res.Hits, salt),
	}
	if out.Found {
		out.Reason = "key verified against " + dbPath
	} else {
		out.Reason = fmt.Sprintf(
			"no candidate passed the HMAC check (%d candidates tried, %d salt hits in %s). "+
				"This WeChat version may store the key differently, or the bridge ran before WeChat "+
				"was fully logged in. Provide the database path explicitly if it was not found, "+
				"or run the bridge with --patterns for this version.",
			attempts, len(windows), processSummary(res.Processes))
	}
	s.writeJSON(w, http.StatusOK, Envelope{Ok: true, Data: out})
}

func processSummary(procs []scan.ProcessInfo) string {
	if len(procs) == 0 {
		return "no WeChat process"
	}
	names := make([]string, 0, len(procs))
	for _, p := range procs {
		names = append(names, fmt.Sprintf("%s#%d", p.Name, p.PID))
	}
	return strings.Join(names, ", ")
}

// validateRequest is the body of POST /api/key/validate.
type validateRequest struct {
	Key    string `json:"key"`
	DBPath string `json:"dbPath"`
}

func (s *server) handleValidate(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		s.fail(w, http.StatusMethodNotAllowed, "method_not_allowed", "use POST")
		return
	}
	body, err := io.ReadAll(io.LimitReader(r.Body, 1<<20))
	if err != nil {
		s.fail(w, http.StatusBadRequest, "bad_request", "cannot read body: %v", err)
		return
	}
	var req validateRequest
	if err := json.Unmarshal(body, &req); err != nil {
		s.fail(w, http.StatusBadRequest, "bad_request", "invalid JSON body: %v", err)
		return
	}
	req.Key = strings.TrimSpace(req.Key)
	req.DBPath = strings.TrimSpace(req.DBPath)
	if req.Key == "" {
		s.fail(w, http.StatusBadRequest, "bad_request", "key is required")
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
			s.writeJSON(w, http.StatusOK, Envelope{Ok: true, Data: map[string]any{
				"valid":  false,
				"reason": "no WeChat database found; provide dbPath explicitly",
			}})
			return
		}
		dbPath = found[0]
	}

	secret, _, _, kerr := util.ParseKeyInput(req.Key)
	if kerr != nil {
		s.fail(w, http.StatusBadRequest, "bad_key", "cannot parse key: %v", kerr)
		return
	}

	f, oerr := sqlcipher.Open(dbPath, secret, sqlcipher.ModeRaw, false)
	if oerr != nil {
		s.writeJSON(w, http.StatusOK, Envelope{Ok: true, Data: map[string]any{
			"valid":  false,
			"reason": oerr.Error(),
			"dbPath": dbPath,
		}})
		return
	}
	defer f.Close()

	s.writeJSON(w, http.StatusOK, Envelope{Ok: true, Data: map[string]any{
		"valid":    true,
		"dbPath":   dbPath,
		"mode":     f.Mode().String(),
		"salt":     hex.EncodeToString(f.Salt()),
		"numPages": f.NumPages(),
	}})
}

// ---------------------------------------------------------------------------
// DB discovery

// defaultDBRoots returns the typical locations of WeChat data folders.
func defaultDBRoots() []string {
	var roots []string
	if ud := os.Getenv("USERPROFILE"); ud != "" {
		roots = append(roots, filepath.Join(ud, "Documents"))
		roots = append(roots, filepath.Join(ud, "OneDrive", "Documents"))
		roots = append(roots, filepath.Join(ud, "Desktop"))
	}
	if hd := os.Getenv("HOME"); hd != "" {
		roots = append(roots, filepath.Join(hd, "Documents"))
	}
	return roots
}

// findWeChatDBs walks each root (bounded depth) collecting MSG.db files that
// live under a WeChat data directory. On Windows it additionally probes every
// drive root for common WeChat data folder names (xwechat_files / WeChat
// Files / xwechat) using a name-targeted, depth-bounded walk, which finds
// installs outside the user Documents folder (custom drives, portable installs).
func findWeChatDBs(roots []string) ([]string, error) {
	seen := map[string]bool{}
	var out []string
	var err error
	for _, root := range roots {
		root = strings.TrimSpace(root)
		if root == "" {
			continue
		}
		out, err = collectMsgDBs(root, out, seen, 0, false)
		if err != nil {
			return nil, err
		}
	}
	if runtime.GOOS == "windows" {
		for _, drive := range windowsDriveRoots() {
			for _, wd := range findWeChatDataDirs(drive, 3) {
				out, err = collectMsgDBs(wd, out, seen, 8, true)
				if err != nil {
					return nil, err
				}
			}
		}
	}
	sort.Strings(out)
	return out, nil
}

// isMsgDbName reports whether a file name is a WeChat chat-log database.
// 4.0.x: MSG.db / message.db. 4.1.x: per-shard msg_0.db / message_0.db /
// biz_message_0.db (the msg_<md5> tables live inside those files).
func isMsgDbName(name string) bool {
	l := strings.ToLower(name)
	if l == "msg.db" || l == "message.db" {
		return true
	}
	return msgDbFileRe.MatchString(l)
}

var msgDbFileRe = regexp.MustCompile(`^(msg|message|biz_message)_\d+\.db$`)

// collectMsgDBs walks dir (to maxDepth; 0 = unlimited) appending chat-log
// databases living under a WeChat data directory. It returns the accumulated
// list.
func collectMsgDBs(dir string, out []string, seen map[string]bool, maxDepth int, requireWechatDir bool) ([]string, error) {
	err := filepath.WalkDir(dir, func(path string, d os.DirEntry, err error) error {
		if err != nil {
			return nil // skip unreadable entries
		}
		if d.IsDir() {
			if strings.HasPrefix(d.Name(), ".") && d.Name() != "." {
				return filepath.SkipDir
			}
			return nil
		}
		if !isMsgDbName(d.Name()) {
			return nil
		}
		if maxDepth > 0 && pathDepth(dir, path) > maxDepth {
			return nil
		}
		if !requireWechatDir {
			pl := strings.ToLower(filepath.ToSlash(path))
			if !strings.Contains(pl, "xwechat_files") && !strings.Contains(pl, "wechat files") {
				return nil
			}
		}
		abs, aerr := filepath.Abs(path)
		if aerr != nil {
			abs = path
		}
		if !seen[abs] {
			seen[abs] = true
			out = append(out, abs)
		}
		return nil
	})
	return out, err
}

// pathDepth returns how many path segments separate child from base.
func pathDepth(base, child string) int {
	rel, err := filepath.Rel(base, child)
	if err != nil {
		return 1 << 30
	}
	depth := 0
	for _, seg := range strings.Split(rel, string(filepath.Separator)) {
		if seg != "" && seg != "." {
			depth++
		}
	}
	return depth
}

// windowsDriveRoots lists existing drive roots on Windows (C:\, D:\...).
func windowsDriveRoots() []string {
	var roots []string
	for _, l := range "ABCDEFGHIJKLMNOPQRSTUVWXYZ" {
		root := string(l) + `:\`
		if _, err := os.Stat(root); err == nil {
			roots = append(roots, root)
		}
	}
	return roots
}

// skipDirNames are directory names that never contain WeChat data and are too
// heavy to walk (Windows system locations).
var skipDirNames = map[string]bool{
	"windows": true, "program files": true, "program files (x86)": true,
	"programdata": true, "$recycle.bin": true, "system volume information": true,
	"recovery": true, "perflogs": true, "msocache": true, "config.msi": true,
	"intel": true, "amd": true, "nvidia": true, "temp": true, "tmp": true,
}

// findWeChatDataDirs returns directories named like WeChat data folders
// (xwechat_files / WeChat Files / xwechat), found with a name-targeted,
// depth-bounded walk from root. Other directories are descended into only up
// to maxDepth, so the whole drive is never enumerated.
func findWeChatDataDirs(root string, maxDepth int) []string {
	var dirs []string
	var walk func(dir string, depth int)
	walk = func(dir string, depth int) {
		if depth > maxDepth {
			return
		}
		entries, err := os.ReadDir(dir)
		if err != nil {
			return
		}
		for _, e := range entries {
			if !e.IsDir() {
				continue
			}
			name := strings.ToLower(e.Name())
			if skipDirNames[name] || strings.HasPrefix(name, ".") {
				continue
			}
			full := filepath.Join(dir, e.Name())
			switch name {
			case "xwechat_files", "wechat files", "xwechat":
				dirs = append(dirs, full)
			default:
				walk(full, depth+1)
			}
		}
	}
	walk(root, 1)
	return dirs
}

func patternNames(patterns []scan.Pattern) []string {
	names := make([]string, 0, len(patterns))
	for _, p := range patterns {
		names = append(names, p.Name)
	}
	return names
}
