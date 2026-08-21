package extract

import (
	"bytes"
	"encoding/hex"
	"testing"

	"github.com/klauspost/compress/zstd"

	"wechatsummary/exporter/internal/sqlite"
)

func TestDecodeMessageContentPrefersCompress(t *testing.T) {
	// compress_content holds readable XML; message_content is binary garbage.
	xml := []byte(`<?xml version="1.0"?><msg><img md5="abc123def456abc123def456abc123de"/></msg>`)
	enc, err := zstd.NewWriter(nil)
	if err != nil {
		t.Fatal(err)
	}
	compressed := enc.EncodeAll(xml, nil)
	enc.Close()

	blank := sqlite.Value{}
	got := DecodeMessageContent(sqlite.Value{Kind: sqlite.VBlob, Blob: []byte{0x28, 0xef, 0x2f, 0x60, 0x31}}, sqlite.Value{Kind: sqlite.VBlob, Blob: compressed})
	if got != string(xml) {
		t.Fatalf("zstd compress decode = %q, want %q", got, xml)
	}
	_ = blank

	// hex-encoded compress content
	hexed := sqlite.Value{Kind: sqlite.VText, Text: hex.EncodeToString(xml)}
	if got := DecodeMessageContent(sqlite.Value{}, hexed); got != string(xml) {
		t.Fatalf("hex decode = %q", got)
	}

	// plain text content preserved
	if got := DecodeMessageContent(sqlite.Value{Kind: sqlite.VText, Text: "hello"}, sqlite.Value{}); got != "hello" {
		t.Fatalf("plain = %q", got)
	}
}

func TestDecodeMessageContentZstdMagic(t *testing.T) {
	// Direct zstd magic blob should decompress.
	enc, _ := zstd.NewWriter(nil)
	data := enc.EncodeAll([]byte("<msg><type>47</type></msg>"), nil)
	enc.Close()
	if !bytes.Equal(data[:4], zstdMagic) {
		t.Fatalf("magic mismatch: %x", data[:4])
	}
	got := decodeBinaryContent(data)
	if got != "<msg><type>47</type></msg>" {
		t.Fatalf("zstd decode = %q", got)
	}
}
