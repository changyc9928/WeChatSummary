//go:build !windows

package bridge

// scanDBKeyViaWeixinDll is a no-op off Windows: the weixin.dll obfuscated-key
// recovery relies on Win32 toolhelp module enumeration and ReadProcessMemory.
func scanDBKeyViaWeixinDll(dbPath string, probe keyProbeFunc, logf func(string)) (keyHex, label string, moduleFound bool) {
	return "", "", false
}
