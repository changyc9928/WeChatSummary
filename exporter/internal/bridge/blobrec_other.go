//go:build !windows

package bridge

// recoverDBKeyFromDisk is a no-op off Windows: DPAPI blob recovery only
// exists in the same user session on the machine running WeChat.
func recoverDBKeyFromDisk(probeRaw, probeAny func(pageKey []byte) string, accountDir string, logf func(string)) (keyHex, label string, candidates int) {
	return "", "", 0
}
