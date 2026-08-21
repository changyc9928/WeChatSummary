package bridge

import (
	"archive/zip"
	"crypto/md5"
	"crypto/tls"
	"encoding/hex"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"os"
	"path/filepath"
	"regexp"
	"sort"
	"strconv"
	"strings"
	"time"

	"wechatsummary/exporter/internal/datdecrypt"
	"wechatsummary/exporter/internal/extract"
)

// exportMediaPerTable walks each table's own messages (via index ranges into
// the merged slice), resolves+decrypts image .dat files into the zip under
// images/<YYYYMMDD>/, rewrites the matching message content to
// "[图片] images/...", and embeds emojis (emojis/<date>/<ts>_<md5>.<ext>) and
// videos (videos/<date>/<ts>_<md5>.mp4) the same way CipherTalk does. Each
// table's rows are visited exactly once (the previous all-tables × all-messages
// loop was O(T*M)). Mutations land in msgs, the slice messages.json marshals.
// Counters are kept on me (runtime).
func exportMediaPerTable(zw *zip.Writer, accountDir string, msgs []extract.Message, ranges []tableRange, me *mediaExportRuntime) (saved int, failed int) {
	// Load the 4.1 hardlink.db md5 index once per export (it can hold tens of
	// thousands of rows; per-image lookup would be wasteful).
	var hli *hardlinkIndex
	var hliReason string
	if me != nil && me.secret != nil {
		hli, hliReason = loadHardlinkIndex(accountDir, me.secret)
		if hli == nil {
			bridgeLog.Add("info", "export media: hardlink index unavailable (%s); falling back to session-dir search", hliReason)
		} else {
			bridgeLog.Add("info", "export media: hardlink index %s: %d md5 -> %d dirs", hli.imageTable, len(hli.byMD5), len(hli.dirNames))
		}
	}
	for _, rng := range ranges {
		mt := rng.mt
		name := strings.ToLower(filepath.Base(mt.Table.Name()))
		var sessionCands []string
		switch {
		case strings.HasPrefix(name, "msg_") && len(name) == 36:
			// 4.1: msg_<md5(sessionId)>; the attach storage dir is that hash
			sessionCands = append(sessionCands, name[4:])
		case len(name) == 32 && isHexString(name):
			sessionCands = append(sessionCands, name)
		default:
			sessionCands = append(sessionCands, name)
		}
		if s := cleanSessionDir(sessionCands[0]); s != "" && s != sessionCands[0] {
			sessionCands = append(sessionCands, s)
		}
		packed, packedSamples, perr := extract.ScanPackedInfo(mt.Table, mt.Columns)
		if perr != nil {
			bridgeLog.Add("warn", "export media: ScanPackedInfo(%s): %v", mt.Table.Name(), perr)
		}
		for _, ps := range packedSamples {
			bridgeLog.Add("info", "export media: packed blob sample rowid=%d hex=%x ascii=%q", ps.RowID, ps.Blob, snippet(string(ps.Blob), 96))
		}
		diagLogged := false
		for i := rng.start; i < rng.en; i++ {
			m := &msgs[i]
			if m.LocalType == 3 {
				// Image: CipherTalk passes BOTH the XML md5 and the packed-info
				// dat name to decryptImage (exportService.ts:2865-2892) because
				// on WeChat 4.1 the on-disk cache file is named by the packed
				// dat base while the XML carries a different md5 attribute.
				// The bridge previously used only the XML md5 as the lookup
				// key, so every image whose on-disk name differed ("no .dat
				// found for <xmlmd5>") was skipped even when the file existed
				// (proven by the 01:31 run: AES key present+verified, still 0
				// images saved). Build the full candidate key set now.
				md5Key := extract.MediaMd5FromXML(m.RawContent)
				if md5Key == "" {
					md5Key = extract.MediaMd5FromXML(m.Content)
				}
				var keys []string
				if md5Key != "" {
					keys = append(keys, md5Key)
				}
				if pn := packed[m.RowID]; pn != "" && !containsFold(keys, pn) {
					keys = append(keys, pn)
				}
				if len(keys) == 0 {
					me.skipped++
					if !diagLogged && me.skipped <= 3 {
						diagLogged = true
						bridgeLog.Add("info", "export media: no md5 or packed dat name for image in %s (rowid=%d, localId=%d, createTime=%d) rawContent=%q content=%q packedCount=%d columns=%v",
							mt.Table.Name(), m.RowID, m.LocalID, m.CreateTime, snippet(m.RawContent, 160), snippet(m.Content, 160), len(packed), mt.Columns)
					}
					continue
				}
				// Diagnostic: show the packed-info dat name for the first few
				// IMAGE rows (the earlier packed-samples only covered the
				// first rows of the table, which are not images, so we never
				// saw what image rows carry). Confirms whether the packed
				// dat base equals the on-disk name in 4.1.12.26.
				if len(keys) > 1 && me.packedDiag < 3 {
					me.packedDiag++
					bridgeLog.Add("debug", "export media: image rowid=%d xmlMd5=%s packedName=%s rawHead=%q", m.RowID, md5Key, packed[m.RowID], snippet(m.RawContent, 120))
				}
				me.found++
				var datPath string
				var dec []byte
				var derr error
				// 1) hardlink.db index per candidate key (md5 -> file_name +
				// dir1/dir2). The index is sparse (964-985 image rows on this
				// account) but exact: when it knows the file, the path is
				// right (e.g. xml md5 390dfa81... -> 363569555d...dat).
				if hli != nil {
					for _, key := range keys {
						datPath, dec, derr = hli.resolve(accountDir, key, me.xorKey, me.aesKey)
						if derr != nil || datPath == "" || dec == nil {
							bridgeLog.Add("debug", "export media: hardlink miss %s (rowid=%d): %v", key, m.RowID, derr)
							continue
						}
						if !strings.EqualFold(key, md5Key) {
							bridgeLog.Add("debug", "export media: image %s resolved via hardlink key %s (rowid=%d)", md5Key, key, m.RowID)
						}
						break
					}
				}
				// 2) hardlink unavailable or missed -> legacy session-dir /
				//    walk. Build CipherTalk's full .dat index once, lazily, on
				//    the first miss (it is the net that catches files stored
				//    outside the predicted session/month/subdir layout).
				if (hli == nil || derr != nil || datPath == "" || dec == nil) && len(keys) > 0 {
					if !me.datIndexBuilt {
						me.datIndex = ensureFullDatIndex(accountDir)
						me.datIndexBuilt = true
					}
					// The hardlink index knows the real on-disk file name even
					// when the predicted attach path missed (e.g. the file was
					// moved or stored under another session dir). The full
					// .dat index is keyed by that file's base name — NOT the
					// message XML md5 — so search the whole legacy chain by
					// every candidate: XML md5, packed dat name, and the
					// hardlink-known file name (all deduped).
					for _, key := range keys {
						for _, sessionDir := range sessionCands {
							datPath, dec, derr = resolveDatPath(accountDir, sessionDir, key, m.CreateTime, me.haveXor, me.xorKey, me.aesKey, me.datIndex)
							if derr == nil && datPath != "" && dec != nil {
								if !strings.EqualFold(key, md5Key) {
									bridgeLog.Add("debug", "export media: image %s resolved via alternate key %s (rowid=%d)", md5Key, key, m.RowID)
								}
								break
							}
						}
						if derr == nil && datPath != "" && dec != nil {
							break
						}
					}
				}
				if derr != nil || datPath == "" || dec == nil {
					failed++
					// The hardlink index telling us the on-disk file name does
					// NOT mean the file resolved: every decrypt attempt failed,
					// typically because the V2 AES key is missing (aes=
					// memory:none). Say so instead of a bare "no .dat found",
					// which reads as if the file itself is gone.
					if hli != nil {
						for _, key := range keys {
							if fn := hli.fileNameFor(key); fn != "" {
								bridgeLog.Add("info", "export media: image %s (rowid=%d) not resolved: hardlink index knows %s (key %s) but no candidate decrypted (%s, last err: %v) — V2 images need the AES key; open an image chat / Moments in WeChat so the key is in memory, then retry", md5Key, m.RowID, fn, key, aesNote(me.reason), derr)
								failed-- // counted once below
								break
							}
						}
					}
					bridgeLog.Add("info", "export media: image %s (rowid=%d) not resolved: %v", md5Key, m.RowID, derr)
					continue
				}
				ext := datdecrypt.DetectExt(dec)
				if ext == "" {
					failed++
					continue
				}
				df := time.Unix(m.CreateTime, 0).Format("20060102")
				fileName := fmt.Sprintf("%d_%s%s", m.CreateTime, md5Key, ext)
				rel := "images/" + df + "/" + fileName
				if werr := writeZipEntry(zw, rel, dec); werr != nil {
					failed++
					continue
				}
				m.Content = "[图片] " + rel
				m.Type = "图片消息"
				saved++
				continue
			}
			if m.LocalType == 47 {
				// Emoji: replicate CipherTalk exportService.ts LocalType 47
				// flow exactly. Parse cdnurl/thumburl/md5/encrypturl/aeskey
				// from the XML (decoded content first, raw fallback), then
				// fetch via: local Emojis cache -> CDN download -> encryptUrl
				// + AES-128-ECB. Unlike an earlier bridge patch, business/
				// emoticon/Persist files are NEVER read raw: they are
				// encrypted with an unknown scheme and copying them produced
				// the corrupted .gif entries in exported zips.
				content := m.Content
				raw := m.RawContent
				f := emojiFieldsFromXML(raw, content)
				if f.cdnURL == "" && f.md5 == "" {
					me.skipped++
					continue
				}
				cacheKey := f.md5
				if cacheKey == "" {
					cacheKey = hashStringCipherTalk(f.cdnURL)
				}
				ext := ".png"
				if strings.Contains(f.cdnURL, ".gif") || strings.Contains(content, `type="2"`) {
					ext = ".gif"
				}
				df := time.Unix(m.CreateTime, 0).Format("20060102")
				fileName := fmt.Sprintf("%d_%s%s", m.CreateTime, cacheKey, ext)
				rel := "emojis/" + df + "/" + fileName
				data, src := me.resolveEmoji(cacheKey, f)
				if data == nil {
					failed++
					bridgeLog.Add("debug", "export media: emoji %s (rowid=%d) not resolved (local cache, CDN and encryptUrl all missed; createTime=%d)", cacheKey, m.RowID, m.CreateTime)
					continue
				}
				if werr := writeZipEntry(zw, rel, data); werr != nil {
					failed++
					bridgeLog.Add("debug", "export media: emoji %s zip failed: %v", cacheKey, werr)
					continue
				}
				bridgeLog.Add("debug", "export media: emoji %s -> %s (%s, %d bytes)", cacheKey, rel, src, len(data))
				m.Content = "[动画表情] " + rel
				m.Type = "动画表情"
				me.found++
				saved++
				continue
			}
			if m.LocalType == 43 {
				// Video: CipherTalk videoService parity. The XML carries
				// multiple md5 candidates (newmd5/md5/rawmd5/originsourcemd5,
				// attrs and tags), the on-disk name may not equal any of them
				// (hardlink.db video_hardlink_info% bridges md5 -> file_name,
				// with _raw/_hd/`  suffixes), months may be YYYY-MM or YYYYMM,
				// and the size+md5 scan is the last-resort fallback.
				cands := videoMd5Candidates(m.RawContent, m.Content)
				if len(cands) == 0 {
					me.skipped++
					bridgeLog.Add("debug", "export media: video (rowid=%d) has no md5 candidate in XML; skipping (createTime=%d)", m.RowID, m.CreateTime)
					continue
				}
				vp, matched := resolveLocalVideo(accountDir, cands, m.RawContent, m.Content, me, hli, m.CreateTime)
				if vp == "" {
					failed++
					bridgeLog.Add("debug", "export media: video %v (rowid=%d) not found (createTime=%d)", cands, m.RowID, m.CreateTime)
					continue
				}
				df := time.Unix(m.CreateTime, 0).Format("20060102")
				rel := fmt.Sprintf("videos/%s/%d_%s%s", df, m.CreateTime, matched, strings.ToLower(filepath.Ext(vp)))
				data, rerr := os.ReadFile(vp)
				if rerr != nil {
					failed++
					bridgeLog.Add("debug", "export media: video %s read failed: %v", matched, rerr)
					continue
				}
				if werr := writeZipEntry(zw, rel, data); werr != nil {
					failed++
					bridgeLog.Add("debug", "export media: video %s zip failed: %v", matched, werr)
					continue
				}
				m.Content = "[视频] " + rel
				m.Type = "视频消息"
				me.found++
				saved++
				continue
			}
		}
	}
	return saved, failed
}

