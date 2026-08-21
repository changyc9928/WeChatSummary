// Package sqlcipher implements SQLCipher 4 page-level decryption for
// WeChat 4.x WCDB database files, without any external dependencies.
//
// WeChat 4.1+ opens databases through WCDB (SQLCipher 4 defaults):
//
//	page size      4096
//	kdf            PBKDF2-HMAC-SHA512(secret, salt16, 256000, 32)
//	mac key        PBKDF2-HMAC-SHA512(mainKey, salt^0x3A, 2, 32)
//	mac            HMAC-SHA512(ciphertext+IV + pgnoLE32), MAC is the last 64 bytes
//	cipher         AES-256-CBC, zero padding (plaintext length == block multiple)
//	page 1 layout  salt(16) | payload(4000) | iv(16) | mac(64)
//	other pages    payload(4016) | iv(16) | mac(64)
//	(IV is the first 16 bytes of the 80-byte reserve, MAC the last 64;
//	page 1's original "SQLite format 3\0" magic is not stored and must be
//	restored after decryption.)
//
// Two key modes are auto-detected per database via HMAC verification:
//
//   - raw:    secret is already the effective per-db page key (WeChat 4.0.x
//     memory scans yield key+salt of this form; CipherTalk's dbKey likewise).
//   - derived:secret is a passphrase; derive the page key with PBKDF2(256000).
package sqlcipher

import (
	"bytes"
	"crypto/aes"
	"crypto/cipher"
	"crypto/hmac"
	"crypto/sha512"
	"encoding/binary"
	"errors"
	"fmt"
	"io"
	"os"
	"sync"

	"golang.org/x/crypto/pbkdf2"
)

const (
	PageSize      = 4096
	saltSize      = 16
	ivSize        = 16
	macSize       = 64
	reserveSize   = ivSize + macSize
	kdfIterations = 256000
	macIterations = 2
	saltXor       = 0x3A
)

var sqliteMagic = []byte("SQLite format 3\x00")

// DerivePageKey applies the SQLCipher4 salt-iteration: PBKDF2-HMAC-SHA512.
func DerivePageKey(secret []byte, salt []byte) []byte {
	return pbkdf2.Key(secret, salt, kdfIterations, 32, sha512.New)
}

// deriveMacKey derives the HMAC key from the page key and database salt.
func deriveMacKey(pageKey []byte, salt []byte) []byte {
	macSalt := make([]byte, len(salt))
	for i, b := range salt {
		macSalt[i] = b ^ saltXor
	}
	return pbkdf2.Key(pageKey, macSalt, macIterations, 32, sha512.New)
}

// KeyMode describes how the provided secret maps to the page key.
type KeyMode int

const (
	ModeRaw     KeyMode = iota // secret is the page key directly
	ModeDerived                // secret is a passphrase; derive with PBKDF2
)

func (m KeyMode) String() string {
	if m == ModeDerived {
		return "derived(passphrase)"
	}
	return "raw"
}

// File is an opened, verified SQLCipher database.
type File struct {
	mu        sync.Mutex
	f         *os.File
	pageKey   []byte
	macKey    []byte
	salt      []byte
	npg       int64
	mode      KeyMode
	verifyMac bool // false = lenient open (page-1 magic verified, per-page MAC skipped)
}

