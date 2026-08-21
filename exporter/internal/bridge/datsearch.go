package bridge

// This file ports CipherTalk's image .dat resolution helpers verbatim
// (electron/services/imageDecryptService.ts):
//
//   - normalizeDatBase   (line 1823) — repeated [._][a-z] suffix stripping
//   - matchesDatName     (line 1766) — normalized-base equality + regex + includes
//   - isThumbnailDat     (line 1783) — _t / .t / _t_W / _thumb detection
//   - getLikelyDatFileNames (line 2428) — full-size then thumbnail variants
//   - buildFullDatIndex  (line 1577) — index EVERY .dat under msg/attach by
//     normalized base, built once per export, then O(1) lookups
//   - lookupFullDatIndex (line 1607) — thumb-filtered candidate walk
//   - fastProbabilisticSearch (line 1629) — old md5[0:2]/md5[2:4] layout +
//     new session-dir x month-hint layout
//   - buildDatSearchMonthHints (line 2045) — createTime month + current + prev
//   - searchDatInSessionRoot (line 2456) — actual last-6 month dirs x subdirs

import (
	"crypto/md5"
	"fmt"
	"os"
	"path/filepath"
	"regexp"
	"sort"
	"strings"
	"time"
)

// looksLikeMd5 reports whether s is a 32-hex string (CipherTalk looksLikeMd5).
func looksLikeMd5(s string) bool {
	if len(s) != 32 {
		return false
	}
	for _, c := range s {
		if !((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F')) {
			return false
		}
	}
	return true
}

// hasImageVariantSuffix mirrors CipherTalk: base ends with [._]<letter>.
func hasImageVariantSuffix(baseLower string) bool {
	if len(baseLower) < 2 {
		return false
	}
	c0, c1 := baseLower[len(baseLower)-2], baseLower[len(baseLower)-1]
	return (c0 == '.' || c0 == '_') && c1 >= 'a' && c1 <= 'z'
}

// isLikelyImageDatBase mirrors CipherTalk: variant suffix or md5-like.
func isLikelyImageDatBase(baseLower string) bool {
	return hasImageVariantSuffix(baseLower) || looksLikeMd5(baseLower)
}

// normalizeDatBase mirrors CipherTalk line 1823: lowercase; strip a trailing
// .dat/.jpg; then repeatedly strip trailing [._][a-z] suffix groups
// (so "abc_t_W.dat" -> "abc", "abc.h.dat" -> "abc").
func normalizeDatBase(name string) string {
	base := strings.ToLower(name)
	if strings.HasSuffix(base, ".dat") || strings.HasSuffix(base, ".jpg") {
		base = base[:len(base)-4]
	}
	for len(base) >= 2 {
		c0, c1 := base[len(base)-2], base[len(base)-1]
		if (c0 == '.' || c0 == '_') && c1 >= 'a' && c1 <= 'z' {
			base = base[:len(base)-2]
			continue
		}
		break
	}
	return base
}

// matchesDatName mirrors CipherTalk line 1766.
func matchesDatName(fileName, datName string) bool {
	lower := strings.ToLower(fileName)
	base := lower
	if strings.HasSuffix(lower, ".dat") {
		base = lower[:len(lower)-4]
	}
	if normalizeDatBase(base) == normalizeDatBase(strings.ToLower(datName)) {
		return true
	}
	re := regexp.MustCompile(`^` + regexp.QuoteMeta(datName) + `(?:[._][a-z])?\.dat$`)
	if re.MatchString(lower) {
		return true
	}
	return strings.HasSuffix(lower, ".dat") && strings.Contains(lower, datName)
}

// isThumbnailDat mirrors CipherTalk line 1783: _t/_t_W/.t/.t_W dat files and
// *_thumb.dat.
func isThumbnailDat(fileName string) bool {
	lower := strings.ToLower(fileName)
	if strings.HasSuffix(lower, "_thumb.dat") {
		return true
	}
	return regexp.MustCompile(`[._]t(?:_[a-z]+)?\.dat$`).MatchString(lower)
}

// isThumbnailPath mirrors CipherTalk line 1795 (applied to the basename).
func isThumbnailPath(filePath string) bool {
	lower := strings.ToLower(filepath.Base(filePath))
	if isThumbnailDat(lower) {
		return true
	}
	ext := filepath.Ext(lower)
	base := lower
	if ext != "" {
		base = lower[:len(lower)-len(ext)]
	}
	return strings.HasSuffix(base, "_t") || strings.HasSuffix(base, "_thumb") || strings.HasSuffix(base, ".t")
}

// getLikelyDatFileNames mirrors CipherTalk getLikelyDatFileNames (line 2428):
// full-size variants first (plain, _h, .h, _hd), thumbnails last when allowed.
func getLikelyDatFileNames(datName string, allowThumbnail bool) []string {
	lower := strings.ToLower(datName)
	normalized := normalizeDatBase(lower)
	names := []string{
		lower + ".dat",
		normalized + ".dat",
		normalized + "_h.dat",
		normalized + ".h.dat",
		normalized + "_hd.dat",
	}
	if allowThumbnail {
		names = append(names,
			normalized+"_t.dat",
			normalized+".t.dat",
			normalized+"_thumb.dat",
		)
	}
	seen := map[string]bool{}
	out := make([]string, 0, len(names))
	for _, n := range names {
		if n != "" && !seen[n] {
			seen[n] = true
			out = append(out, n)
		}
	}
	return out
}

// searchDatInKnownDir mirrors CipherTalk line 2444: exists-check each likely
// name in one dir, honoring the thumbnail policy.
func searchDatInKnownDir(dirPath, datName string, allowThumbnail bool) string {
	if dirPath == "" {
		return ""
	}
	if fi, err := os.Stat(dirPath); err != nil || !fi.IsDir() {
		return ""
	}
	for _, candidateName := range getLikelyDatFileNames(datName, allowThumbnail) {
		candidatePath := filepath.Join(dirPath, candidateName)
		if fi, err := os.Stat(candidatePath); err == nil && !fi.IsDir() {
			if !allowThumbnail && isThumbnailPath(candidatePath) {
				continue
			}
			return candidatePath
		}
	}
	return ""
}

// searchDatInSessionRoot mirrors CipherTalk line 2456: read the session root's
// actual YYYY-MM dirs (newest 6), then {Img,Image,mg} x likely names.
func searchDatInSessionRoot(sessionRoot, datName string, allowThumbnail bool) string {
	if sessionRoot == "" {
		return ""
	}
	entries, err := os.ReadDir(sessionRoot)
	if err != nil {
		return ""
	}
	monthRe := regexp.MustCompile(`^\d{4}-\d{2}$`)
	var months []string
	for _, e := range entries {
		if e.IsDir() && monthRe.MatchString(e.Name()) {
			months = append(months, e.Name())
		}
	}
	sort.Sort(sort.Reverse(sort.StringSlice(months)))
	if len(months) > 6 {
		months = months[:6]
	}
	subDirs := []string{"Img", "Image", "mg"}
	for _, monthDir := range months {
		for _, subDir := range subDirs {
			imageDir := filepath.Join(sessionRoot, monthDir, subDir)
			if hit := searchDatInKnownDir(imageDir, datName, allowThumbnail); hit != "" {
				return hit
			}
			for _, candidateName := range getLikelyDatFileNames(datName, allowThumbnail) {
				candidatePath := filepath.Join(imageDir, candidateName)
				if fi, err := os.Stat(candidatePath); err == nil && !fi.IsDir() {
					if !allowThumbnail && isThumbnailPath(candidatePath) {
						continue
					}
					return candidatePath
				}
			}
		}
	}
	return ""
}

// buildDatSearchMonthHints mirrors CipherTalk line 2045: createTime month
// first, then the current and previous calendar months.
func buildDatSearchMonthHints(createTime int64) []string {
	var months []string
	add := func(month string) {
		if month != "" {
			for _, m := range months {
				if m == month {
					return
				}
			}
			months = append(months, month)
		}
	}
	add(resolveYearMonth(createTime))
	now := time.Now()
	for i := 0; i < 2; i++ {
		t := now.AddDate(0, -i, 0)
		add(t.Format("2006-01"))
	}
	return months
}

// resolveYearMonth returns createTime's YYYY-MM, handling ms vs s.
func resolveYearMonth(createTime int64) string {
	if createTime <= 0 {
		return ""
	}
	ts := createTime
	if ts > 1e12 {
		ts /= 1000
	}
	return time.Unix(ts, 0).Format("2006-01")
}

// fullDatIndex is the once-per-export index of every .dat under msg/attach,
// keyed by normalized base name (CipherTalk buildFullDatIndex line 1577).
type fullDatIndex struct {
	byBase map[string][]string
	root   string
	count  int
}

// ensureFullDatIndex builds (once) and returns the .dat index for the account.
func ensureFullDatIndex(accountDir string) *fullDatIndex {
	root := filepath.Join(accountDir, "msg", "attach")
	return buildFullDatIndex(root)
}

// buildFullDatIndex walks the whole msg/attach tree and indexes every *.dat
// file by normalizeDatBase(name) (CipherTalk line 1577-1605).
func buildFullDatIndex(root string) *fullDatIndex {
	idx := &fullDatIndex{byBase: map[string][]string{}, root: root}
	if fi, err := os.Stat(root); err != nil || !fi.IsDir() {
		return idx
	}
	queue := []string{root}
	for len(queue) > 0 {
		dir := queue[len(queue)-1]
		queue = queue[:len(queue)-1]
		entries, err := os.ReadDir(dir)
		if err != nil {
			continue
		}
		for _, e := range entries {
			full := filepath.Join(dir, e.Name())
			if e.IsDir() {
				queue = append(queue, full)
			} else if strings.HasSuffix(strings.ToLower(e.Name()), ".dat") {
				idx.count++
				base := normalizeDatBase(e.Name())
				idx.byBase[base] = append(idx.byBase[base], full)
			}
		}
	}
	bridgeLog.Add("info", "export media: full .dat index built: %d file(s) / %d base name(s) under %s", idx.count, len(idx.byBase), displayDir(root))
	return idx
}

// lookupFullDatIndex mirrors CipherTalk line 1607: candidates under the
// normalized base, thumb policy, then matchesDatName + exists check.
func (idx *fullDatIndex) lookupFullDatIndex(datName string, allowThumbnail, thumbOnly bool) string {
	if idx == nil {
		return ""
	}
	candidates := idx.byBase[normalizeDatBase(datName)]
	for _, p := range candidates {
		name := strings.ToLower(filepath.Base(p))
		isThumb := isThumbnailDat(name)
		if thumbOnly && !isThumb {
			continue
		}
		if !allowThumbnail && isThumb {
			continue
		}
		if matchesDatName(filepath.Base(p), datName) {
			if fi, err := os.Stat(p); err == nil && !fi.IsDir() {
				return p
			}
		}
	}
	return ""
}

// fastProbabilisticSearch mirrors CipherTalk line 1629:
// 策略A old layout msg/attach/<md5[0:2]>/<md5[2:4]>/<datName> (+Img/mg/Image)
// 策略B new layout: 32-hex session dirs x month hints x {Img,Image} x targets.
func fastProbabilisticSearch(root, datName string, createTime int64) string {
	if root == "" {
		return ""
	}
	lowerName := strings.ToLower(datName)
	baseName := lowerName
	if strings.HasSuffix(baseName, ".dat") {
		baseName = baseName[:len(baseName)-4]
		baseName = regexp.MustCompile(`[_.](?:t|h|hd|thumb)(?:_[a-z]+)?$`).ReplaceAllString(baseName, "")
	}

	// 策略A: legacy xx/yy layout for 32-hex bases.
	if looksLikeMd5(baseName) {
		dir1, dir2 := baseName[0:2], baseName[2:4]
		candidates := []string{
			filepath.Join(root, dir1, dir2, datName),
			filepath.Join(root, dir1, dir2, "Img", datName),
			filepath.Join(root, dir1, dir2, "mg", datName),
			filepath.Join(root, dir1, dir2, "Image", datName),
		}
		for _, p := range candidates {
			if fi, err := os.Stat(p); err == nil && !fi.IsDir() {
				return p
			}
		}
	}

	// 策略B: new session-hash layout.
	entries, err := os.ReadDir(root)
	if err != nil {
		return ""
	}
	var sessionDirs []string
	for _, e := range entries {
		if e.IsDir() && len(e.Name()) == 32 && isHexString(e.Name()) {
			sessionDirs = append(sessionDirs, e.Name())
		}
	}
	if len(sessionDirs) == 0 {
		return ""
	}
	months := buildDatSearchMonthHints(createTime)
	targetNames := []string{datName}
	if baseName != lowerName {
		targetNames = append(targetNames,
			baseName+".dat",
			baseName+"_t.dat",
			baseName+"_thumb.dat",
		)
	} else if !strings.HasSuffix(lowerName, ".dat") {
		// CipherTalk only pushes the .dat forms when the base changed; but its
		// callers pass md5/dat bases without ".dat", so mirror the intent and
		// also probe the .dat / thumbnail forms (strict superset).
		targetNames = append(targetNames,
			lowerName+".dat",
			lowerName+"_t.dat",
			lowerName+"_thumb.dat",
		)
	}
	batchSize := 20
	for i := 0; i < len(sessionDirs); i += batchSize {
		end := i + batchSize
		if end > len(sessionDirs) {
			end = len(sessionDirs)
		}
		for _, sessDir := range sessionDirs[i:end] {
			for _, month := range months {
				for _, sub := range []string{"Img", "Image"} {
					dirPath := filepath.Join(root, sessDir, month, sub)
					if fi, err := os.Stat(dirPath); err != nil || !fi.IsDir() {
						continue
					}
					for _, name := range targetNames {
						p := filepath.Join(dirPath, name)
						if fi, err := os.Stat(p); err == nil && !fi.IsDir() {
							return p
						}
					}
				}
			}
		}
	}
	return ""
}

// sessionStorageDirs mirrors CipherTalk resolveSessionStorageDirs (line 2394):
// the raw id when md5-like, md5(raw), md5(lower(raw)), plus cleaned variants.
func sessionStorageDirs(sessionID string) []string {
	raw := strings.TrimSpace(sessionID)
	if raw == "" {
		return nil
	}
	var dirs []string
	add := func(v string) {
		v = strings.TrimSpace(v)
		if v == "" {
			return
		}
		lower := strings.ToLower(v)
		if looksLikeMd5(v) && !containsFold(dirs, lower) {
			dirs = append(dirs, lower)
		}
		h := md5Hex(v)
		if !containsFold(dirs, h) {
			dirs = append(dirs, h)
		}
		if lower != v {
			hl := md5Hex(lower)
			if !containsFold(dirs, hl) {
				dirs = append(dirs, hl)
			}
		}
	}
	add(raw)
	cleaned := cleanSessionDir(raw)
	if cleaned != raw {
		add(cleaned)
	}
	return dirs
}

// md5Hex returns the lowercase hex md5 of s (CipherTalk's crypto hash).
func md5Hex(s string) string {
	sum := md5.Sum([]byte(s))
	return fmt.Sprintf("%x", sum)
}
