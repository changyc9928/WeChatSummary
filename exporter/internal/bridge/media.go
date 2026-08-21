package bridge

import (
	"archive/zip"
	"bytes"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"regexp"
	"sort"
	"strconv"
	"strings"

	"wechatsummary/exporter/internal/datdecrypt"
)

// WeChat media cache files (*.dat — images, audio) use container formats from
// CipherTalk's datDecryptCore.ts. The decrypt "keys" are:
//
//   - XOR key: recovered from _t.dat template files (majority vote over the
//     trailing 2 bytes with the x^0xFF == y^0xD9 consistency check), or from
//     the file header itself against known JPEG/PNG/GIF/BMP/WebP signatures.
//   - AES key (V2 files): a per-account 16-char ASCII key materialized in
//     WeChat memory; the bridge recovers it natively (keynative.go) by
//     probing printable runs against the V2 template ciphertext — no
//     external DLL (same scan CipherTalk's wkt_scan_image_key_auth did).
//
// V1 files use a constant key ("cfcd208495d565ef" = the first 16 hex chars of
// MD5("0"), as ASCII).

const (
	mediaMaxTemplateFiles = 32
	mediaTemplateCap      = 16 // newest templates used
	mediaMaxSingleSize    = 64 << 20
	mediaMaxBatchBytes    = 256 << 20
)

// --- template discovery (CipherTalk imageKeyService.findTemplateDatFiles) ---

func findTemplateDatFiles(root string) ([]string, error) {
	if root == "" {
		return nil, errors.New("no directory to scan")
	}
	var files []string
	err := filepath.WalkDir(root, func(path string, d os.DirEntry, err error) error {
		if err != nil {
			return nil
		}
		if d.IsDir() {
			return nil
		}
		if strings.HasSuffix(strings.ToLower(d.Name()), "_t.dat") {
			files = append(files, path)
		}
		return nil
	})
	if err != nil {
		return nil, err
	}
	re := regexp.MustCompile(`(\d{4}-\d{2})`)
	sort.SliceStable(files, func(i, j int) bool {
		// newest YYYY-MM first
		mi := re.FindStringSubmatch(files[i])
		mj := re.FindStringSubmatch(files[j])
		if len(mi) > 1 && len(mj) > 1 {
			return mi[1] > mj[1]
		}
		return false
	})
	if len(files) > mediaTemplateCap {
		files = files[:mediaTemplateCap]
	}
	return files, nil
}

func xorKeyFromTemplateFiles(files []string) (byte, bool) {
	var pairs [][2]byte
	for _, f := range files {
		b, err := os.ReadFile(f)
		if err != nil || len(b) < 2 {
			continue
		}
		pairs = append(pairs, [2]byte{b[len(b)-2], b[len(b)-1]})
	}
	return datdecrypt.XorKeyFromTemplate(pairs)
}

// templateCiphertext returns the first 16 bytes past the 15-byte header of
// the newest V2-signed template (CipherTalk getCiphertextFromTemplate).
func templateCiphertext(files []string) ([]byte, error) {
	for _, f := range files {
		b, err := os.ReadFile(f)
		if err != nil || len(b) < 0x1f {
			continue
		}
		if datdecrypt.DetectVersion(b) == 2 {
			return b[0x0f:0x1f], nil
		}
	}
	return nil, errors.New("no V2 template file found (only V1/V3 media present?)")
}