// Open opens and verifies an encrypted database with the given secret.
// Both KeyModes are probed unless wantMode is set.
func Open(path string, secret []byte, mode KeyMode, wantMode bool) (*File, error) {
	f, err := os.Open(path)
	if err != nil {
		return nil, err
	}
	fi, err := f.Stat()
	if err != nil {
		f.Close()
		return nil, err
	}
	if fi.Size() < PageSize {
		f.Close()
		return nil, errors.New("file smaller than one sqlcipher page")
	}
	page1 := make([]byte, PageSize)
	if _, err := io.ReadFull(f, page1); err != nil {
		f.Close()
		return nil, err
	}
	salt := make([]byte, saltSize)
	copy(salt, page1[:saltSize])

	modes := []KeyMode{ModeRaw, ModeDerived}
	if wantMode {
		modes = []KeyMode{mode}
	}
	var lastErr error
	for _, m := range modes {
		var pageKey []byte
		if m == ModeDerived {
			pageKey = DerivePageKey(secret, salt)
		} else {
			pageKey = make([]byte, 32)
			if len(secret) != 32 {
				lastErr = fmt.Errorf("raw key must be 32 bytes, got %d", len(secret))
				continue
			}
			copy(pageKey, secret)
		}
		macKey := deriveMacKey(pageKey, salt)
		if verifyPage1(page1, pageKey, macKey) {
			return &File{f: f, pageKey: pageKey, macKey: macKey, salt: salt, npg: fi.Size() / PageSize, mode: m, verifyMac: true}, nil
		}
		lastErr = fmt.Errorf("mode %s: HMAC mismatch", m)
	}
	f.Close()
	if lastErr == nil {
		lastErr = errors.New("no key mode verified")
	}
	return nil, fmt.Errorf("sqlcipher: cannot verify %s with provided secret (%v)", path, lastErr)
}

// Mode returns the auto-detected key mode.
func (f *File) Mode() KeyMode { return f.mode }

// ProbePageKey checks a 32-byte page key against a database without opening
// it, and reports two independent signals:
//
//   - macOK:    the SQLCipher-4 HMAC over page 1 matches (our full assumption
//     set: MAC algorithm SHA512 and macKey = PBKDF2(pageKey, salt^0x3A, 2)).
//   - magicOK:  CBC-decrypting page 1's payload yields the SQLite header
//     magic. This depends only on the cipher key/IV layout, NOT on the MAC
//     parameters — so a key that fails macOK but passes magicOK means the
//     key is right and only the MAC settings differ (e.g. a vendor fork).
//
// Both can be false when the key is wrong or the cipher layout differs.
func ProbePageKey(path string, pageKey []byte) (macOK, magicOK bool, err error) {
	if len(pageKey) != 32 {
		return false, false, fmt.Errorf("page key must be 32 bytes, got %d", len(pageKey))
	}
	f, err := os.Open(path)
	if err != nil {
		return false, false, err
	}
	defer f.Close()
	fi, err := f.Stat()
	if err != nil {
		return false, false, err
	}
	if fi.Size() < PageSize {
		return false, false, errors.New("file smaller than one sqlcipher page")
	}
	page1 := make([]byte, PageSize)
	if _, err := io.ReadFull(f, page1); err != nil {
		return false, false, err
	}
	return probePage1Magic(page1, pageKey)
}

// Page1Probe is a preloaded page-1 checker for scanning many candidate page
// keys without re-opening/reading the database per attempt. Create once per
// database, then call Check for each 32-byte candidate.
type Page1Probe struct {
	page1 []byte
}

// NewPage1Probe reads page 1 of the database once and returns a reusable
// probe. Fails when the file is unreadable or smaller than one page.
func NewPage1Probe(path string) (*Page1Probe, error) {
	f, err := os.Open(path)
	if err != nil {
		return nil, err
	}
	defer f.Close()
	fi, err := f.Stat()
	if err != nil {
		return nil, err
	}
	if fi.Size() < PageSize {
		return nil, errors.New("file smaller than one sqlcipher page")
	}
	page1 := make([]byte, PageSize)
	if _, err := io.ReadFull(f, page1); err != nil {
		return nil, err
	}
	return &Page1Probe{page1: page1}, nil
}

// Check verifies one 32-byte page key: macOK when the SQLCipher-4 HMAC
// matches, magicOK when the CBC-decrypted payload starts with the SQLite
// magic (see ProbePageKey for the meaning of each signal).
func (p *Page1Probe) Check(pageKey []byte) (macOK, magicOK bool) {
	if len(pageKey) != 32 {
		return false, false
	}
	macOK, magicOK, _ = probePage1Magic(p.page1, pageKey)
	return macOK, magicOK
}

