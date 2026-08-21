package bridge

import (
	"crypto/ed25519"
	"crypto/x509"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"strings"

	"wechatsummary/exporter/internal/sqlcipher"
)

// WeChat 4.1.x no longer keeps the SQLCipher page key directly adjacent to a
// database salt in memory the way 4.0.x did: the DB key is materialized by
// weixin.dll's keystream derivation and read out of the global_config
// in-memory structure. CipherTalk (https://github.com/ILoveBingLu/CipherTalk,
// CC BY-NC-SA 4.0) ships a closed-source scanner for exactly this layout,
// wechat_key_tool.dll. We do NOT depend on that DLL: the bridge re-implements
// the scan natively (keynative.go) as a probe-driven search — extract key
// candidates from WeChat memory and verify each one against the real
// database, so no WeChat-version offsets or external binaries are needed.
// The DLL remains available only as an explicit opt-in fallback for API
// callers that happen to have a CipherTalk install on the machine.

// keyToolAccount mirrors CipherTalk's wkt_scan_account_auth JSON output:
// a 64-hex db_key plus human-readable account fields.
type keyToolAccount struct {
	DbKey  string `json:"db_key"`
	Wxid   string `json:"wxid"`
	Name   string `json:"name"`
	Number string `json:"number"`
	Phone  string `json:"phone"`
	Seed   int    `json:"seed"`
}

// keyToolDiag mirrors CipherTalk's wkt_scan_diag_auth JSON output.
type keyToolDiag struct {
	Key        string `json:"key"`
	Auth       *bool  `json:"auth"`
	DbOK       *bool  `json:"db_ok"`
	Pids       int    `json:"pids"`
	Opened     int    `json:"opened"`
	Bytes      int64  `json:"bytes"`
	Markers    int    `json:"markers"`
	Candidates int    `json:"candidates"`
}

func parseKeyToolAccount(raw []byte) (keyToolAccount, error) {
	var a keyToolAccount
	if err := json.Unmarshal(raw, &a); err != nil {
		return a, fmt.Errorf("parse key tool account json: %v", err)
	}
	a.DbKey = strings.TrimSpace(a.DbKey)
	if len(a.DbKey) != 64 {
		return a, fmt.Errorf("key tool returned db_key of length %d (want 64 hex)", len(a.DbKey))
	}
	if _, err := hex.DecodeString(a.DbKey); err != nil {
		return a, fmt.Errorf("key tool returned non-hex db_key: %v", err)
	}
	return a, nil
}

func parseKeyToolDiag(raw []byte) (keyToolDiag, error) {
	var d keyToolDiag
	if err := json.Unmarshal(raw, &d); err != nil {
		return d, fmt.Errorf("parse key tool diag json: %v", err)
	}
	d.Key = strings.TrimSpace(d.Key)
	return d, nil
}

// keyToolPrivateKey is CipherTalk's key for signing the scan challenge
// (embedded in their repo; the DLL verifies the matching public key).
// The bytes are XOR-obfuscated in their source; reproduced verbatim.
const keyToolPrivateKeyObf = "6a74585b5a6a5f5c59713f2a5e785e7a168e0e9425838c437f0b1274d114f59457f436c936b80178da1848856b58eef3"

// keyToolKey loads CipherTalk's Ed25519 signing key from the obfuscated form.
func keyToolKey() (ed25519.PrivateKey, error) {
	der, err := hex.DecodeString(keyToolPrivateKeyObf)
	if err != nil {
		return nil, fmt.Errorf("decode key tool private key: %v", err)
	}
	for i := range der {
		der[i] ^= 0x5a
	}
	k, err := x509.ParsePKCS8PrivateKey(der)
	if err != nil {
		return nil, fmt.Errorf("parse key tool private key: %v", err)
	}
	priv, ok := k.(ed25519.PrivateKey)
	if !ok {
		return nil, errors.New("key tool private key is not Ed25519")
	}
	return priv, nil
}