// verifyAesKey decrypts every V2 template with the candidate key (strict
// PKCS7 + XOR + image-magic) and accepts the key only when a full template
// decrypt succeeds ("template-decrypt"). There is deliberately NO weaker
// acceptance path: a "block-magic" check on a single 16-byte ciphertext
// window matches random keys with probability ~2^-16 per attempt (BMP's 2-byte
// "BM" magic alone), so a 4M-attempt memory scan would be statistically
// guaranteed to surface a false positive — exactly what produced the earlier
// garbage media decrypts (verification=block-magic in the logs).
//
// The final gate is StrongMediaPayload, which requires a full structural
// image header (JPEG markers + JFIF/Exif/SOF, PNG IHDR, GIF LSD, WebP
// chunks, BMP DIB fields, HEIC ftyp box), NOT a bare 2-byte magic: at 40M
// attempts a wrong key that decrypts a template into a structurally valid
// image is ~2^-40+ per attempt, i.e. effectively impossible.
func verifyAesKey(aesKey []byte, files []string) (bool, string) {
	for _, f := range files {
		b, err := os.ReadFile(f)
		if err != nil || datdecrypt.DetectVersion(b) != 2 {
			continue
		}
		if len(b) > mediaMaxSingleSize {
			continue
		}
		if xorKey, ok := xorKeyFromTemplateFiles([]string{f}); ok {
			dec, derr := datdecrypt.Decrypt(b, xorKey, aesKey)
			if derr == nil && datdecrypt.StrongMediaPayload(dec) {
				return true, "template-decrypt"
			}
		}
	}
	return false, ""
}

// --- routes ---

// mediaKeysRequest is the body of POST /api/media/keys.
type mediaKeysRequest struct {
	// Dir is the WeChat account dir (or any root) to scan for _t.dat
	// templates. Empty: derived from the discovered databases.
	Dir string `json:"dir"`
	// DLLPath overrides the wechat_key_tool.dll used for the AES key scan.
	DLLPath string `json:"dllPath"`
	// Manual overrides; providing aesKey skips the memory scan.
	XorKey string `json:"xorKey"` // hex, e.g. "73"
	AesKey string `json:"aesKey"` // 16 ASCII chars
}

// mediaKeysResult is the JSON payload of /api/media/keys.
type mediaKeysResult struct {
	Found         bool   `json:"found"`
	XorKey        string `json:"xorKey,omitempty"` // hex
	AesKey        string `json:"aesKey,omitempty"`
	Source        string `json:"source,omitempty"`       // memory | manual
	Verification  string `json:"verification,omitempty"` // template-decrypt | block-magic | none
	Dir           string `json:"dir,omitempty"`
	TemplateCount int    `json:"templateCount"`
	Reason        string `json:"reason,omitempty"`
}

