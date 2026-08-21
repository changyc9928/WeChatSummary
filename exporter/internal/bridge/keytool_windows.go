//go:build windows

package bridge

import (
	"crypto/ed25519"
	"errors"
	"fmt"
	"strings"
	"syscall"
	"unsafe"
)

// keyTool wraps CipherTalk's wechat_key_tool.dll process-scanner.
type keyTool struct {
	dll          *syscall.DLL
	challenge    *syscall.Proc
	procAccount  *syscall.Proc
	procDiag     *syscall.Proc
	procImageKey *syscall.Proc
	free         *syscall.Proc
	key          ed25519.PrivateKey
}

func loadKeyTool(path string) (keyToolImpl, error) {
	dll, err := syscall.LoadDLL(path)
	if err != nil {
		return nil, fmt.Errorf("load %s: %v", path, err)
	}
	proc := func(name string) (*syscall.Proc, error) {
		p, err := dll.FindProc(name)
		if err != nil {
			return nil, fmt.Errorf("export %s missing from %s: %v", name, path, err)
		}
		return p, nil
	}
	ch, err := proc("wkt_challenge")
	if err != nil {
		return nil, err
	}
	sa, err := proc("wkt_scan_account_auth")
	if err != nil {
		return nil, err
	}
	sd, err := proc("wkt_scan_diag_auth")
	if err != nil {
		return nil, err
	}
	ik, err := proc("wkt_scan_image_key_auth")
	if err != nil {
		return nil, err
	}
	fr, err := proc("wkt_free")
	if err != nil {
		return nil, err
	}
	key, err := keyToolKey()
	if err != nil {
		return nil, err
	}
	return &keyTool{dll: dll, challenge: ch, procAccount: sa, procDiag: sd, procImageKey: ik, free: fr, key: key}, nil
}

func (t *keyTool) Close() { t.dll = nil }

// scanAccount walks Weixin.exe's global_config structure via the DLL and
// returns the db_key plus account fields. No database path is needed.
func (t *keyTool) scanAccount() (keyToolAccount, error) {
	sig, err := t.signChallenge()
	if err != nil {
		return keyToolAccount{}, err
	}
	ptr, callErr := t.call(t.procAccount, uintptr(unsafe.Pointer(&sig[0])), uintptr(len(sig)))
	if callErr != nil {
		return keyToolAccount{}, callErr
	}
	if ptr == 0 {
		return keyToolAccount{}, errors.New("wkt_scan_account_auth returned null (Weixin.exe not found or not logged in)")
	}
	defer t.free.Call(ptr)
	return parseKeyToolAccount([]byte(cString(ptr)))
}

// scanDiag falls back to the crypt_key neighborhood scan, anchored on a
// contact.db path (the salt the DLL uses to validate candidates).
func (t *keyTool) scanDiag(contactDbPath string) (keyToolDiag, error) {
	sig, err := t.signChallenge()
	if err != nil {
		return keyToolDiag{}, err
	}
	cpath := append([]byte(contactDbPath), 0)
	ptr, callErr := t.call(t.procDiag, uintptr(unsafe.Pointer(&sig[0])), uintptr(len(sig)), uintptr(unsafe.Pointer(&cpath[0])))
	if callErr != nil {
		return keyToolDiag{}, callErr
	}
	if ptr == 0 {
		return keyToolDiag{}, errors.New("wkt_scan_diag_auth returned null")
	}
	defer t.free.Call(ptr)
	return parseKeyToolDiag([]byte(cString(ptr)))
}

func (t *keyTool) signChallenge() ([]byte, error) {
	var nonce [32]byte
	n, callErr := t.call(t.challenge, uintptr(unsafe.Pointer(&nonce[0])), uintptr(len(nonce)))
	if callErr != nil {
		return nil, callErr
	}
	if int(n) != len(nonce) {
		return nil, fmt.Errorf("wkt_challenge returned %d bytes, want %d", n, len(nonce))
	}
	return ed25519.Sign(t.key, nonce[:]), nil
}

// scanImageAesKey recovers the per-account image/audio media AES key from
// WeChat process memory: wkt_scan_image_key_auth(sig, ciphertext) scans for
// the key that decrypts the given 16-byte template ciphertext and returns a
// NUL-terminated key string (first 16 chars = AES-128 key), mirroring
// CipherTalk's wxKeyService.scanImageAesKey.
func (t *keyTool) scanImageAesKey(ciphertext []byte) (string, error) {
	if len(ciphertext) < 16 {
		return "", errors.New("ciphertext must be at least 16 bytes")
	}
	sig, err := t.signChallenge()
	if err != nil {
		return "", err
	}
	ptr, callErr := t.call(t.procImageKey,
		uintptr(unsafe.Pointer(&sig[0])), uintptr(len(sig)),
		uintptr(unsafe.Pointer(&ciphertext[0])), uintptr(len(ciphertext)))
	if callErr != nil {
		return "", callErr
	}
	if ptr == 0 {
		return "", errors.New("wkt_scan_image_key_auth returned null (WeChat not running, or the key is not in memory yet; open some images in WeChat and retry)")
	}
	defer t.free.Call(ptr)
	key := strings.TrimRight(cString(ptr), "\x00")
	key = strings.TrimSpace(key)
	if len(key) < 16 {
		return "", fmt.Errorf("wkt_scan_image_key_auth returned a %d-char key", len(key))
	}
	return key[:16], nil
}

// call invokes a DLL proc. Return values are the C int/pointer results; the
// last-error value from Proc.Call is stale for these exports (they report
// failure via their return value), so it is ignored.
func (t *keyTool) call(p *syscall.Proc, args ...uintptr) (uintptr, error) {
	r, _, _ := p.Call(args...)
	return r, nil
}

// cString reads a NUL-terminated byte string from an address (DLL output).
func cString(p uintptr) string {
	if p == 0 {
		return ""
	}
	var b []byte
	for i := uintptr(0); i < 4<<20; i++ {
		c := *(*byte)(unsafe.Pointer(p + i))
		if c == 0 {
			break
		}
		b = append(b, c)
	}
	return string(b)
}