// keyToolCandidates returns DLL paths to try in order: explicit config/env
// first, then common CipherTalk install locations on this machine.
func keyToolCandidates(explicit string) []string {
	var out []string
	push := func(p string) {
		p = strings.TrimSpace(p)
		if p != "" {
			out = append(out, p)
		}
	}
	push(explicit)
	push(os.Getenv("WECHAT_KEYTOOL_DLL"))
	local := os.Getenv("LOCALAPPDATA")
	if local != "" {
		push(filepath.Join(local, "Programs", "CipherTalk", "resources", "wechat_key_tool.dll"))
	}
	user := os.Getenv("USERPROFILE")
	if user != "" {
		push(filepath.Join(user, "Documents", "CipherTalk", "resources", "wechat_key_tool.dll"))
		push(filepath.Join(user, "Downloads", "CipherTalk", "resources", "wechat_key_tool.dll"))
	}
	// dedupe preserving order
	seen := map[string]bool{}
	var uniq []string
	for _, p := range out {
		if !seen[p] {
			seen[p] = true
			uniq = append(uniq, p)
		}
	}
	return uniq
}

// keyToolRequest is the body of POST /api/key/tool.
type keyToolRequest struct {
	// DLLPath points at wechat_key_tool.dll (from a CipherTalk install).
	// Empty: try the bridge --keytool-path flag, env WECHAT_KEYTOOL_DLL,
	// then common install locations.
	DLLPath string `json:"dllPath"`
	// DBPath is the chat-log database to verify the recovered key against.
	// Empty: first database discovered under the configured roots.
	DBPath string `json:"dbPath"`
}

// keyToolResult is the JSON payload of /api/key/tool.
type keyToolResult struct {
	Found        bool            `json:"found"`
	Key          string          `json:"key,omitempty"` // 64-hex db_key
	Mode         string          `json:"mode,omitempty"`
	Verification string          `json:"verification,omitempty"` // mac | magic
	DBPath       string          `json:"dbPath,omitempty"`
	Salt         string          `json:"salt,omitempty"`
	DLLPath      string          `json:"dllPath,omitempty"`
	Account      *keyToolAccount `json:"account,omitempty"`
	Attempts     int             `json:"attempts"`
	Reason       string          `json:"reason,omitempty"`
}

// keyToolLoad is the per-platform loader seam (windows: real DLL; else stub).
var keyToolLoad = loadKeyTool

// keyToolImpl is the per-platform scanner interface satisfied by keyTool on
// Windows (and a stub elsewhere).
type keyToolImpl interface {
	scanAccount() (keyToolAccount, error)
	scanDiag(contactDbPath string) (keyToolDiag, error)
	scanImageAesKey(ciphertext []byte) (string, error)
	Close()
}