// emojiFields are the per-message emoji fields parsed from the message XML,
// mirroring CipherTalk exportService.ts LocalType 47: cdnurl (or thumburl),
// (emoticon)md5, encrypturl, aeskey. &amp; is decoded to & on the URLs.
type emojiFields struct {
	cdnURL     string
	md5        string
	encryptURL string
	aesKey     string
}

var (
	cdnURLRe      = regexp.MustCompile(`(?i)cdnurl\s*=\s*['"]([^'"]+)['"]`)
	thumbURLRe    = regexp.MustCompile(`(?i)thumburl\s*=\s*['"]([^'"]+)['"]`)
	emojiMD5AttrRe = regexp.MustCompile(`(?i)(?:emoticon)?md5\s*=\s*['"]([a-f0-9]+)['"]`)
	emojiMD5TagRe  = regexp.MustCompile(`(?i)<md5>([^<]+)</md5>`)
	encryptURLRe  = regexp.MustCompile(`(?i)encrypturl\s*=\s*['"]([^'"]+)['"]`)
	aesKeyRe      = regexp.MustCompile(`(?i)aeskey\s*=\s*['"]([a-zA-Z0-9]+)['"]`)
)

// emojiFieldsFromXML parses emoji fields from the decoded content first, then
// the raw content (CipherTalk parses the decoded content; the raw fallback
// covers bridges where decoding stripped the attributes).
func emojiFieldsFromXML(raw, content string) emojiFields {
	f := emojiFields{}
	for _, src := range []string{content, raw} {
		if src == "" {
			continue
		}
		if f.cdnURL == "" {
			if m := cdnURLRe.FindStringSubmatch(src); m != nil {
				f.cdnURL = strings.ReplaceAll(m[1], "&amp;", "&")
			} else if m := thumbURLRe.FindStringSubmatch(src); m != nil {
				f.cdnURL = strings.ReplaceAll(m[1], "&amp;", "&")
			}
		}
		if f.md5 == "" {
			if m := emojiMD5AttrRe.FindStringSubmatch(src); m != nil {
				f.md5 = strings.ToLower(m[1])
			} else if m := emojiMD5TagRe.FindStringSubmatch(src); m != nil {
				f.md5 = strings.ToLower(m[1])
			}
		}
		if f.encryptURL == "" {
			if m := encryptURLRe.FindStringSubmatch(src); m != nil {
				f.encryptURL = strings.ReplaceAll(m[1], "&amp;", "&")
			}
		}
		if f.aesKey == "" {
			if m := aesKeyRe.FindStringSubmatch(src); m != nil {
				f.aesKey = m[1]
			}
		}
	}
	return f
}

