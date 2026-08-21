package sqlcipher

import (
	"bytes"
	"encoding/binary"
	"encoding/hex"
	"fmt"
	"os"
)

// WalSource overlays WAL frames over a main database File so that page reads
// reflect the latest committed state. WAL frames are encrypted exactly like
// main-database pages (verified empirically against SQLCipher 4.17); the
// overlay therefore reuses File's decrypt path, keeping only the highest
// frame per page whose page-HMAC verifies.
type WalSource struct {
	main   *File
	frames map[int64][]byte // ciphertext page images, pgno -> latest frame
	npgs   int64            // logical page count incl. wal-only pages
	walErr error            // set when a -wal exists but could not be applied
	diag   string           // human-readable diagnostics on why frames were skipped
}

// Diag returns diagnostics collected while applying the WAL (salt comparison
// outcomes, read failures). Empty when the WAL applied cleanly.
func (ws *WalSource) Diag() string { return ws.diag }

// OpenWal opens the database and, if a -wal file exists next to it, applies
// its frames. A missing or unusable WAL degrades to the main file only.
func OpenWal(path string, secret []byte, mode KeyMode, wantMode bool) (*WalSource, error) {
	main, err := Open(path, secret, mode, wantMode)
	if err != nil {
		return nil, err
	}
	ws := &WalSource{main: main, frames: map[int64][]byte{}, npgs: main.npg}
	walPath := path + "-wal"
	walBytes, err := os.ReadFile(walPath)
	if err != nil {
		ws.diag = fmt.Sprintf("wal read failed: %v", err)
		return ws, nil // no usable wal; plain main file
	}
	if len(walBytes) < 32+24+PageSize {
		ws.diag = fmt.Sprintf("wal too small (%d B)", len(walBytes))
		return ws, nil // no usable wal; plain main file
	}
	if err := ws.applyWal(walBytes); err != nil {
		ws.walErr = err
		ws.frames = map[int64][]byte{}
	}
	return ws, nil
}

// WalError reports why the WAL could not be applied (nil if none).
func (ws *WalSource) WalError() error { return ws.walErr }

// Frames returns the number of live WAL frames applied (0 when none).
func (ws *WalSource) Frames() int { return len(ws.frames) }

// NumPages returns the logical database size in pages.
func (ws *WalSource) NumPages() int64 { return ws.npgs }

// Mode exposes the underlying key mode.
func (ws *WalSource) Mode() KeyMode { return ws.main.mode }

// Close closes the main file.
func (ws *WalSource) Close() error { return ws.main.Close() }

// ReadPage returns the page from the WAL overlay if present, else the main file.
func (ws *WalSource) ReadPage(pgno int64) ([]byte, error) {
	if img, ok := ws.frames[pgno]; ok {
		return decryptPageBuffer(img, pgno, ws.main.pageKey, ws.main.macKey, ws.main.verifyMac)
	}
	return ws.main.ReadPage(pgno)
}

// genBehind reports whether the frame salt is exactly one WAL generation
// behind the header salt — SQLCipher's deterministic checkpoint-restart
// counter. Observed on WeChat 4.1 (Windows, SQLCipher 4.x) WALs across every
// shard: header a0's high 32 bits are frame-salt high-32 + 1 (e.g. a0
// 7df2cba9… vs frame 7df2cba8…), with the low words regenerated per cycle.
// After a checkpoint the header advances one generation past every frame
// still on disk, so a strict salt=={a0,a1} gate rejects them all.
func genBehind(s1, a0 []byte) bool {
	if len(s1) < 4 || len(a0) < 4 {
		return false
	}
	high := binary.BigEndian.Uint32(s1[0:4])
	ah := binary.BigEndian.Uint32(a0[0:4])
	return high == ah-1 // wraps naturally for ah==0
}

