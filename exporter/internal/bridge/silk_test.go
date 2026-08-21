package bridge

import (
	"encoding/base64"
	"testing"

	silk "github.com/wdvxdr1123/go-silk"
)

// TestSilkDecodeCannedBlob verifies the vendored SILK decoder (BSD-3-Clause,
// ccgo transpile of the SILK SDK, patched with //go:nocheckptr) decodes a
// genuine #!SILK_V3 container — the exact framing WeChat stores in VoiceInfo
// tables — to 24 kHz s16le PCM. The blob is a real 1 s / 24 kHz encode
// captured once at module-build time (the ccgo encoder's unsafe arithmetic
// would trip checkptr under -race; the production bridge only decodes).
func TestSilkDecodeCannedBlob(t *testing.T) {
	const sampleRate = 24000
	b, err := base64.StdEncoding.DecodeString(testRealSilkBlobB64)
	if err != nil {
		t.Fatal(err)
	}
	if b[0] != 0x02 || string(b[1:10]) != "#!SILK_V3" {
		t.Fatalf("unexpected header: %x %q", b[:2], b[1:10])
	}
	out, err := silk.DecodeSilkBuffToPcm(b, sampleRate)
	if err != nil {
		t.Fatalf("decode: %v", err)
	}
	if len(out) == 0 {
		t.Fatal("empty pcm output")
	}
	// 1 s at 24 kHz mono s16le = 48000 bytes; the encoder trims to ~47116.
	if len(out) < 44000 || len(out) > 48000 {
		t.Fatalf("unexpected pcm length: %d", len(out))
	}
	t.Logf("silk %d bytes -> pcm %d bytes", len(b), len(out))
}

// TestDecodeSilkToWav verifies the full SILK -> WAV pipeline used by the
// voice export: decode then wrap in a standard RIFF/WAVE header.
func TestDecodeSilkToWav(t *testing.T) {
	b, err := base64.StdEncoding.DecodeString(testRealSilkBlobB64)
	if err != nil {
		t.Fatal(err)
	}
	wav := decodeSilkToWav(b)
	if len(wav) < 44 {
		t.Fatalf("wav too short: %d", len(wav))
	}
	if string(wav[:4]) != "RIFF" || string(wav[8:12]) != "WAVE" {
		t.Fatalf("not a wav: %q %q", wav[:4], wav[8:12])
	}
	// PCM data length = wavLen - 44.
	if got := int(wav[40]) | int(wav[41])<<8 | int(wav[42])<<16 | int(wav[43])<<24; got != len(wav)-44 {
		t.Fatalf("data chunk len = %d, file len-44 = %d", got, len(wav)-44)
	}
}