// hashStringCipherTalk replicates CipherTalk's hashString (djb2 with 32-bit
// truncation, |hash| in hex) used as the emoji cache key when no md5 exists.
func hashStringCipherTalk(s string) string {
	var h int32
	for i := 0; i < len(s); i++ {
		h = (h<<5 - h) + int32(s[i])
	}
	v := int64(h)
	if v < 0 {
		v = -v
	}
	return strconv.FormatInt(v, 16)
}

// detectEmojiExtCipherTalk mirrors CipherTalk's detectEmojiExt: png/jpg/webp
// by magic, anything else is treated as gif.
func detectEmojiExtCipherTalk(buf []byte) string {
	if len(buf) >= 2 && buf[0] == 0x89 && buf[1] == 0x50 {
		return ".png"
	}
	if len(buf) >= 2 && buf[0] == 0xFF && buf[1] == 0xD8 {
		return ".jpg"
	}
	if len(buf) >= 2 && buf[0] == 0x52 && buf[1] == 0x49 {
		return ".webp"
	}
	return ".gif"
}

// wechatEmojiUA is CipherTalk's MicroMessenger user agent, used for emoji CDN
// downloads so WeChat's CDN serves the file.
const wechatEmojiUA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Safari/537.36 MicroMessenger/7.0.20.1781(0x67001431) NetType/WIFI WindowsWechat/3.9.11.17(0x63090b11)"

