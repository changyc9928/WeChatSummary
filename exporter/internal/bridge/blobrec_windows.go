//go:build windows

package bridge

import (
	"os"
	"path/filepath"
	"strings"
	"syscall"
	"time"
	"unsafe"
)

const (
	regHKCU           = uintptr(0x80000001) // HKEY_CURRENT_USER
	regKeyRead64      = 0x00020019          // KEY_READ | KEY_WOW64_64KEY
	regValueBinary    = 3
	regValueDword     = 4
	cryptProtectUIFrb = 0x01 // CRYPTPROTECT_UI_FORBIDDEN
	maxBlobCandidates = 300
	maxCandidateSize  = 1 << 20 // 1 MiB per file
	maxBlobSize       = 1 << 14 // 16 KiB per DPAPI blob
)

var (
	kernel32 = syscall.NewLazyDLL("kernel32.dll")
	crypt32  = syscall.NewLazyDLL("crypt32.dll")
	advapi32 = syscall.NewLazyDLL("advapi32.dll")

	procLocalFree          = kernel32.NewProc("LocalFree")
	procCryptUnprotectData = crypt32.NewProc("CryptUnprotectData")
	procRegOpenKeyExW      = advapi32.NewProc("RegOpenKeyExW")
	procRegEnumValueW      = advapi32.NewProc("RegEnumValueW")
	procRegQueryValueExW   = advapi32.NewProc("RegQueryValueExW")
	procRegCloseKey        = advapi32.NewProc("RegCloseKey")
)

type dataBlob struct {
	cbData uint32
	pbData uintptr
}

// cryptUnprotectData unwraps a Windows DPAPI-protected blob in the current
// user session (no UI, no external tooling). On failure returns the error.
//
//go:nocheckptr
func cryptUnprotectData(blob []byte) ([]byte, error) {
	if len(blob) == 0 {
		return nil, syscall.EINVAL
	}
	var in dataBlob
	in.cbData = uint32(len(blob))
	if len(blob) > 0 {
		in.pbData = uintptr(unsafe.Pointer(&blob[0]))
	}
	var out dataBlob
	r1, _, e1 := procCryptUnprotectData.Call(
		uintptr(unsafe.Pointer(&in)), 0, 0, 0, 0, cryptProtectUIFrb, uintptr(unsafe.Pointer(&out)))
	if r1 == 0 {
		return nil, e1
	}
	defer procLocalFree.Call(out.pbData)
	return unsafe.Slice((*byte)(unsafe.Pointer(out.pbData)), int(out.cbData)), nil
}

// readRegistryBinary collects REG_BINARY values under one HKCU key path.
func readRegistryBinary(keyPath string) [][]byte {
	var h uintptr
	r, _, _ := procRegOpenKeyExW.Call(regHKCU,
		uintptr(unsafe.Pointer(syscall.StringToUTF16Ptr(keyPath))), 0, regKeyRead64,
		uintptr(unsafe.Pointer(&h)))
	if r != 0 {
		return nil // key absent or unreadable — not an error
	}
	defer procRegCloseKey.Call(h)
	var out [][]byte
	for i := 0; i < 128 && len(out) < maxBlobCandidates; i++ {
		nameBuf := make([]uint16, 256)
		nameLen := uint32(len(nameBuf))
		r, _, _ := procRegEnumValueW.Call(h, uintptr(i),
			uintptr(unsafe.Pointer(&nameBuf[0])), uintptr(unsafe.Pointer(&nameLen)), 0, 0, 0, 0)
		if r != 0 {
			break
		}
		var typ, size uint32
		if r, _, _ = procRegQueryValueExW.Call(h, uintptr(unsafe.Pointer(&nameBuf[0])), 0,
			uintptr(unsafe.Pointer(&typ)), 0, uintptr(unsafe.Pointer(&size))); r != 0 {
			continue
		}
		if typ != regValueBinary && typ != regValueDword {
			continue
		}
		if size == 0 || size > maxBlobSize {
			continue
		}
		buf := make([]byte, size)
		if r, _, _ = procRegQueryValueExW.Call(h, uintptr(unsafe.Pointer(&nameBuf[0])), 0,
			uintptr(unsafe.Pointer(&typ)), uintptr(unsafe.Pointer(&buf[0])),
			uintptr(unsafe.Pointer(&size))); r != 0 {
			continue
		}
		out = append(out, buf[:size])
	}
	return out
}

var blobFileRoots = []string{
	// Roaming
	`Tencent\Weixin`,
	`Tencent\WeChat`,
	`Tencent\Weixin\All Users`,
	`Tencent\WeChat\All Users`,
	// Local
	`Tencent\Weixin`,
	`Tencent\WeChat`,
}

