// Command wedat decrypts WeChat media cache files (*.dat) — images/audio —
// using the same core the bridge serves over /api/media/*.
//
//	wedat -decrypt file.dat -xor 73 [-aes 0123456789abcdef] > out.bin
//	wedat -dir msg/attach -xor 73 -aes KEY -out media.zip
package main

import (
	"archive/zip"
	"flag"
	"fmt"
	"os"
	"path/filepath"
	"strconv"
	"strings"

	"wechatsummary/exporter/internal/datdecrypt"
)

func main() {
	decrypt := flag.String("decrypt", "", "single .dat file to decrypt to stdout")
	dir := flag.String("dir", "", "directory of .dat files to decrypt into a zip (-out)")
	out := flag.String("out", "media.zip", "zip output for -dir")
	xorHex := flag.String("xor", "", "XOR key (hex); empty = derive from header/templates")
	aes := flag.String("aes", "", "AES key for V2 files (16 ASCII chars)")
	flag.Parse()

	if *decrypt == "" && *dir == "" {
		fmt.Fprintln(os.Stderr, "usage: wedat -decrypt file.dat [-xor 73] [-aes KEY] | wedat -dir DIR [-out x.zip]")
		os.Exit(2)
	}

	var xorKey byte
	haveXor := false
	if *xorHex != "" {
		v, err := strconv.ParseUint(*xorHex, 0, 8)
		if err != nil {
			fmt.Fprintf(os.Stderr, "bad xor key %q (hex 0-ff)\n", *xorHex)
			os.Exit(2)
		}
		xorKey = byte(v)
		haveXor = true
	}
	var aesKey []byte
	if *aes != "" {
		aesKey = datdecrypt.AsciiKey16(*aes)
		if aesKey == nil {
			fmt.Fprintln(os.Stderr, "aes key must be at least 16 ASCII chars")
			os.Exit(2)
		}
	}

	if *decrypt != "" {
		data, err := os.ReadFile(*decrypt)
		if err != nil {
			fmt.Fprintln(os.Stderr, err)
			os.Exit(1)
		}
		if !haveXor {
			if k, ok := datdecrypt.DetectXorKeyFromHeader(data); ok {
				xorKey = k
			} else {
				fmt.Fprintln(os.Stderr, "cannot derive XOR key from header; pass -xor")
				os.Exit(1)
			}
		}
		dec, err := datdecrypt.Decrypt(data, xorKey, aesKey)
		if err != nil {
			fmt.Fprintln(os.Stderr, err)
			os.Exit(1)
		}
		fmt.Fprintf(os.Stderr, "version=%d xor=%02x ext=%s\n", datdecrypt.DetectVersion(data), xorKey, datdecrypt.DetectExt(dec))
		if _, err := os.Stdout.Write(dec); err != nil {
			fmt.Fprintln(os.Stderr, err)
			os.Exit(1)
		}
		return
	}

	// batch
	f, err := os.Create(*out)
	if err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
	zw := zip.NewWriter(f)
	n := 0
	err = filepath.WalkDir(*dir, func(path string, d os.DirEntry, err error) error {
		if err != nil || d.IsDir() {
			if err != nil {
				return err
			}
			return nil
		}
		realExt := filepath.Ext(d.Name())
		if !strings.EqualFold(realExt, ".dat") {
			return nil
		}
		b, rerr := os.ReadFile(path)
		if rerr != nil {
			return nil
		}
		k := xorKey
		if !haveXor && datdecrypt.DetectVersion(b) == 0 {
			if kk, ok := datdecrypt.DetectXorKeyFromHeader(b); ok {
				k = kk
			}
		}
		dec, derr := datdecrypt.Decrypt(b, k, aesKey)
		if derr != nil {
			fmt.Fprintf(os.Stderr, "skip %s: %v\n", path, derr)
			return nil
		}
		extOut := datdecrypt.DetectExt(dec)
		if extOut == "" {
			extOut = ".bin"
		}
		rel, _ := filepath.Rel(*dir, path)
		name := strings.TrimSuffix(filepath.ToSlash(rel), realExt) + extOut
		w, werr := zw.Create(name)
		if werr != nil {
			return werr
		}
		if _, werr := w.Write(dec); werr != nil {
			return werr
		}
		n++
		return nil
	})
	if err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
	if err := zw.Close(); err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
	if err := f.Close(); err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
	fmt.Fprintf(os.Stderr, "wrote %d files to %s\n", n, *out)
}