// downloadEmojiBytes downloads one emoji payload with CipherTalk's
// doDownloadBuffer semantics: http->https upgrade for qq.com/wechat.com, the
// MicroMessenger UA, TLS verification disabled, 8s timeout, at most 5
// redirects (301/302/307, relative locations resolved), non-200 -> nil.
func downloadEmojiBytes(rawURL string) []byte {
	redirects := 0
	cur := rawURL
	for {
		if redirects > 5 {
			return nil
		}
		u := cur
		if strings.HasPrefix(u, "http://") && (strings.Contains(u, "qq.com") || strings.Contains(u, "wechat.com")) {
			u = strings.Replace(u, "http://", "https://", 1)
		}
		req, err := http.NewRequest(http.MethodGet, u, nil)
		if err != nil {
			return nil
		}
		req.Header.Set("User-Agent", wechatEmojiUA)
		req.Header.Set("Accept", "*/*")
		client := &http.Client{
			Timeout: 8 * time.Second,
			Transport: &http.Transport{
				TLSClientConfig: &tls.Config{InsecureSkipVerify: true}, //nolint:gosec // rejectUnauthorized:false (CipherTalk)
			},
			CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse },
		}
		resp, err := client.Do(req)
		if err != nil {
			return nil
		}
		if resp.StatusCode == http.StatusMovedPermanently || resp.StatusCode == http.StatusFound || resp.StatusCode == http.StatusTemporaryRedirect {
			loc := resp.Header.Get("Location")
			resp.Body.Close()
			if loc == "" {
				return nil
			}
			if !strings.HasPrefix(loc, "http") {
				base, berr := url.Parse(u)
				if berr != nil {
					return nil
				}
				ref, rerr := url.Parse(loc)
				if rerr != nil {
					return nil
				}
				cur = base.ResolveReference(ref).String()
			} else {
				cur = loc
			}
			redirects++
			continue
		}
		if resp.StatusCode != http.StatusOK {
			resp.Body.Close()
			return nil
		}
		buf, rerr := io.ReadAll(io.LimitReader(resp.Body, mediaMaxSingleSize))
		resp.Body.Close()
		if rerr != nil || len(buf) == 0 {
			return nil
		}
		return buf
	}
}

// decryptEmojiAES decrypts an emoji encryptUrl payload: AES-128-ECB with the
// key being the first 16 chars of md5(aeskey) as UTF-8, PKCS7 auto-padding
// (CipherTalk downloadAndDecryptEmoji).
func decryptEmojiAES(ciphertext []byte, aesKey string) ([]byte, error) {
	sum := md5.Sum([]byte(aesKey))
	hexKey := hex.EncodeToString(sum[:])[:16]
	return datdecrypt.DecryptAES128ECBPadded([]byte(hexKey), ciphertext)
}

// resolveEmoji resolves one emoji by cacheKey using CipherTalk's exact fetch
// chain: local Emojis cache -> CDN download -> encryptUrl + AES decrypt.
// Results are memoized per export run (CipherTalk's emojiFetchCache), so the
// same emoji appearing in many messages is fetched at most once. Persist
// files are intentionally never consulted.
func (me *mediaExportRuntime) resolveEmoji(cacheKey string, f emojiFields) ([]byte, string) {
	if me.emojiFetch == nil {
		me.emojiFetch = map[string][]byte{}
	}
	if b, ok := me.emojiFetch[cacheKey]; ok {
		if b == nil {
			return nil, ""
		}
		return b, "memo"
	}
	// 1. local Emojis cache: <cachePath>/Emojis/<cacheKey>{.gif,.png,.webp,.jpg,.jpeg,''}
	emojiCacheDir := filepath.Join(me.accountDir, "Emojis")
	for _, cand := range []string{cacheKey + ".gif", cacheKey + ".png", cacheKey + ".webp", cacheKey + ".jpg", cacheKey + ".jpeg", cacheKey} {
		p := filepath.Join(emojiCacheDir, cand)
		if fi, err := os.Stat(p); err == nil && !fi.IsDir() && fi.Size() > 0 {
			if b, rerr := os.ReadFile(p); rerr == nil {
				me.emojiFetch[cacheKey] = b
				return b, "local"
			}
		}
	}
	// 2. CDN download (cdnurl || thumburl)
	if f.cdnURL != "" {
		if b := downloadEmojiBytes(f.cdnURL); len(b) > 0 {
			me.emojiFetch[cacheKey] = b
			return b, "cdn"
		}
	}
	// 3. encryptUrl + per-emoji AES key
	if f.encryptURL != "" && f.aesKey != "" {
		if b := downloadEmojiBytes(f.encryptURL); len(b) > 0 {
			if dec, derr := decryptEmojiAES(b, f.aesKey); derr == nil && len(dec) > 0 {
				me.emojiFetch[cacheKey] = dec
				return dec, "encryptUrl"
			}
		}
	}
	me.emojiFetch[cacheKey] = nil
	return nil, ""
}