var blobSkipDir = map[string]bool{
	"video": true, "image": true, "audio": true, "voice": true, "emoji": true,
	"media": true, "attach": true, "message": true, "cache": true, "temp": true,
	"logs": true, "log": true, "report": true, "crash": true, "filestorage": true,
}

func isBlobSkipDir(name string) bool {
	n := strings.ToLower(name)
	return blobSkipDir[n] || strings.HasPrefix(n, "wechatappex")
}

// diskBlobCandidates gathers the small binary candidates that might hold the
// DB key: WeChat config files under the two user profiles, the account
// directory, and REG_BINARY values under the Tencent registry keys.
func diskBlobCandidates(accountDir string, logf func(string)) []string {
	var paths []string
	seen := map[string]bool{}
	add := func(p string) {
		if p == "" || seen[p] {
			return
		}
		if fi, err := os.Stat(p); err == nil && fi.Size() <= maxCandidateSize && fi.Size() > 0 {
			seen[p] = true
			paths = append(paths, p)
		}
	}
	walk := func(root string) {
		if root == "" {
			return
		}
		_ = filepath.WalkDir(root, func(p string, d os.DirEntry, err error) error {
			if err != nil {
				return nil
			}
			if d.IsDir() {
				if p != root && isBlobSkipDir(d.Name()) {
					return filepath.SkipDir
				}
				return nil
			}
			if len(paths) >= maxBlobCandidates {
				return filepath.SkipAll
			}
			add(p)
			return nil
		})
	}
	roaming, _ := os.UserConfigDir()
	local := os.Getenv("LOCALAPPDATA")
	for _, r := range blobFileRoots {
		walk(filepath.Join(roaming, r))
		if local != "" {
			walk(filepath.Join(local, r))
		}
	}
	walk(accountDir) // e.g. G:\xwechat_files\wxid_..._bfe8
	_ = logf
	return paths
}

// registryBlobCandidates wraps readRegistryBinary over the Tencent key space.
func registryBlobCandidates(logf func(string)) [][]byte {
	keys := []string{
		`Software\Tencent\WeChat`,
		`Software\Tencent\Weixin`,
		`Software\Tencent\WeChat\All Users`,
		`Software\Tencent\Weixin\All Users`,
	}
	var out [][]byte
	for _, k := range keys {
		out = append(out, readRegistryBinary(k)...)
	}
	return out
}

// recoverDBKeyFromDisk tries every candidate blob in direct and
// DPAPI-unprotected form against the page-1 probe. Returns the verified
// 64-hex key and label, or "" when nothing verifies.
func recoverDBKeyFromDisk(probeRaw, probeAny func(pageKey []byte) string, accountDir string, logf func(string)) (keyHex, label string, candidates int) {
	if probeRaw == nil {
		return "", "", 0
	}
	// Bounded: a miss must not cost minutes of file scanning.
	deadline := time.Now().Add(90 * time.Second)
	// 1. Files (probe raw contents).
	paths := diskBlobCandidates(accountDir, logf)
	// 2. Registry values.
	regs := registryBlobCandidates(logf)
	candidates = len(paths) + len(regs)
	bridgeLogDebug("blob recovery: %d candidate file(s) + %d registry value(s)", len(paths), len(regs))

	tryBytes := func(name string, data []byte) (string, string) {
		if len(data) == 0 || len(data) > maxCandidateSize {
			return "", ""
		}
		if k, l := scanBytesForKeyAny(data, probeRaw, probeAny); k != "" {
			bridgeLogDebug("blob recovery: key verified from %s (%s)", name, l)
			return k, l
		}
		u, uerr := cryptUnprotectData(data)
		if uerr != nil {
			return "", ""
		}
		if k, l := scanBytesForKeyAny(u, probeRaw, probeAny); k != "" {
			bridgeLogDebug("blob recovery: key verified from DPAPI-unprotected %s (%s)", name, l)
			return k, l
		}
		return "", ""
	}

	for _, p := range paths {
		if keyHex != "" {
			break
		}
		if time.Now().After(deadline) {
			bridgeLogDebug("blob recovery: deadline reached while scanning files")
			break
		}
		data, err := os.ReadFile(p)
		if err != nil {
			continue
		}
		keyHex, label = tryBytes(filepath.Base(p), data)
	}
	for _, b := range regs {
		if keyHex != "" {
			break
		}
		if time.Now().After(deadline) {
			bridgeLogDebug("blob recovery: deadline reached while scanning registry")
			break
		}
		keyHex, label = tryBytes("registry", b)
	}
	return keyHex, label, candidates
}
