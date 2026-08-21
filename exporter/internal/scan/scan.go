// Package scan implements memory scanning of local processes for WeChat
// WCDB/SQLCipher key material. Only the Windows engine (scan_windows.go)
// performs real memory reads; other platforms return ErrUnsupportedPlatform.
//
// The engine is deliberately pattern-driven: WeChat ships per-version key
// material that the community maintains as byte patterns (see DefaultPatterns
// and the --patterns JSON overlay in cmd/bridge). The bridge validates every
// candidate against a real database via the sqlcipher package (HMAC check),
// so a pattern that is wrong or version-stale simply yields no verified key
// instead of corrupting anything.
package scan

import (
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"strings"
)

// ErrUnsupportedPlatform is returned by Scan on platforms without a memory
// scanning engine (anything that is not a Windows build).
var ErrUnsupportedPlatform = errors.New("memory scanning requires a Windows build of the bridge (GOOS=windows)")

// ErrStopScanning stops a ForEachReadableRegion walk cleanly (return it from
// the callback; the walk aborts without an error).
var ErrStopScanning = errors.New("stop scanning")

// Pattern is one byte sequence to search for in process memory together with
// a human-readable label surfaced in scan results.
type Pattern struct {
	Name        string `json:"name"`
	Description string `json:"description,omitempty"`
	Bytes       []byte `json:"-"`
	Hex         string `json:"hex"`
}

// ProcessInfo describes a process targeted by a scan.
type ProcessInfo struct {
	PID  uint32 `json:"pid"`
	Name string `json:"name"`
}

// Hit is a single pattern match inside a scanned process.
type Hit struct {
	PID        uint32 `json:"pid"`
	Process    string `json:"process"`
	Pattern    string `json:"pattern"`
	Address    uint64 `json:"address"` // absolute virtual address
	MatchedHex string `json:"matchedHex"`
	ContextHex string `json:"contextHex"` // bytes following the match (for inspection)
	Context    string `json:"context"`    // printable ASCII preview around the match
	// WindowHex are the raw bytes captured around the match:
	// [Address-WindowBefore .. Address+len(pattern)+WindowAfter), hex.
	// It is what key-candidate extraction (bridge /api/key/autofind) reads,
	// so the frontend never needs raw memory itself.
	WindowHex string `json:"windowHex,omitempty"`
}

// Result is the aggregate outcome of one scan.
type Result struct {
	Processes      []ProcessInfo `json:"processes"`
	Hits           []Hit         `json:"hits"`
	BytesScanned   int64         `json:"bytesScanned"`
	RegionsScanned int           `json:"regionsScanned"`
	Errors         []string      `json:"errors,omitempty"`
	DurationMs     int64         `json:"durationMs"`
}

// Options configures a scan.
type Options struct {
	// Processes are case-insensitive substrings matched against the process
	// image name (e.g. "weixin", "wechat"). Empty selects DefaultProcesses.
	Processes []string
	// Patterns override the pattern set. Empty selects DefaultPatterns.
	Patterns []Pattern
	// MaxHits caps how many hits are collected per pattern per process.
	// Zero selects a default of 500.
	MaxHits int
	// MaxBytes caps how many bytes are read per process. Zero selects a
	// default of 1 GiB.
	MaxBytes int64
	// WindowBefore / WindowAfter size the raw byte window captured around each
	// match (see Hit.WindowHex). Zero picks sensible defaults (128/128).
	WindowBefore int
	WindowAfter  int
}

// DefaultWindowBefore / DefaultWindowAfter bound the per-hit memory window.
const (
	DefaultWindowBefore = 128
	DefaultWindowAfter  = 128
)

// DefaultProcesses are the WeChat-family image names the scanner looks for.
var DefaultProcesses = []string{"weixin", "wechat", "wecom"}

// DefaultPatterns are anchor markers used by community tooling. They are
// intentionally conservative: each one is cheap to scan for and any hit can
// be verified against a real database by the bridge. Extension patterns for
// a specific WeChat version can be supplied at runtime via a patterns JSON
// file (see cmd/bridge) without rebuilding.
//
// wechat-4x-salt is the fixed 16-byte SQLCipher salt reported by community
// WeChat 4.0.x tooling (verify it against your own MSG.db before relying on
// it — the byte-for-byte value may change between versions; the bytes are
// those of the first 16 bytes of a WCDB page-1 header in WeChat 4.0.x scans).
var DefaultPatterns = []Pattern{
	{
		Name:        "wechat-4x-salt",
		Description: "community-reported WeChat 4.0.x WCDB page-1 salt (verify with your DB)",
		Bytes:       []byte{0x78, 0x5f, 0x75, 0x9e, 0x4d, 0xc2, 0x79, 0x86, 0x6d, 0x3d, 0x9b, 0x5c, 0x9a, 0x21, 0x4c, 0x56},
	},
	{
		Name:        "wcdb",
		Description: "WCDB engine marker string",
		Bytes:       []byte("WCDB"),
	},
}

// defaultMaxHits / defaultMaxBytes keep scans bounded on large processes.
const (
	defaultMaxHits  = 500
	defaultMaxBytes = int64(1 << 30) // 1 GiB per process
)