// findLocalVideo locates <md5>.mp4 under <accountDir>/msg/video/ (WeChat 4.x
// layout: msg/video/<YYYYMM>/<md5>.mp4). createTime picks the month first,
// then recent months.
func findLocalVideo(accountDir, md5 string, createTime int64) (string, bool) {
	if md5 == "" || accountDir == "" {
		return "", false
	}
	root := filepath.Join(accountDir, "msg", "video")
	for _, month := range candidateMonthDirs(createTime) {
		p := filepath.Join(root, month, md5+".mp4")
		if fi, err := os.Stat(p); err == nil && !fi.IsDir() {
			return p, true
		}
	}
	return "", false
}

// videoMd5Re extras: the video message XML attrs CipherTalk reads
// (videoService.ts collectVideoMd5Candidates) — newmd5/md5/rawmd5/
// originsourcemd5 as attributes of <videomsg> and as child tags.
var (
	videoMd5AttrCands = []string{"newmd5", "md5", "rawmd5", "originsourcemd5"}
	videoMd5TagCands  = []string{"newmd5", "md5", "rawmd5"}
	videoExtRe        = regexp.MustCompile(`(?i)\.(mp4|mov|mkv|avi|flv|wmv|webm|m4v|3gp)$`)
	videoLengthRe     = regexp.MustCompile(`(?i)<videomsg[^>]*\slength\s*=\s*['"](\d+)['"]`)
)

// videoMd5Candidates returns every distinct lowercased md5 candidate for a
// video message, CipherTalk order: preferred md5 first (the generic XML md5),
// then newmd5/md5/rawmd5/originsourcemd5 attrs, then the same tags.
func videoMd5Candidates(raw, content string) []string {
	var out []string
	seen := map[string]bool{}
	add := func(v string) {
		v = strings.ToLower(strings.TrimSpace(v))
		if len(v) >= 16 && isHexString(v) && !seen[v] {
			seen[v] = true
			out = append(out, v)
		}
	}
	if m := extract.MediaMd5FromXML(raw); m != "" {
		add(m)
	}
	if m := extract.MediaMd5FromXML(content); m != "" {
		add(m)
	}
	for _, src := range []string{raw, content} {
		for _, a := range videoMd5AttrCands {
			re := regexp.MustCompile(`(?i)<videomsg[^>]*\s` + a + `\s*=\s*['"]([a-f0-9]{16,32})['"]`)
			if m := re.FindStringSubmatch(src); m != nil {
				add(m[1])
			}
		}
		for _, tag := range videoMd5TagCands {
			re := regexp.MustCompile(`(?i)<` + tag + `>\s*([a-f0-9]{16,32})\s*</` + tag + `>`)
			if m := re.FindStringSubmatch(src); m != nil {
				add(m[1])
			}
		}
	}
	return out
}

// videoLengthFromXML returns the videomsg length attribute (bytes), used by
// the size+md5 fallback scan when the hardlink/name search misses.
func videoLengthFromXML(raw string) int64 {
	if m := videoLengthRe.FindStringSubmatch(raw); m != nil {
		if n, err := strconv.ParseInt(m[1], 10, 64); err == nil {
			return n
		}
	}
	return 0
}

// resolveLocalVideo finds the on-disk video file for one message, CipherTalk
// videoService.getVideoInfo parity:
//
//  1. md5 candidates -> file key candidates (md5s + hardlink.db
//     video_hardlink_info% file_name for each md5, basename-normalized).
//  2. Every year-month dir under msg/video/ (YYYY-MM or YYYYMM, newest first):
//     exact <key>.mp4, else a prefix scan (WeChat appends _raw/_hd/… suffixes)
//     matching video extensions.
//  3. Last resort: size+md5 scan over the once-per-export video index using
//     the XML length attribute (unique size hit wins; multiple hits resolve
//     by md5 of the file content).
//
// Returns the absolute path and the matched key (base without extension) —
// "" when not found.
func resolveLocalVideo(accountDir string, md5Cands []string, rawContent, content string, me *mediaExportRuntime, hli *hardlinkIndex, createTime int64) (string, string) {
	if accountDir == "" || len(md5Cands) == 0 {
		return "", ""
	}
	root := filepath.Join(accountDir, "msg", "video")
	if fi, err := os.Stat(root); err != nil || !fi.IsDir() {
		bridgeLog.Add("debug", "export media: video dir %s missing (%v); skipping size scan", root, err)
		return "", ""
	}

	// 1. File-key candidate set: md5s + hardlink file_names for each md5.
	fileKeys := []string(md5Cands)
	if hli != nil {
		for _, m := range md5Cands {
			if fn := hli.videoByMD5[strings.ToLower(m)]; fn != "" {
				fileKeys = append(fileKeys, videoKeyCandidates(fn)...)
			}
		}
		if len(hli.videoByMD5) == 0 && len(hli.videoBySize) == 0 && len(hli.videoTable) == 0 {
			bridgeLog.Add("debug", "export media: video: hardlink.db has no video_hardlink_info table (%s); md5-name scan only", hli.imageTable)
		}
	}
	fileKeys = dedupeStrings(fileKeys)

	// 2. Exact + prefix scan across all month dirs, newest first.
	dirs, derr := os.ReadDir(root)
	if derr == nil {
		var months []string
		for _, d := range dirs {
			if d.IsDir() {
				months = append(months, d.Name())
			}
		}
		sort.Slice(months, func(i, j int) bool { return months[i] > months[j] }) // newest first
		for _, month := range months {
			dirPath := filepath.Join(root, month)
			entries, eerr := os.ReadDir(dirPath)
			if eerr != nil {
				continue
			}
			files := map[string]string{} // lower base -> base
			for _, e := range entries {
				if !e.IsDir() && videoExtRe.MatchString(e.Name()) {
					files[strings.ToLower(e.Name())] = e.Name()
				}
			}
			for _, key := range fileKeys {
				exact := filepath.Join(dirPath, key+".mp4")
				if fi, err := os.Stat(exact); err == nil && !fi.IsDir() {
					return exact, key
				}
				// Prefix hit: file starts with key (case-insensitive) and is a
				// video extension — covers <key>.mp4, <key>_raw.mp4, <key>_hd.mov…
				var hit string
				for lb, base := range files {
					if strings.HasPrefix(lb, strings.ToLower(key)) && videoExtRe.MatchString(base) {
						hit = base
						break
					}
				}
				if hit != "" {
					return filepath.Join(dirPath, hit), videoStemKey(hit)
				}
			}
		}
	}

	// 3. Size+md5 fallback over the whole video index.
	expectedSize := videoLengthFromXML(rawContent)
	if expectedSize <= 0 {
		expectedSize = videoLengthFromXML(content)
	}
	if expectedSize > 0 {
		if path, key, ok := findVideoBySizeAndMd5(root, expectedSize, md5Cands, me); ok {
			return path, key
		}
	}
	return "", ""
}