func (s *server) handleMediaKeys(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		s.fail(w, http.StatusMethodNotAllowed, "method_not_allowed", "use POST")
		return
	}
	body, err := io.ReadAll(io.LimitReader(r.Body, 1<<20))
	if err != nil {
		s.fail(w, http.StatusBadRequest, "bad_request", "cannot read body: %v", err)
		return
	}
	var req mediaKeysRequest
	if err := json.Unmarshal(body, &req); err != nil {
		s.fail(w, http.StatusBadRequest, "bad_request", "invalid JSON body: %v", err)
		return
	}
	req = mediaKeysRequest{
		Dir:     strings.TrimSpace(req.Dir),
		DLLPath: strings.TrimSpace(req.DLLPath),
		XorKey:  strings.TrimSpace(req.XorKey),
		AesKey:  strings.TrimSpace(req.AesKey),
	}

	dir := req.Dir
	if dir == "" {
		roots := s.cfg.DBRoots
		if len(roots) == 0 {
			roots = defaultDBRoots()
		}
		if dbs, derr := findWeChatDBs(roots); derr == nil && len(dbs) > 0 {
			dir = accountDirOf(dbs[0])
		}
		if dir == "" && len(roots) > 0 {
			dir = roots[0]
		}
	}
	out := mediaKeysResult{Dir: dir}

	files, ferr := findTemplateDatFiles(dir)
	if ferr != nil {
		s.fail(w, http.StatusInternalServerError, "template_scan_failed", "%v", ferr)
		return
	}
	out.TemplateCount = len(files)
	if len(files) == 0 {
		out.Reason = "no _t.dat template files found under " + displayDir(dir) + ". Open an image chat in WeChat (thumbnails are cached as _t.dat) and retry, or provide dir/xorKey/aesKey manually."
		bridgeLog.Add("warn", "media keys: no _t.dat templates under %s", displayDir(dir))
		s.writeJSON(w, http.StatusOK, Envelope{Ok: true, Data: out})
		return
	}

	// XOR key from templates
	xorKey, xok := xorKeyFromTemplateFiles(files)
	if xok {
		out.XorKey = fmt.Sprintf("%02x", xorKey)
		out.Found = true
		bridgeLog.Add("info", "media keys: %d _t.dat templates under %s -> xorKey 0x%02x", len(files), displayDir(dir), xorKey)
	} else {
		out.Reason = "could not derive the XOR key from template files (inconsistent trailing bytes); provide xorKey manually (e.g. \"73\")"
	}
	if v1, v2, v3, total := templateVersionCounts(files); total > 0 {
		bridgeLog.Add("info", "media keys: template versions V1=%d V2=%d V3=%d (total %d): V1/V3 decrypt with XOR alone, only V2 needs the AES key", v1, v2, v3, total)
	}

	// AES key — native memory scan, no external tool: the 32-char key string
	// is in WeChat memory once images have been loaded; we test every
	// printable 16-char window against the V2 template ciphertext (block
	// magic) and confirm with a full template decrypt.
	aesKey := []byte(nil)
	if len(req.AesKey) >= 16 {
		aesKey = datdecrypt.AsciiKey16(req.AesKey)
		out.AesKey = string(aesKey)
		out.Source = "manual"
	} else if xok {
		if _, cerr := templateCiphertext(files); cerr != nil {
			out.Reason = appendReason(out.Reason, cerr.Error())
		} else {
			key, attempts, serr := findImageAesKeyInMemory(dir, files, func(m string) { bridgeLogDebug("media keys: %s", m) })
			if serr != nil {
				out.Reason = appendReason(out.Reason, "AES key scan: "+serr.Error())
				bridgeLog.Add("warn", "media keys: AES key scan failed: %v", serr)
			} else if key != "" {
				aesKey = datdecrypt.AsciiKey16(key)
				out.AesKey = string(aesKey)
				out.Source = "memory"
				bridgeLog.Add("info", "media keys: AES key recovered from WeChat memory natively (attempts=%d)", attempts)
			} else {
				out.Reason = appendReason(out.Reason, "AES key not found in WeChat memory after 3 scan attempts ("+itoa(attempts)+" probes, wxid-anchored first). Open a chat with images — or Moments/朋友圈 — in WeChat so the image key is loaded into memory, then retry.")
				bridgeLog.Add("info", "media keys: AES key not found in memory after 3 attempts (probes=%d)", attempts)
			}
		}
	}

	if aesKey == nil && xok {
		out.Found = out.XorKey != "" // xor alone is enough to decrypt V1/V3 media
	}

	// verify
	if xok && aesKey != nil {
		ok, how := verifyAesKey(aesKey, files)
		if ok {
			out.Found = true
			out.Verification = how
			bridgeLog.Add("info", "media keys: AES verified (%s)", how)
		} else {
			out.Reason = appendReason(out.Reason, "recovered AES key failed to decrypt any V2 template (wrong account? retry the scan after opening images)")
			out.Found = false
			bridgeLog.Add("warn", "media keys: AES verification failed")
		}
	} else if xok && len(files) > 0 && detectOnlyV1orV3(files) {
		out.Verification = "none (V1/V3 only; constant or header-derived XOR decrypts these)"
	}

	bridgeLog.Add("info", "media keys: result found=%v xor=%s aes=%s verification=%s", out.Found, out.XorKey, out.AesKey, out.Verification)
	s.writeJSON(w, http.StatusOK, Envelope{Ok: true, Data: out})
}

