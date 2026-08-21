//go:build !windows

package scan

// ForEachReadableRegion is not available off-Windows: reading another
// process's memory is a Windows-only capability of the bridge.
func ForEachReadableRegion(pid uint32, maxBytes int64, fn func(addr uintptr, data []byte) error) (int64, []error) {
	return 0, []error{ErrUnsupportedPlatform}
}

// FindWeChatProcesses is not available off-Windows.
func FindWeChatProcesses() ([]ProcessInfo, error) {
	return nil, ErrUnsupportedPlatform
}