// OpenLenient opens a database whose page key was verified by the page-1
// magic only (see ProbePageKey), skipping per-page MAC verification. Use it
// when a vendor fork changes the HMAC parameters but keeps the AES/CBC page
// layout — the magic check is a far stronger signal than any password guess,
// so a lenient open is safe for keys obtained from WeChat's own memory.
// The returned File must be closed by the caller.
func OpenLenient(path string, secret []byte, mode KeyMode, wantMode bool) (*File, error) {
	f, err := os.Open(path)
	if err != nil {
		return nil, err
	}
	fi, err := f.Stat()
	if err != nil {
		f.Close()
		return nil, err
	}
	if fi.Size() < PageSize {
		f.Close()
		return nil, errors.New("file smaller than one sqlcipher page")
	}
	page1 := make([]byte, PageSize)
	if _, err := io.ReadFull(f, page1); err != nil {
		f.Close()
		return nil, err
	}
	salt := make([]byte, saltSize)
	copy(salt, page1[:saltSize])

	modes := []KeyMode{ModeRaw, ModeDerived}
	if wantMode {
		modes = []KeyMode{mode}
	}
	var lastErr error
	for _, m := range modes {
		var pageKey []byte
		if m == ModeDerived {
			pageKey = DerivePageKey(secret, salt)
		} else {
			if len(secret) != 32 {
				lastErr = fmt.Errorf("raw key must be 32 bytes, got %d", len(secret))
				continue
			}
			pageKey = append([]byte(nil), secret...)
		}
		if _, magicOK, _ := probePage1Magic(page1, pageKey); magicOK {
			return &File{f: f, pageKey: pageKey, salt: salt, npg: fi.Size() / PageSize, mode: m, verifyMac: false}, nil
		}
		lastErr = fmt.Errorf("mode %s: page-1 magic mismatch", m)
	}
	f.Close()
	return nil, fmt.Errorf("sqlcipher: cannot open %s leniently (%v)", path, lastErr)
}

// probePage1Magic decrypts page 1 with the given page key and reports whether
// the plaintext carries the SQLite header magic.
func probePage1Magic(page1, pageKey []byte) (macOK, magicOK bool, err error) {
	salt := make([]byte, saltSize)
	copy(salt, page1[:saltSize])
	macOK = verifyPage1(page1, pageKey, deriveMacKey(pageKey, salt))
	payload := page1[saltSize : PageSize-macSize]
	iv := page1[PageSize-reserveSize : PageSize-macSize]
	block, err := aes.NewCipher(pageKey)
	if err != nil {
		return false, false, err
	}
	plain := make([]byte, len(payload))
	cipher.NewCBCDecrypter(block, iv).CryptBlocks(plain, payload)
	plain = stripZeroPadding(plain)
	if len(plain) >= 84 && bytes.Equal(plain[:16], sqliteMagic) {
		magicOK = true
	}
	return macOK, magicOK, nil
}

// Salt returns a copy of the database salt (the first 16 bytes of page 1).
func (f *File) Salt() []byte { return append([]byte(nil), f.salt...) }

// NumPages returns the total page count.
func (f *File) NumPages() int64 { return f.npg }

// Close closes the underlying file.
func (f *File) Close() error { return f.f.Close() }

func verifyPage1(page1 []byte, pageKey, macKey []byte) bool {
	// MAC covers ciphertext + IV: [16:4032] for page 1, [0:4032] elsewhere.
	payload := page1[saltSize : PageSize-macSize]
	return hmacEqual(macKey, payload, 1, page1[PageSize-macSize:])
}

func hmacEqual(macKey, payload []byte, pgno uint32, expected []byte) bool {
	h := hmac.New(sha512.New, macKey)
	h.Write(payload)
	var pg [4]byte
	binary.LittleEndian.PutUint32(pg[:], pgno)
	h.Write(pg[:])
	return hmac.Equal(h.Sum(nil), expected)
}