// handleKeyTool recovers the WeChat 4.1.x database key via CipherTalk's
// wechat_key_tool.dll (user-supplied), then verifies it against the chat-log
// database and returns a key the Export flow can use directly.
func (s *server) handleKeyTool(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		s.fail(w, http.StatusMethodNotAllowed, "method_not_allowed", "use POST")
		return
	}
	body, err := io.ReadAll(io.LimitReader(r.Body, 1<<20))
	if err != nil {
		s.fail(w, http.StatusBadRequest, "bad_request", "cannot read body: %v", err)
		return
	}
	var req keyToolRequest
	if err := json.Unmarshal(body, &req); err != nil {
		s.fail(w, http.StatusBadRequest, "bad_request", "invalid JSON body: %v", err)
		return
	}
	req.DLLPath = strings.TrimSpace(req.DLLPath)
	req.DBPath = strings.TrimSpace(req.DBPath)

	// 0. resolve the chat-log database: native recovery verifies candidates
	// against it, so it must exist before scanning.
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
			s.writeJSON(w, http.StatusOK, Envelope{Ok: true, Data: keyToolResult{
				Found:  false,
				Reason: "no WeChat chat database found to verify a recovered key against; provide dbPath explicitly",
			}})
			return
		}
		dbPath = found[0]
	}

	// 1. native memory scan — the built-in recovery, no external tool. The
	//    bridge re-implements CipherTalk's scanner as a probe-driven search:
	//    every candidate key extracted from WeChat memory is verified against
	//    dbPath (page-1 HMAC, then magic), so a wrong guess can never pass.
	logf := func(m string) { bridgeLogDebug("keytool: %s", m) }
	key, label, attempts, nerr := findDBKeyInMemory(dbPath, accountDirOf(dbPath), logf)
	var nativeNote string
	if nerr != nil {
		nativeNote = nerr.Error()
		bridgeLog.Add("warn", "keytool: native scan unavailable: %v", nerr)
	} else if key == "" {
		bridgeLog.Add("info", "keytool: native scan found no key (attempts=%d)", attempts)
	}
	if key != "" {
		out := keyToolResult{Key: key, DBPath: dbPath, Mode: "raw", Verification: label, Attempts: attempts}
		out.Found = true
		if salt, serr := readDBSalt(dbPath); serr == nil {
			out.Salt = hex.EncodeToString(salt)
		}
		if label == "magic" {
			out.Reason = "key found in WeChat memory; it decrypts page 1 (magic check) but the HMAC parameters differ from SQLCipher-4 defaults, so export uses lenient mode"
		} else {
			out.Reason = "key found in WeChat memory and verified against " + displayDir(dbPath)
		}
		bridgeLog.Add("info", "keytool: native scan verified db key %s (%s, attempts=%d)", key, label, attempts)
		s.writeJSON(w, http.StatusOK, Envelope{Ok: true, Data: out})
		return
	}

	// 2. optional CipherTalk DLL fallback, used only when an explicit DLL is
	//    actually present on this machine (the default path needs none).
	dllPath := ""
	explicit := firstNonEmpty(req.DLLPath, s.cfg.KeyToolPath)
	for _, cand := range keyToolCandidates(explicit) {
		if fi, err := os.Stat(cand); err == nil && !fi.IsDir() {
			dllPath = cand
			break
		}
	}
	if dllPath == "" {
		reason := nativeNote
		if reason == "" {
			reason = "key not found in WeChat memory (attempts=" + itoa(attempts) + "). Make sure WeChat is running and logged in, and opened a chat at least once, then retry."
		}
		s.writeJSON(w, http.StatusOK, Envelope{Ok: true, Data: keyToolResult{Found: false, Reason: reason}})
		return
	}
	bridgeLog.Add("info", "keytool: loaded DLL %s", dllPath)

	tool, lerr := keyToolLoad(dllPath)
	if lerr != nil {
		s.writeJSON(w, http.StatusOK, Envelope{Ok: true, Data: keyToolResult{
			Found:   false,
			DLLPath: dllPath,
			Reason:  "cannot load key tool: " + lerr.Error(),
		}})
		return
	}
	defer tool.Close()

	dlKey := ""
	dlReason := ""
	var account keyToolAccount
	account, aerr := tool.scanAccount()
	if aerr == nil && account.DbKey != "" {
		dlKey = account.DbKey
	} else {
		// fallback: crypt_key neighborhood scan anchored on contact.db
		contact := findContactDB(s, dbPath)
		if contact == "" {
			dlReason = "account scan found no db_key (" + errString(aerr) + ") and no contact.db was found for the neighborhood scan"
		} else {
			diag, derr := tool.scanDiag(contact)
			if derr != nil {
				dlReason = "account scan missed and neighborhood scan failed: " + derr.Error()
			} else if diag.Key == "" {
				dlReason = "neighborhood scan found no key (auth=" + boolStr(diag.Auth) + ", markers=" + itoa(diag.Markers) + ", candidates=" + itoa(diag.Candidates) + "). Make sure WeChat is fully logged in and has opened a chat."
			} else {
				dlKey = diag.Key
			}
		}
	}

	if dlKey == "" {
		bridgeLog.Add("warn", "keytool: DLL fallback recovered nothing (%s)", dlReason)
		s.writeJSON(w, http.StatusOK, Envelope{Ok: true, Data: keyToolResult{
			Found:   false,
			DLLPath: dllPath,
			Reason:  dlReason,
		}})
		return
	}
	bridgeLog.Add("info", "keytool: db_key recovered via DLL for %s (len=%d)", account.Wxid, len(dlKey))
	key = dlKey

	raw, derr := hex.DecodeString(key)
	if derr != nil || len(raw) != 32 {
		s.writeJSON(w, http.StatusOK, Envelope{Ok: true, Data: keyToolResult{
			Found:  false,
			Key:    key,
			DBPath: dbPath,
			Reason: "key tool returned an unexpected key format (" + key + ")",
		}})
		return
	}

	out := keyToolResult{Key: key, DBPath: dbPath, DLLPath: dllPath, Account: &account}
	salt, serr := readDBSalt(dbPath)
	if serr == nil {
		out.Salt = hex.EncodeToString(salt)
	}
	if f, oerr := sqlcipher.Open(dbPath, raw, sqlcipher.ModeRaw, true); oerr == nil {
		f.Close()
		out.Found = true
		out.Mode = "raw"
		out.Verification = "mac"
		out.Attempts = 1
		out.Reason = "key recovered via DLL and verified against " + dbPath
	} else if macOK, magicOK, perr := sqlcipher.ProbePageKey(dbPath, raw); perr == nil && magicOK {
		if f, oerr := sqlcipher.OpenLenient(dbPath, raw, sqlcipher.ModeRaw, true); oerr == nil {
			f.Close()
			out.Found = true
			out.Mode = "raw"
			out.Verification = "magic"
			out.Attempts = 1
			out.Reason = "key decrypted page 1 (magic check) against " + dbPath + "; MAC parameters differ from SQLCipher-4 defaults (macOK=" + boolStr2(macOK) + "), export uses lenient mode"
		} else {
			out.Reason = "key decrypts page 1 but lenient open failed: " + oerr.Error()
		}
	} else {
		out.Reason = "key does not decrypt " + dbPath + " (macOK=" + boolStr2(macOK) + ", magicOK=" + boolStr2(magicOK) + ")"
	}
	bridgeLog.Add("info", "keytool: verify %s db=%s found=%v verification=%s", key[:8], dbPath, out.Found, out.Verification)
	s.writeJSON(w, http.StatusOK, Envelope{Ok: true, Data: out})
}