// mediaDecryptRequest is the body of POST /api/media/decrypt.
type mediaDecryptRequest struct {
	Path   string `json:"path"`
	XorKey string `json:"xorKey"` // hex, optional; derived from header when empty
	AesKey string `json:"aesKey"` // 16 ASCII chars; V2 requires it
}

// mediaDecryptResult is the JSON payload of /api/media/decrypt.
type mediaDecryptResult struct {
	FileName string `json:"fileName"`
	Data     string `json:"data"` // base64
	Ext      string `json:"ext"`
	Size     int    `json:"size"`
	Version  int    `json:"version"`
	XorKey   string `json:"xorKey,omitempty"`
	Source   string `json:"source,omitempty"` // header | manual | template
	Reason   string `json:"reason,omitempty"`
}

func (s *server) handleMediaDecrypt(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		s.fail(w, http.StatusMethodNotAllowed, "method_not_allowed", "use POST")
		return
	}
	body, err := io.ReadAll(io.LimitReader(r.Body, 1<<20))
	if err != nil {
		s.fail(w, http.StatusBadRequest, "bad_request", "cannot read body: %v", err)
		return
	}
	var req mediaDecryptRequest
	if err := json.Unmarshal(body, &req); err != nil {
		s.fail(w, http.StatusBadRequest, "bad_request", "invalid JSON body: %v", err)
		return
	}
	req.Path = strings.TrimSpace(req.Path)
	req.XorKey = strings.TrimSpace(req.XorKey)
	req.AesKey = strings.TrimSpace(req.AesKey)

	in, inerr := os.Open(req.Path)
	if inerr != nil {
		s.fail(w, http.StatusBadRequest, "file_open_failed", "%v", inerr)
		return
	}
	defer in.Close()
	st, serr := in.Stat()
	if serr != nil || st.IsDir() {
		s.fail(w, http.StatusBadRequest, "file_open_failed", "not a file: %s", req.Path)
		return
	}
	if st.Size() > mediaMaxSingleSize {
		s.fail(w, http.StatusBadRequest, "file_too_large", "file is %d bytes (limit %d)", st.Size(), mediaMaxSingleSize)
		return
	}
	data, rerr := io.ReadAll(in)
	if rerr != nil {
		s.fail(w, http.StatusInternalServerError, "file_read_failed", "%v", rerr)
		return
	}

	version := datdecrypt.DetectVersion(data)
	out := mediaDecryptResult{FileName: filepath.Base(req.Path), Version: version}

	// XOR key: manual override > header-derived
	xorKey := byte(0)
	out.Source = "header"
	if req.XorKey != "" {
		v, perr := strconv.ParseUint(req.XorKey, 16, 8)
		if perr != nil {
			s.fail(w, http.StatusBadRequest, "bad_xor_key", "%v", perr)
			return
		}
		xorKey = byte(v)
		out.Source = "manual"
	} else if version == 0 {
		if k, ok := datdecrypt.DetectXorKeyFromHeader(data); ok {
			xorKey = k
		} else if s.cfg.MediaXorKey != "" {
			v, perr := strconv.ParseUint(s.cfg.MediaXorKey, 16, 8)
			if perr == nil {
				xorKey = byte(v)
				out.Source = "config"
			}
		}
		if xorKey == 0 && out.Source == "header" {
			s.fail(w, http.StatusBadRequest, "xor_key_required",
				"cannot derive the XOR key from the header for this file (version %d); pass xorKey (hex), e.g. 73 for classic image XOR", version)
			return
		}
	}
	out.XorKey = fmt.Sprintf("%02x", xorKey)

	var aesKey []byte
	if len(req.AesKey) >= 16 {
		aesKey = datdecrypt.AsciiKey16(req.AesKey)
	}
	dec, derr := datdecrypt.Decrypt(data, xorKey, aesKey)
	if derr != nil {
		s.fail(w, http.StatusBadRequest, "decrypt_failed", "%v", derr)
		return
	}
	// plaintext passthrough: old WeChat wrote raw media with NUL padding
	if version == 0 {
		stripped := datdecrypt.StripTrailingNul(dec)
		if datdecrypt.LooksLikeMediaPayload(stripped) {
			dec = stripped
		}
	}
	ext := datdecrypt.DetectExt(dec)
	if ext == "" {
		ext = ".bin"
	}
	out.Ext = ext
	out.Size = len(dec)
	out.Data = base64.StdEncoding.EncodeToString(dec)
	s.writeJSON(w, http.StatusOK, Envelope{Ok: true, Data: out})
}

