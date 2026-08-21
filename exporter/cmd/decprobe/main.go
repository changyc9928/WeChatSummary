package main

import (
	"encoding/hex"
	"fmt"
	"os"

	"wechatsummary/exporter/internal/datdecrypt"
)

func tryKey(label string, key []byte, files ...string) {
	xor := byte(0x23)
	for _, f := range files {
		b, err := os.ReadFile(f)
		if err != nil {
			continue
		}
		dec, derr := datdecrypt.Decrypt(b, xor, key)
		if derr != nil {
			continue
		}
		ext := datdecrypt.DetectExt(dec)
		fmt.Printf("%s | %s: %d bytes ext=%q head=%x\n", label, f, len(dec), ext, dec[:min(16, len(dec))])
	}
}

func min(a, b int) int {
	if a < b {
		return a
	}
	return b
}

func main() {
	files := []string{
		os.Getenv("HOME") + "/Downloads/wechat-raw/33862c726ffe8009d1e3dbb793379236.dat",
		os.Getenv("HOME") + "/Downloads/wechat-raw/33862c726ffe8009d1e3dbb793379236_t.dat",
		os.Getenv("HOME") + "/Downloads/wechat-raw/33862c726ffe8009d1e3dbb793379236_b.dat",
	}
	// XML aeskey for rowid=71
	ae := "6fcddde297a744739019364a85fb62f6"
	tryKey("xmlAeskey[0:16]", []byte(ae[:16]), files...)
	h, _ := hex.DecodeString(ae)
	tryKey("xmlAeskey-raw16", h, files...)
	// v0.1.23 memory "recovered"
	tryKey("mem-7C938F60902A1BB5", []byte("7C938F60902A1BB5"), files...)
	// DB-key-derived guesses: md5(account), etc.
	_ = hex.EncodeToString
}
