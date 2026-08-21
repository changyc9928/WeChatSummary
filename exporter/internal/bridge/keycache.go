package bridge

import (
	"encoding/hex"
	"encoding/json"
	"os"
	"path/filepath"
	"strings"
	"sync"
)

// WeChat per-account SQLCipher keys do not rotate: once a scan verifies a key
// against a database's page-1 HMAC, the same dbPath will verify the same key
// on every later run. The verified-key cache makes repeat scans return in
// milliseconds instead of re-walking the whole process address space.
//
// Entries are never trusted blindly: on each request the cached key is
// re-verified against the live database page 1 (the same probe the scan uses),
// so a rotated or wrong key simply fails verification and falls through to a
// full scan. The file is best-effort — unreadable/unwritable locations are
// ignored and the scan proceeds normally.
type verifiedKeyCache struct {
	mu      sync.Mutex
	path    string
	entries map[string]string // cleaned dbPath -> 64-hex key
}

var (
	keyCacheMu sync.Mutex
	keyCache   *verifiedKeyCache
	// keyCachePathOverride lets tests redirect the cache file.
	keyCachePathOverride string
)

// cachedKeysFile returns the JSON file path for the verified-key cache.
func cachedKeysFile() string {
	if keyCachePathOverride != "" {
		return keyCachePathOverride
	}
	if dir, err := os.UserConfigDir(); err == nil && dir != "" {
		return filepath.Join(dir, "wechatsummary", "key_cache.json")
	}
	// Last resort: next to the executable.
	if exe, err := os.Executable(); err == nil {
		return filepath.Join(filepath.Dir(exe), "key_cache.json")
	}
	return "key_cache.json"
}

func loadKeyCache() *verifiedKeyCache {
	keyCacheMu.Lock()
	defer keyCacheMu.Unlock()
	if keyCache != nil {
		return keyCache
	}
	c := &verifiedKeyCache{path: cachedKeysFile(), entries: map[string]string{}}
	if data, err := os.ReadFile(c.path); err == nil {
		var blob struct {
			Entries map[string]string `json:"entries"`
		}
		if json.Unmarshal(data, &blob) == nil && len(blob.Entries) > 0 {
			for p, k := range blob.Entries {
				p = normalizeCacheKey(p)
				if len(k) == 64 && isHexString(k) {
					c.entries[p] = strings.ToLower(k)
				}
			}
		}
	}
	keyCache = c
	return c
}

// normalizeCacheKey canonicalizes a database path for use as a cache key.
func normalizeCacheKey(dbPath string) string {
	dbPath = strings.TrimSpace(dbPath)
	if dbPath == "" {
		return ""
	}
	abs, err := filepath.Abs(dbPath)
	if err == nil {
		dbPath = abs
	}
	return filepath.Clean(dbPath)
}

// lookup returns a previously verified key for dbPath, if any.
func (c *verifiedKeyCache) lookup(dbPath string) (string, bool) {
	c.mu.Lock()
	defer c.mu.Unlock()
	k, ok := c.entries[normalizeCacheKey(dbPath)]
	return k, ok
}

// remember stores a verified key for dbPath and persists it best-effort.
func (c *verifiedKeyCache) remember(dbPath string, keyHex string) {
	dbPath = normalizeCacheKey(dbPath)
	if dbPath == "" || len(keyHex) != 64 || !isHexString(keyHex) {
		return
	}
	c.mu.Lock()
	c.entries[dbPath] = strings.ToLower(keyHex)
	data, err := json.MarshalIndent(map[string]any{
		"version": 1,
		"entries": c.entries,
	}, "", "  ")
	c.mu.Unlock()
	if err != nil {
		return
	}
	// Best-effort write: mkdir + temp file + rename for atomicity.
	dir := filepath.Dir(c.path)
	if err := os.MkdirAll(dir, 0o755); err != nil {
		return
	}
	tmp := c.path + ".tmp"
	if err := os.WriteFile(tmp, data, 0o644); err != nil {
		return
	}
	_ = os.Rename(tmp, c.path)
}

// invalidate removes a dbPath entry whose cached key failed verification.
func (c *verifiedKeyCache) invalidate(dbPath string) {
	c.mu.Lock()
	delete(c.entries, normalizeCacheKey(dbPath))
	c.mu.Unlock()
}


// hexToKeyBytes decodes a 64-hex cache entry back to 32 raw bytes.
func hexToKeyBytes(s string) []byte {
	if len(s) != 64 {
		return nil
	}
	b, err := hex.DecodeString(s)
	if err != nil {
		return nil
	}
	return b
}