// mediaDirRequest is the body of POST /api/media/decrypt-dir.
type mediaDirRequest struct {
	Dir    string `json:"dir"`
	XorKey string `json:"xorKey"`
	AesKey string `json:"aesKey"`
	// IncludePlain copies matching non-.dat media as-is (true by default).
	IncludePlain *bool `json:"includePlain"`
}

// mediaDirResult is the JSON payload of /api/media/decrypt-dir.
type mediaDirResult struct {
	FileName  string   `json:"fileName"` // e.g. wechat-media-decrypt.zip
	ZipBase64 string   `json:"zipBase64"`
	FileCount int      `json:"fileCount"`
	Decrypted int      `json:"decrypted"`
	Plain     int      `json:"plain"`
	Failed    int      `json:"failed"`
	Bytes     int64    `json:"bytes"`
	Reasons   []string `json:"reasons,omitempty"` // up to 20 failure lines
}

func (s *server) handleMediaDecryptDir(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		s.fail(w, http.StatusMethodNotAllowed, "method_not_allowed", "use POST")
		return
	}
	body, err := io.ReadAll(io.LimitReader(r.Body, 1<<20))
	if err != nil {
		s.fail(w, http.StatusBadRequest, "bad_request", "cannot read body: %v", err)
		return
	}
	var req mediaDirRequest
	if err := json.Unmarshal(body, &req); err != nil {
		s.fail(w, http.StatusBadRequest, "bad_request", "invalid JSON body: %v", err)
		return
	}
	req.Dir = strings.TrimSpace(req.Dir)
	req.XorKey = strings.TrimSpace(req.XorKey)
	req.AesKey = strings.TrimSpace(req.AesKey)
	includePlain := req.IncludePlain == nil || *req.IncludePlain

	var aesKey []byte
	if len(req.AesKey) >= 16 {
		aesKey = datdecrypt.AsciiKey16(req.AesKey)
	}
	var manualXor *byte
	if req.XorKey != "" {
		v, perr := strconv.ParseUint(req.XorKey, 16, 8)
		if perr != nil {
			s.fail(w, http.StatusBadRequest, "bad_xor_key", "%v", perr)
			return
		}
		b := byte(v)
		manualXor = &b
	}

	var buf bytes.Buffer
	zw := zip.NewWriter(&buf)
	out := mediaDirResult{FileName: "wechat-media-decrypt.zip"}
	var totalBytes int64
	var reasons []string
	walkErr := filepath.WalkDir(req.Dir, func(path string, d os.DirEntry, err error) error {
		if err != nil {
			return nil
		}
		if d.IsDir() {
			return nil
		}
		realExt := filepath.Ext(d.Name())
		ext := strings.ToLower(realExt)
		rel := relName(req.Dir, path)

		if ext != ".dat" {
			if includePlain && ext != ".db" && ext != ".db-wal" && ext != ".db-shm" {
				if fi, e := d.Info(); e == nil && fi.Size() <= mediaMaxSingleSize {
					totalBytes += fi.Size()
					if totalBytes <= mediaMaxBatchBytes {
						if b, e := os.ReadFile(path); e == nil && writeZipEntry(zw, rel, b) == nil {
							out.Plain++
							out.FileCount++
						}
					}
				}
			}
			return nil
		}
		if totalBytes >= mediaMaxBatchBytes {
			return nil
		}
		b, rerr := os.ReadFile(path)
		if rerr != nil {
			return nil
		}
		version := datdecrypt.DetectVersion(b)
		xorKey := byte(0)
		if manualXor != nil {
			xorKey = *manualXor
		} else if version == 0 {
			if k, ok := datdecrypt.DetectXorKeyFromHeader(b); ok {
				xorKey = k
			}
		}
		dec, derr := datdecrypt.Decrypt(b, xorKey, aesKey)
		if derr != nil {
			out.Failed++
			if len(reasons) < 20 {
				reasons = append(reasons, rel+": "+derr.Error())
			}
			return nil
		}
		if version == 0 {
			stripped := datdecrypt.StripTrailingNul(dec)
			if datdecrypt.LooksLikeMediaPayload(stripped) {
				dec = stripped
			}
		}
		extOut := datdecrypt.DetectExt(dec)
		if extOut == "" {
			extOut = ".bin"
		}
		name := strings.TrimSuffix(rel, realExt) + extOut
		if werr := writeZipEntry(zw, name, dec); werr != nil {
			return nil
		}
		out.Decrypted++
		out.FileCount++
		totalBytes += int64(len(dec))
		return nil
	})
	if walkErr != nil && !os.IsNotExist(walkErr) {
		s.fail(w, http.StatusInternalServerError, "dir_scan_failed", "%v", walkErr)
		return
	}
	if err := zw.Close(); err != nil {
		s.fail(w, http.StatusInternalServerError, "zip_failed", "%v", err)
		return
	}
	out.ZipBase64 = base64.StdEncoding.EncodeToString(buf.Bytes())
	out.Bytes = totalBytes
	out.Reasons = reasons
	s.writeJSON(w, http.StatusOK, Envelope{Ok: true, Data: out})
}