// videoKeyCandidates normalizes a hardlink file_name into searchable keys:
// basename, basename without the video extension, and the stem without
// _raw/_hd suffixes (CipherTalk normalizeVideoFileKey / extractFileNameFromPath).
func videoKeyCandidates(fn string) []string {
	var out []string
	base := filepath.Base(fn)
	out = append(out, base)
	stem := base
	if i := strings.LastIndexByte(stem, '.'); i >= 0 {
		stem = stem[:i]
	}
	out = append(out, stem)
	out = append(out, videoStemKey(base))
	return dedupeStrings(out)
}

// videoStemKey strips the video extension and any _raw/_hd/_hd2 suffix.
func videoStemKey(base string) string {
	stem := base
	if i := strings.LastIndexByte(stem, '.'); i >= 0 {
		stem = stem[:i]
	}
	for _, suf := range []string{"_raw", "_hd", "_hd2"} {
		if strings.HasSuffix(strings.ToLower(stem), suf) {
			stem = strings.TrimSuffix(stem, stem[len(stem)-len(suf):])
			break
		}
	}
	return stem
}

// buildVideoIndex lists every video file under msg/video/<month>/ once per
// export (CipherTalk builds it lazily with a 60s TTL; one export is one
// build), used by the size+md5 fallback.
func buildVideoIndex(root string) []videoFileEntry {
	var out []videoFileEntry
	dirs, derr := os.ReadDir(root)
	if derr != nil {
		return out
	}
	for _, d := range dirs {
		if !d.IsDir() {
			continue
		}
		entries, eerr := os.ReadDir(filepath.Join(root, d.Name()))
		if eerr != nil {
			continue
		}
		for _, e := range entries {
			if e.IsDir() || !videoExtRe.MatchString(e.Name()) {
				continue
			}
			p := filepath.Join(root, d.Name(), e.Name())
			fi, ferr := os.Stat(p)
			if ferr != nil {
				continue
			}
			out = append(out, videoFileEntry{base: e.Name(), dir: d.Name(), path: p, size: fi.Size()})
		}
	}
	bridgeLog.Add("debug", "export media: video size index built: %d candidate file(s) under %s", len(out), root)
	return out
}

// findVideoBySizeAndMd5 locates the video whose size matches the XML length
// attr, resolving ambiguous same-size files by md5 of their content
// (CipherTalk findVideoBySizeAndMd5). A unique size hit wins without hashing.
func findVideoBySizeAndMd5(root string, expectedSize int64, md5Cands []string, me *mediaExportRuntime) (string, string, bool) {
	if me == nil || expectedSize <= 0 {
		return "", "", false
	}
	if !me.videoIndexBuilt {
		me.videoIndex = buildVideoIndex(root)
		me.videoIndexBuilt = true
	}
	var sizeHits []videoFileEntry
	for _, v := range me.videoIndex {
		if v.size == expectedSize {
			sizeHits = append(sizeHits, v)
		}
	}
	if len(sizeHits) == 0 {
		return "", "", false
	}
	if len(sizeHits) == 1 {
		v := sizeHits[0]
		bridgeLog.Add("debug", "export media: video size-scan unique hit %s (size=%d)", v.path, v.size)
		return v.path, videoStemKey(v.base), true
	}
	// Multiple same-size candidates: md5 the file content and require a match.
	md5Set := map[string]bool{}
	for _, m := range md5Cands {
		md5Set[strings.ToLower(m)] = true
	}
	if len(md5Set) == 0 {
		bridgeLog.Add("debug", "export media: video size-scan ambiguous (%d files of size %d) and no md5 to disambiguate", len(sizeHits), expectedSize)
		return "", "", false
	}
	for _, v := range sizeHits {
		h, herr := fileMD5Hex(v.path)
		if herr == nil && md5Set[h] {
			bridgeLog.Add("debug", "export media: video size+md5 hit %s (md5=%s)", v.path, h)
			return v.path, videoStemKey(v.base), true
		}
	}
	bridgeLog.Add("debug", "export media: video size-scan ambiguous (%d files of size %d), md5 %v matched none", len(sizeHits), expectedSize, md5Cands)
	return "", "", false
}