// effectiveOptions fills unset Options with defaults.
func effectiveOptions(opts Options) Options {
	if len(opts.Processes) == 0 {
		opts.Processes = DefaultProcesses
	}
	if len(opts.Patterns) == 0 {
		opts.Patterns = DefaultPatterns
	}
	if opts.MaxHits <= 0 {
		opts.MaxHits = defaultMaxHits
	}
	if opts.MaxBytes <= 0 {
		opts.MaxBytes = defaultMaxBytes
	}
	if opts.WindowBefore <= 0 {
		opts.WindowBefore = DefaultWindowBefore
	}
	if opts.WindowAfter <= 0 {
		opts.WindowAfter = DefaultWindowAfter
	}
	return opts
}

// LoadPatternsFile reads a JSON patterns overlay:
//
//	{
//	  "patterns": [
//	    {"name": "custom", "description": "...", "hex": "deadbeef"},
//	    "deadbeef"   // bare-hex-string form is also accepted
//	  ]
//	}
//
// Returned patterns are appended to DefaultPatterns; duplicates (same bytes)
// are dropped.
func LoadPatternsFile(path string) ([]Pattern, error) {
	b, err := os.ReadFile(path)
	if err != nil {
		return nil, err
	}
	return ParsePatterns(b)
}

// ParsePatterns parses a patterns overlay document (see LoadPatternsFile).
func ParsePatterns(b []byte) ([]Pattern, error) {
	var doc struct {
		Patterns []jsonRawPattern `json:"patterns"`
	}
	if err := json.Unmarshal(b, &doc); err != nil {
		return nil, fmt.Errorf("scan: parse patterns: %w", err)
	}
	byBytes := make(map[string]bool)
	for _, p := range DefaultPatterns {
		byBytes[string(p.Bytes)] = true
	}
	var out []Pattern
	for i, rp := range doc.Patterns {
		raw := rp.Hex
		if raw == "" {
			raw = rp.Raw // bare string form
		}
		raw = strings.TrimSpace(raw)
		if raw == "" {
			return nil, fmt.Errorf("scan: patterns[%d]: empty hex", i)
		}
		raw = strings.ReplaceAll(raw, " ", "")
		raw = strings.ReplaceAll(raw, "\n", "")
		if strings.HasPrefix(raw, "0x") || strings.HasPrefix(raw, "0X") {
			raw = raw[2:]
		}
		dec, err := hex.DecodeString(raw)
		if err != nil || len(dec) == 0 {
			return nil, fmt.Errorf("scan: patterns[%d]: invalid hex %q", i, rp.Hex)
		}
		name := rp.Name
		if name == "" {
			name = "pattern-" + raw
		}
		if byBytes[string(dec)] {
			continue
		}
		p := Pattern{Name: name, Description: rp.Description, Bytes: dec, Hex: raw}
		byBytes[string(dec)] = true
		out = append(out, p)
	}
	return out, nil
}

type jsonRawPattern struct {
	Name        string `json:"name"`
	Description string `json:"description"`
	Hex         string `json:"hex"`
	Raw         string `json:"raw"`
}

// Match is one pattern found in a byte buffer.
type Match struct {
	PatternIdx int
	Offset     int
}

// searchPatterns finds all occurrences of any pattern in haystack. It does
// not overlap chunks; callers reading memory in chunks must keep a carry of
// (maxPatternLen-1) bytes from the previous chunk (see scanWindows.go).
func searchPatterns(patterns [][]byte, haystack []byte, maxHitsPerPattern int) []Match {
	if len(haystack) == 0 {
		return nil
	}
	var out []Match
	for pi, pat := range patterns {
		if len(pat) == 0 {
			continue
		}
		count := 0
		start := 0
		for {
			idx := indexOf(haystack[start:], pat)
			if idx < 0 {
				break
			}
			out = append(out, Match{PatternIdx: pi, Offset: start + idx})
			count++
			if count >= maxHitsPerPattern {
				break
			}
			start += idx + 1
		}
	}
	return out
}

// indexOf is a small naive search (patterns are tiny, so the KMP overhead is
// not justified).
func indexOf(haystack, needle []byte) int {
	if len(needle) == 0 {
		return 0
	}
	if len(haystack) < len(needle) {
		return -1
	}
	for i := 0; i+len(needle) <= len(haystack); i++ {
		match := true
		for j := 0; j < len(needle); j++ {
			if haystack[i+j] != needle[j] {
				match = false
				break
			}
		}
		if match {
			return i
		}
	}
	return -1
}

// captureWindow returns the bytes from chunkData around a match: up to
// before bytes before the match start, then the matched pattern, then up to
// after bytes following it. Offsets are clamped to the chunk. The caller
// (recordMatches) stores the result hex-encoded as Hit.WindowHex.
func captureWindow(chunkData []byte, matchOffset, patLen, before, after int) []byte {
	var (
		from = matchOffset - before
		to   = matchOffset + patLen + after
	)
	if from < 0 {
		from = 0
	}
	if to > len(chunkData) {
		to = len(chunkData)
	}
	// Only meaningful when the match itself is fully inside the chunk.
	if matchOffset+patLen > len(chunkData) {
		return nil
	}
	return chunkData[from:to]
}

// maxPatternLen returns the longest byte pattern length.
func maxPatternLen(patterns []Pattern) int {
	m := 0
	for _, p := range patterns {
		if len(p.Bytes) > m {
			m = len(p.Bytes)
		}
	}
	return m
}