// --- helpers ---

func writeZipEntry(zw *zip.Writer, name string, data []byte) error {
	w, err := zw.Create(name)
	if err != nil {
		return err
	}
	_, err = w.Write(data)
	return err
}

func relName(root, path string) string {
	rel, err := filepath.Rel(root, path)
	if err != nil {
		return filepath.Base(path)
	}
	return filepath.ToSlash(rel)
}

// accountDirOf climbs from a database path to the WeChat account root (the
// parent of db_storage — the sibling msg/attach lives there too).
func accountDirOf(dbPath string) string {
	dir := filepath.Dir(dbPath)
	for i := 0; i < 8; i++ {
		base := strings.ToLower(filepath.Base(dir))
		if base == "db_storage" {
			return filepath.Dir(dir)
		}
		parent := filepath.Dir(dir)
		if parent == dir {
			break
		}
		dir = parent
	}
	return filepath.Dir(dbPath)
}

// templateVersionCounts reports how many of the given _t.dat files carry
// each on-disk encryption version header (V1 sig, V2 sig, V3 = none).
func templateVersionCounts(files []string) (v1, v2, v3, total int) {
	for _, f := range files {
		h := make([]byte, 16)
		fh, err := os.Open(f)
		if err != nil {
			continue
		}
		n, _ := fh.Read(h)
		fh.Close()
		switch {
		case n >= 6 && datdecrypt.DetectVersion(h[:n]) == 1:
			v1++
		case n >= 6 && datdecrypt.DetectVersion(h[:n]) == 2:
			v2++
		default:
			v3++
		}
		total++
	}
	return v1, v2, v3, total
}

func detectOnlyV1orV3(files []string) bool {
	for _, f := range files {
		b, err := os.ReadFile(f)
		if err != nil {
			continue
		}
		if datdecrypt.DetectVersion(b) == 2 {
			return false
		}
	}
	return true
}

func appendReason(existing, add string) string {
	if existing == "" {
		return add
	}
	return existing + ". " + add
}

func displayDir(dir string) string {
	if dir == "" {
		return "(none)"
	}
	return dir
}