// fileMD5Hex returns the MD5 of a file's contents as lowercase hex.
func fileMD5Hex(p string) (string, error) {
	f, err := os.Open(p)
	if err != nil {
		return "", err
	}
	defer f.Close()
	h := md5.New()
	if _, err := io.Copy(h, f); err != nil {
		return "", err
	}
	return hex.EncodeToString(h.Sum(nil)), nil
}

// dedupeStrings removes duplicates preserving first-occurrence order.
func dedupeStrings(in []string) []string {
	out := make([]string, 0, len(in))
	seen := map[string]bool{}
	for _, s := range in {
		if s == "" || seen[s] {
			continue
		}
		seen[s] = true
		out = append(out, s)
	}
	return out
}

// aesNote extracts the "aes=..." fragment from a resolveMediaExport reason
// string, for failure logs (e.g. "aes=memory:none (attempts=4000000)").
func aesNote(reason string) string {
	if i := strings.Index(reason, "aes="); i >= 0 {
		note := reason[i:]
		if j := strings.IndexByte(note, ';'); j >= 0 {
			note = note[:j]
		}
		return strings.TrimSpace(note)
	}
	return "aes=?"
}

// resolveDatPath locates the on-disk .dat for one image message, CipherTalk
// style — session storage dir (the msg_<md5> table hash) x month dir (from
// createTime, then recent months) x image subdir, trying the dat base name
// variants (full-size first, thumbnail last). When the per-session searches
// miss, the CipherTalk fallback chain runs: session-hash-root scan, the
// once-per-export full .dat index, then the fast probabilistic search, then a
// bounded deep walk as the final net.
//
//	cacheKey = md5 from the message XML, else the packed-info dat base name
func resolveDatPath(accountDir, sessionDir, cacheKey string, createTime int64, haveXor bool, xorKey byte, aesKey []byte, idx *fullDatIndex) (string, []byte, error) {
	if cacheKey == "" {
		return "", nil, fmt.Errorf("no md5/datName for image message")
	}
	root := filepath.Join(accountDir, "msg", "attach", sessionDir)
	monthDirs := candidateMonthDirs(createTime)
	for _, month := range monthDirs {
		for _, sub := range []string{"Img", "Image", "mg", "MsgImg", ""} {
			base := filepath.Join(root, month, sub)
			names := datNameVariants(cacheKey)
			for _, nm := range names {
				p := filepath.Join(base, nm)
				data, err := os.ReadFile(p)
				if err != nil {
					continue
				}
				dec, derr := datdecrypt.Decrypt(data, xorKey, aesKey)
				if derr != nil {
					continue
				}
				if !datdecrypt.LooksLikeStrongMedia(dec) {
					// V3 whole-file XOR: derive the key from the clear-text
					// signature (jpg/png/gif/...) when none was supplied.
					if datdecrypt.DetectVersion(data) == 0 {
						if k, ok := datdecrypt.DetectXorKeyFromHeader(data); ok {
							dec2 := datdecrypt.DecryptV3(data, k)
							if datdecrypt.LooksLikeStrongMedia(dec2) {
								return p, dec2, nil
							}
						}
					}
					continue // wrong file variant
				}
				return p, dec, nil
			}
		}
	}

	// CipherTalk resolveDatPath steps 4-9: session-hash-root scan, full .dat
	// index, fast probabilistic search — all under msg/attach.
	attachRoot := filepath.Join(accountDir, "msg", "attach")
	for _, sd := range sessionStorageDirs(sessionDir) {
		sessionRoot := filepath.Join(attachRoot, sd)
		if hit := searchDatInSessionRoot(sessionRoot, cacheKey, true); hit != "" {
			if p, dec, err := tryDecryptDat(hit, xorKey, aesKey); err == nil {
				return p, dec, nil
			}
		}
	}
	if idx != nil {
		if hit := idx.lookupFullDatIndex(cacheKey, true, false); hit != "" {
			if p, dec, err := tryDecryptDat(hit, xorKey, aesKey); err == nil {
				return p, dec, nil
			}
		}
	}
	if hit := fastProbabilisticSearch(attachRoot, cacheKey, createTime); hit != "" {
		if p, dec, err := tryDecryptDat(hit, xorKey, aesKey); err == nil {
			return p, dec, nil
		}
	}

	// Fallback: deep-walk the whole account dir for a <cacheKey>.dat file.
	// WeChat 4.1 may store an image's .dat in a session dir other than the
	// msg_<md5> hash (e.g. the room's own wxid dir), so a bounded recursive
	// search over the account's msg/attach tree recovers those. CipherTalk's
	// imageDecryptService walks the same way (walkForDatInWorker).
	names := datNameVariants(cacheKey)
	if p, dec, err := walkDatSearch(accountDir, names, xorKey, aesKey, 6); err == nil {
		return p, dec, nil
	}
	return "", nil, fmt.Errorf("no .dat found for %q in %s", cacheKey, displayDir(root))
}

