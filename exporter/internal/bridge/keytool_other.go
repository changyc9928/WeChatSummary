//go:build !windows

package bridge

import "fmt"

// keyToolUnsupported is returned on non-Windows builds: the DLL scans
// Weixin.exe memory, which only exists on Windows. The stub still satisfies
// keyToolImpl so the handler type-checks on every platform.
type keyTool struct{}

func loadKeyTool(path string) (keyToolImpl, error) {
	return nil, fmt.Errorf("wechat_key_tool.dll integration requires a Windows build of the bridge (GOOS=windows)")
}

func (t *keyTool) Close() {}

func (t *keyTool) scanAccount() (keyToolAccount, error) {
	return keyToolAccount{}, fmt.Errorf("key tool requires a Windows build of the bridge")
}

func (t *keyTool) scanDiag(contactDbPath string) (keyToolDiag, error) {
	return keyToolDiag{}, fmt.Errorf("key tool requires a Windows build of the bridge")
}

func (t *keyTool) scanImageAesKey(ciphertext []byte) (string, error) {
	return "", fmt.Errorf("key tool requires a Windows build of the bridge")
}