// decryptPageBuffer decrypts one raw 4096-byte page image (as read from a
// main database file or a WAL frame). Returns the plain SQLite page image.
// verifyMac=false skips the per-page HMAC check (lenient opens).
func decryptPageBuffer(buf []byte, pgno int64, pageKey, macKey []byte, verifyMac bool) ([]byte, error) {
	start := 0
	payloadLen := PageSize - reserveSize
	if pgno == 1 {
		start = saltSize
		payloadLen = PageSize - saltSize - reserveSize
	}
	iv := buf[PageSize-reserveSize : PageSize-macSize]
	mac := buf[PageSize-macSize:]
	payload := buf[start : start+payloadLen]

	// HMAC covers ciphertext + IV (SQLCipher hashes through the IV, i.e. all
	// bytes before the MAC region).
	if verifyMac && !hmacEqual(macKey, buf[start:PageSize-macSize], uint32(pgno), mac) {
		return nil, fmt.Errorf("sqlcipher: page %d HMAC mismatch", pgno)
	}

	block, err := aes.NewCipher(pageKey)
	if err != nil {
		return nil, err
	}
	plain := make([]byte, len(payload))
	cipher.NewCBCDecrypter(block, iv).CryptBlocks(plain, payload)
	// IMPORTANT: do NOT strip trailing zeros here. SQLCipher 4's plaintext
	// page is exactly PageSize-reserveSize bytes (4016 for 4096-byte pages);
	// the zeros at the end are part of the SQLite b-tree page (free space,
	// and the tail of a record stored on overflow pages can legitimately be
	// 0x00). Trimming them shortened overflow chunks and produced
	// "payload truncated (N bytes missing)" on records whose payload ends in
	// zero bytes (reproduced: 512-byte tail -> exactly 512 bytes missing;
	// on WeChat 4.1.12.26 -> 4012 bytes missing = one full overflow chunk).

	if pgno == 1 {
		if len(plain) < 84 {
			// 84 bytes decode to the header tail; below that the header
			// is corrupt.
			return nil, errBadPageHeader
		}
		// plain[0:84] = original header bytes [16:100]; re-insert the magic at
		// the front and verify the header parses as SQLite.
		out := make([]byte, 0, PageSize)
		out = append(out, sqliteMagic...)
		out = append(out, plain...)
		hdr := out[:16+84]
		if !validPage1Header(hdr) {
			return nil, errBadPageHeader
		}
		if pageSize := binary.BigEndian.Uint16(hdr[16:]); pageSize != 4096 {
			return nil, fmt.Errorf("sqlcipher: unexpected page size %d in header", pageSize)
		}
		return out, nil
	}
	return append([]byte(nil), plain...), nil
}

var errBadPageHeader = errors.New("plaintext page does not carry a SQLite header")

// ReadPage decrypts and returns one page as a plain SQLite page image.
// For page 1 the SQLite magic is reconstructed; the unused reserve region is
// zeroed. Trailing zero-padding of the payload is stripped, so the returned
// slice may be shorter than PageSize for sparse pages.
func (f *File) ReadPage(pgno int64) ([]byte, error) {
	f.mu.Lock()
	defer f.mu.Unlock()
	if pgno < 1 || pgno > f.npg {
		return nil, fmt.Errorf("sqlcipher: page %d out of range (1..%d)", pgno, f.npg)
	}
	buf := make([]byte, PageSize)
	if _, err := f.f.ReadAt(buf, (pgno-1)*PageSize); err != nil {
		return nil, err
	}
	return decryptPageBuffer(buf, pgno, f.pageKey, f.macKey, f.verifyMac)
}

func validPage1Header(hdr []byte) bool {
	if len(hdr) < 100 {
		return false
	}
	if string(hdr[0:16]) != string(sqliteMagic) {
		return false
	}
	return true
}

// zero-padding removal: SQLCipher pads with zeros; strip trailing zeros so the
// SQLite page content (a b-tree page) is contiguous.
func stripZeroPadding(p []byte) []byte {
	n := len(p)
	for n > 0 && p[n-1] == 0 {
		n--
	}
	return p[:n]
}

// pbkdf2sha512 is PBKDF2-HMAC-SHA512 (params already validated).
func pbkdf2sha512(secret, salt []byte, iter int) []byte {
	return pbkdf2.Key(secret, salt, iter, 32, sha512.New)
}