// findContactDB locates a WeChat contact.db for the neighborhood scan: under
// the explicit dbPath's account dir, or under the discovered roots.
func findContactDB(s *server, dbPath string) string {
	if dbPath != "" {
		base := dbPath
		for i := 0; i < 6; i++ {
			cand := filepath.Join(base, "contact.db")
			if fi, err := os.Stat(cand); err == nil && !fi.IsDir() {
				return cand
			}
			parent := filepath.Dir(base)
			if parent == base {
				break
			}
			base = parent
		}
	}
	roots := s.cfg.DBRoots
	if len(roots) == 0 {
		roots = defaultDBRoots()
	}
	for _, root := range roots {
		var hit string
		filepath.WalkDir(root, func(path string, d os.DirEntry, err error) error {
			if err != nil || d.IsDir() {
				return nil
			}
			if strings.EqualFold(d.Name(), "contact.db") {
				hit = path
				return filepath.SkipAll
			}
			return nil
		})
		if hit != "" {
			return hit
		}
	}
	return ""
}

func firstNonEmpty(vals ...string) string {
	for _, v := range vals {
		if strings.TrimSpace(v) != "" {
			return v
		}
	}
	return ""
}

func errString(err error) string {
	if err == nil {
		return "no error"
	}
	return err.Error()
}

func boolStr(b *bool) string {
	if b == nil {
		return "n/a"
	}
	return fmt.Sprintf("%v", *b)
}

func boolStr2(b bool) string { return fmt.Sprintf("%v", b) }

func itoa(n int) string { return fmt.Sprintf("%d", n) }
