//go:build !windows

package scan

// Scan is unavailable on non-Windows platforms: browser-originated memory
// scanning needs a native process-level reader, which only exists in the
// Windows engine. The bridge reports a 501 so the frontend can explain the
// requirement instead of failing silently.
func Scan(_ Options) (*Result, error) {
	return nil, ErrUnsupportedPlatform
}
