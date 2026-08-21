package util

import (
	"encoding/json"
	"errors"
	"os"
	"strings"
)

// readKeyFile extracts a key or passphrase from a JSON cache file.
// Supported shapes (produced by wcdb-key-tool tooling and CipherTalk):
//
//	{"wechat_requests": [...], "wechat_passphrase": "..."}
//	{"db_path": {"enc_key": "...", ...}}
//	{"dbKey": "..."} / {"key": "..."} / {"data_base_key": "..."}
//
// Plain-text files (single hex string) also work.
func readKeyFile(path string) (string, error) {
	b, err := os.ReadFile(path)
	if err != nil {
		return "", err
	}
	s := strings.TrimSpace(string(b))
	if !strings.HasPrefix(s, "{") {
		return s, nil // plain hex file
	}
	var raw map[string]any
	if err := json.Unmarshal(b, &raw); err != nil {
		return "", err
	}
	candidates := []string{"wechat_passphrase", "passphrase", "dbKey", "db_key", "data_base_key", "key", "enc_key"}
	for _, c := range candidates {
		if v, ok := raw[c]; ok {
			if str, ok := v.(string); ok && str != "" {
				return str, nil
			}
		}
	}
	if m, ok := raw["wechat_requests"].([]any); ok && len(m) > 0 {
		if first, ok := m[0].(map[string]any); ok {
			for _, c := range []string{"passphrase", "key", "enc_key"} {
				if v, ok := first[c].(string); ok && v != "" {
					return v, nil
				}
			}
		}
	}
	return "", errors.New("no recognizable key field in JSON file")
}