func (ws *WalSource) applyWal(wal []byte) error {
	if len(wal) < 32 {
		return fmt.Errorf("wal too short")
	}
	magic := binary.BigEndian.Uint32(wal[0:4])
	if magic != 0x377f0682 && magic != 0x377f0683 &&
		magic != 0x82067f37 && magic != 0x83067f37 {
		return fmt.Errorf("wal magic 0x%08x not recognized", magic)
	}
	pageSz := int(binary.BigEndian.Uint32(wal[8:12]))
	if pageSz != PageSize {
		return fmt.Errorf("wal page size %d != %d", pageSz, PageSize)
	}
	frameSz := 24 + PageSize
	a0 := wal[16:24]
	a1 := wal[24:32]
	var (
		nSaltSkip     int    // frames whose header-salt region matched neither header salt
		nHmacFail     int    // frames rejected by the page HMAC
		nPgnoSkip     int    // frames with pgno<1
		nApp          int    // frames accepted via matching salt
		nGen2         int    // frames accepted in pass 2 (previous-generation salt + HMAC)
		firstSkipSalt string // hex of the first salt-mismatched frame's salt region
		firstSkipPgno int64
	)
	// Pass 1 — strict: a frame is current only if its salt copy matches one of
	// the salts in the WAL header. After a checkpoint the WAL restarts with a
	// new salt pair while stale frames from the previous cycle remain in the
	// file; those frames are validly encrypted (same keys) but carry content
	// that was already written to the main file, so the salt is the
	// authoritative liveness gate and must not be bypassed.
	for off := 32; off+frameSz <= len(wal); off += frameSz {
		pgno := int64(binary.BigEndian.Uint32(wal[off : off+4]))
		nTruncate := binary.BigEndian.Uint32(wal[off+4 : off+8])
		committed := nTruncate > 0
		if pgno < 1 {
			nPgnoSkip++
			continue
		}
		s1 := wal[off+8 : off+16]
		if !bytes.Equal(s1, a0) && !bytes.Equal(s1, a1) {
			nSaltSkip++
			if firstSkipSalt == "" {
				firstSkipSalt = fmt.Sprintf("%x", s1)
				firstSkipPgno = pgno
			}
			continue // stale cycle
		}
		img := make([]byte, PageSize)
		copy(img, wal[off+24:off+frameSz])
		// validate page HMAC; only accept frames that decrypt consistently
		if _, err := decryptPageBuffer(img, pgno, ws.main.pageKey, ws.main.macKey, ws.main.verifyMac); err != nil {
			nHmacFail++
			if ws.walErr == nil {
				ws.walErr = fmt.Errorf("wal frame pgno=%d rejected: %v", pgno, err)
			}
			continue // stale-cycle frame or corrupt; ignore
		}
		ws.frames[pgno] = img
		nApp++
		if committed && int64(nTruncate) > ws.npgs {
			ws.npgs = int64(nTruncate)
		}
		if pgno > ws.npgs {
			ws.npgs = pgno
		}
	}
	// Pass 2 — previous-generation relaxation. On WeChat 4.1 every frame in the
	// file can carry the generation-1 salt (checkpoint restart advanced the
	// header but left the frames, e.g. because WeChat is running), so the
	// strict pass legitimately finds nothing even though the frames hold the
	// newest un-checkpointed rows. When the strict pass applied zero frames we
	// retry with the deterministic generation-counter rule (high-32 == a0-1),
	// still gated by the page HMAC: the HMAC keys are derived from the DB key
	// alone, so a previous-generation frame that verifies is authentic page
	// content, just from the pre-restart cycle. Keep the LAST frame per pgno.
	if nApp == 0 && nSaltSkip > 0 {
		for off := 32; off+frameSz <= len(wal); off += frameSz {
			pgno := int64(binary.BigEndian.Uint32(wal[off : off+4]))
			nTruncate := binary.BigEndian.Uint32(wal[off+4 : off+8])
			committed := nTruncate > 0
			if pgno < 1 {
				continue
			}
			s1 := wal[off+8 : off+16]
			if !genBehind(s1, a0) && !genBehind(s1, a1) {
				continue
			}
			img := make([]byte, PageSize)
			copy(img, wal[off+24:off+frameSz])
			if _, err := decryptPageBuffer(img, pgno, ws.main.pageKey, ws.main.macKey, ws.main.verifyMac); err != nil {
				nHmacFail++
				if ws.walErr == nil {
					ws.walErr = fmt.Errorf("wal frame pgno=%d (prev-gen) rejected: %v", pgno, err)
				}
				continue
			}
			ws.frames[pgno] = img
			nGen2++
			if committed && int64(nTruncate) > ws.npgs {
				ws.npgs = int64(nTruncate)
			}
			if pgno > ws.npgs {
				ws.npgs = pgno
			}
		}
	}
	if nApp+nGen2 == 0 && nSaltSkip > 0 {
		ws.diag = fmt.Sprintf("wal: all %d frame(s) salt-skipped (hdr a0=%x a1=%x, first frame pgno=%d salt=%s frameHdr=%s; pgno<1=%d hmacFail=%d)",
			nSaltSkip, a0, a1, firstSkipPgno, firstSkipSalt, hex.EncodeToString(wal[32:56]), nPgnoSkip, nHmacFail)
	} else if nGen2 > 0 {
		ws.diag = fmt.Sprintf("wal: pass2 accepted %d previous-generation frame(s) via HMAC (pass1 salt-matched=%d, hdr a0=%x a1=%x, first frame pgno=%d salt=%s; pgno<1=%d hmacFail=%d)",
			nGen2, nApp, a0, a1, firstSkipPgno, firstSkipSalt, nPgnoSkip, nHmacFail)
	}
	return nil
}
