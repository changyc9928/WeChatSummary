package extract

import (
	"bytes"
	"encoding/base64"
	"encoding/hex"
	"strings"
	"sync"
	"unicode/utf8"

	"github.com/klauspost/compress/zstd"

	"wechatsummary/exporter/internal/sqlite"
)

// This file mirrors CipherTalk's decodeMessageContent / decodeMaybeCompressed
// / decodeBinaryContent chain: WeChat 4.1.x stores the readable XML for
// non-text messages in a *different* column (compress_content) than the raw
// binary message_content, and that column can itself be zstd-compressed, hex
// encoded, or base64 encoded. decodeMessageContent prefers compress_content
// and falls back to message_content, applying every encoding it knows.

var zstdMagic = []byte{0x28, 0xb5, 0x2f, 0xfd} // big-endian 0xFD2FB528

var (
	zstdOnce    sync.Once
	zstdDecoder *zstd.Decoder
	zstdDecErr  error
)

func getZstdDecoder() (*zstd.Decoder, error) {
	zstdOnce.Do(func() {
		zstdDecoder, zstdDecErr = zstd.NewReader(nil, zstd.WithDecoderMaxMemory(256<<20))
	})
	return zstdDecoder, zstdDecErr
}

// looksLikeHex reports whether s is long enough and entirely hex digits.
func looksLikeHex(s string) bool {
	if len(s) <= 16 {
		return false
	}
	for _, c := range s {
		if !((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F')) {
			return false
		}
	}
	return true
}

// looksLikeBase64 reports whether s is long enough, length-divisible by 4,
// and base64-shaped — mirroring CipherTalk's looksLikeBase64 (regex
// ^[A-Za-z0-9+/=]+$, no whitespace allowed).
func looksLikeBase64(s string) bool {
	if len(s) <= 16 || len(s)%4 != 0 {
		return false
	}
	for _, c := range s {
		ok := (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '+' || c == '/' || c == '='
		if !ok {
			return false
		}
	}
	return true
}

// decodeBinaryContent decodes one blob: zstd (magic 0xFD2FB528) then UTF-8,
// with a latin1 fallback when UTF-8 has too many replacement characters —
// exactly CipherTalk's decodeBinaryContent.
func decodeBinaryContent(data []byte) string {
	if len(data) == 0 {
		return ""
	}
	if len(data) >= 4 && bytes.Equal(data[:4], zstdMagic) {
		if dec, err := getZstdDecoder(); err == nil {
			if out, err := dec.DecodeAll(data, nil); err == nil {
				return decodeBinaryContent(out)
			}
		}
	}
	if utf8.Valid(data) {
		return string(data)
	}
	// Count replacement chars the UTF-8 decode would produce.
	repl := 0
	for i := 0; i < len(data); {
		r, size := utf8.DecodeRune(data[i:])
		if r == utf8.RuneError && size <= 1 {
			repl++
		}
		i += size
	}
	if repl < len(data)*2/10 {
		// Acceptable: return UTF-8 with invalid bytes dropped.
		return strings.ToValidUTF8(string(data), "")
	}
	// latin1 passthrough (each byte -> same code point)
	var sb strings.Builder
	sb.Grow(len(data))
	for _, b := range data {
		sb.WriteRune(rune(b))
	}
	return sb.String()
}

// decodeMaybeCompressed handles a value that may be a blob or an
// encoded/compressed string, mirroring CipherTalk's decodeMaybeCompressed.
func decodeMaybeCompressed(raw sqlite.Value) string {
	switch raw.Kind {
	case sqlite.VBlob:
		return decodeBinaryContent(raw.Blob)
	case sqlite.VText:
		s := raw.Text
		if s == "" {
			return ""
		}
		if len(s) > 16 && looksLikeHex(s) {
			if b, err := hex.DecodeString(s); err == nil && len(b) > 0 {
				return decodeBinaryContent(b)
			}
		}
		if len(s) > 16 && looksLikeBase64(s) {
			clean := strings.Map(func(r rune) rune {
				if r == '\n' || r == '\r' || r == ' ' {
					return -1
				}
				return r
			}, s)
			if b, err := base64.StdEncoding.DecodeString(clean); err == nil && len(b) > 0 {
				return decodeBinaryContent(b)
			}
		}
		return s
	}
	return ""
}

// DecodeMessageContent returns the readable content for one message row,
// preferring compress_content over message_content — CipherTalk's
// decodeMessageContent. Both values may be blob or text.
func DecodeMessageContent(messageContent, compressContent sqlite.Value) string {
	content := decodeMaybeCompressed(compressContent)
	if content == "" {
		content = decodeMaybeCompressed(messageContent)
	}
	return content
}