// tryDecryptDat decrypts one candidate path with the standard pipeline,
// returning the path+bytes only when a recognizable media signature appears.
func tryDecryptDat(p string, xorKey byte, aesKey []byte) (string, []byte, error) {
	data, rerr := os.ReadFile(p)
	if rerr != nil {
		return "", nil, rerr
	}
	dec, derr := datdecrypt.Decrypt(data, xorKey, aesKey)
	if derr != nil {
		return "", nil, derr
	}
	if !datdecrypt.LooksLikeStrongMedia(dec) {
		if datdecrypt.DetectVersion(data) == 0 {
			if k, ok := datdecrypt.DetectXorKeyFromHeader(data); ok {
				if d2 := datdecrypt.DecryptV3(data, k); datdecrypt.LooksLikeStrongMedia(d2) {
					return p, d2, nil
				}
			}
		}
		return "", nil, fmt.Errorf("no media signature in decrypted data")
	}
	return p, dec, nil
}

// walkDatSearch recursively searches root (bounded depth) for any file whose
// name matches one of the dat variants, decrypting each candidate. Returns
// the first decrypted file with a recognizable media signature.
func walkDatSearch(root string, names []string, xorKey byte, aesKey []byte, maxDepth int) (string, []byte, error) {
	want := map[string]bool{}
	for _, n := range names {
		want[strings.ToLower(n)] = true
	}
	var found string
	var dec []byte
	err := filepath.Walk(root, func(p string, info os.FileInfo, err error) error {
		if err != nil {
			return nil
		}
		if info.IsDir() {
			if p != root && filepath.Base(p) == ".git" {
				return filepath.SkipDir
			}
			return nil
		}
		if !want[strings.ToLower(info.Name())] {
			return nil
		}
		data, rerr := os.ReadFile(p)
		if rerr != nil {
			return nil
		}
		d, derr := datdecrypt.Decrypt(data, xorKey, aesKey)
		if derr != nil || !datdecrypt.LooksLikeStrongMedia(d) {
			// V3 whole-file XOR fallback
			if datdecrypt.DetectVersion(data) == 0 {
				if k, ok := datdecrypt.DetectXorKeyFromHeader(data); ok {
					if d2 := datdecrypt.DecryptV3(data, k); datdecrypt.LooksLikeStrongMedia(d2) {
						found, dec = p, d2
						return filepath.SkipAll
					}
				}
			}
			return nil
		}
		found, dec = p, d
		return filepath.SkipAll
	})
	if err != nil {
		return "", nil, err
	}
	if found == "" || dec == nil {
		return "", nil, fmt.Errorf("no dat match under %s", displayDir(root))
	}
	return found, dec, nil
}

// datNameVariants lists the filename variants searched for a cache key
// (e.g. a 32-hex md5 or a packed-info dat base name): plain, _h/.h/_hd
// full-size suffixes, then _t/.t/_thumb thumbnail suffixes.
func datNameVariants(cacheKey string) []string {
	base := strings.ToLower(cacheKey)
	var names []string
	if strings.HasSuffix(base, ".dat") {
		base = strings.TrimSuffix(base, ".dat")
	}
	for _, s := range []string{"", "_h", ".h", "_hd"} {
		names = append(names, base+s+".dat")
	}
	for _, s := range []string{"_t", ".t", "_thumb"} {
		names = append(names, base+s+".dat")
	}
	var out []string
	seen := map[string]bool{}
	for _, n := range names {
		if !seen[n] {
			seen[n] = true
			out = append(out, n)
		}
	}
	return out
}

// candidateMonthDirs returns createTime's YYYY-MM first, then the previous
// five calendar months (newest first) — the layout WeChat uses under
// msg/attach/<session>/<YYYY-MM>/.
func candidateMonthDirs(createTime int64) []string {
	t := time.Unix(createTime, 0)
	var out []string
	seen := map[string]bool{}
	add := func(tt time.Time) {
		k := tt.Format("2006-01")
		if !seen[k] {
			seen[k] = true
			out = append(out, k)
		}
	}
	add(t)
	for i := 1; i <= 5; i++ {
		add(t.AddDate(0, -i, 0))
	}
	return out
}

var wxidRe = regexp.MustCompile(`(?i)^wxid_[^_]+`)

// cleanSessionDir mirrors CipherTalk's cleanAccountDirName: normalize the
// wxid/chatroom token used for attach storage-hash dirs.
func cleanSessionDir(s string) string {
	s = strings.TrimSpace(s)
	if s == "" {
		return s
	}
	if strings.HasPrefix(strings.ToLower(s), "wxid_") {
		if m := wxidRe.FindString(s); m != "" {
			return m
		}
		return s
	}
	return s
}

// isHexString reports whether s is a non-empty even-length hex string.
func isHexString(s string) bool {
	if len(s) == 0 || len(s)%2 != 0 {
		return false
	}
	for _, c := range s {
		if !((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F')) {
			return false
		}
	}
	return true
}

// snippet returns a compact, log-safe preview of s (escaped, truncated).
func snippet(s string, max int) string {
	if len(s) > max {
		s = s[:max] + "…"
	}
	var b strings.Builder
	for _, r := range s {
		if r == '\n' || r == '\r' || r == '\t' {
			b.WriteByte(' ')
			continue
		}
		b.WriteRune(r)
	}
	return b.String()
}
