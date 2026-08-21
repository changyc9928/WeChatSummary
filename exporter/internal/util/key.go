package util

import (
	"encoding/hex"
	"errors"
	"regexp"
	"strconv"
	"strings"
)

// ParseKeyInput accepts a key in several shapes:
//   - raw 32-byte key as hex (64 chars), optionally wrapped in x'...'
//   - 96-hex (64 key + 32 salt) from WeChat 4.0.x memory scans
//   - a path to a JSON key cache file produced by wcdb-key-tool tooling
func ParseKeyInput(input string) (keyBytes []byte, salt []byte, isPassphrase bool, err error) {
	s := strings.TrimSpace(input)
	if s == "" {
		return nil, nil, false, errors.New("empty key input")
	}

	if m := regexp.MustCompile(`^[xX]\s*'([0-9a-fA-F]+)'$`).FindStringSubmatch(s); m != nil {
		s = m[1]
	}

	// JSON cache file? {...,"dbKey":"...."} or {"wechat_passphrase": "..."}
	if strings.Contains(s, "{") || strings.HasSuffix(s, ".json") || strings.Contains(s, "/") || strings.Contains(s, `\`) {
		b, ferr := readKeyFile(s)
		if ferr == nil {
			s = b
		}
	}

	h := strings.TrimSpace(s)
	if !regexp.MustCompile(`^[0-9a-fA-F]+$`).MatchString(h) {
		return nil, nil, false, errors.New("key must be hex (optionally x'...' wrapped)")
	}

	switch len(h) {
	case 64:
		b, derr := hex.DecodeString(h)
		if derr != nil {
			return nil, nil, false, derr
		}
		// 64-hex: either a direct per-db key (4.0.x) or a passphrase (4.1+).
		// We cannot tell apart without a database; caller verifies via HMAC.
		return b, nil, false, nil
	case 96:
		k, derr := hex.DecodeString(h[:64])
		if derr != nil {
			return nil, nil, false, derr
		}
		saltB, derr := hex.DecodeString(h[64:])
		if derr != nil {
			return nil, nil, false, derr
		}
		return k, saltB, false, nil
	default:
		return nil, nil, false, errors.New("key hex length must be 64 or 96, got " + strconv.Itoa(len(h)))
	}
}

// ParseUint parses an integer from various numeric shapes (comma stripped).
func ParseUint(s string) (int64, error) {
	return strconv.ParseInt(strings.TrimSpace(strings.ReplaceAll(s, ",", "")), 10, 64)
}
