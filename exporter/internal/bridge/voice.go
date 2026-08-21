package bridge

import (
	"archive/zip"
	"encoding/base64"
	"encoding/binary"
	"encoding/hex"
	"fmt"
	"os"
	"path/filepath"
	"regexp"
	"sort"
	"strings"
	"time"

	silk "github.com/wdvxdr1123/go-silk"

	"wechatsummary/exporter/internal/extract"
	"wechatsummary/exporter/internal/sqlcipher"
	"wechatsummary/exporter/internal/sqlite"
)

// Voice export, CipherTalk exportService.ts parity (the "语音导出" block, LocalType
// 34). WeChat 4.1 keeps voice SILK blobs in VoiceInfo% tables inside the same
// message DBs that hold msg_<md5>; we probe every opened shard (CipherTalk's
// findMediaDbs walks db_storage for media*.db — on Windows 4.1 the voice rows
// live in the message/media shards themselves). Blobs are SILK v3
// (0x02 + "#!SILK_V3" + length-prefixed frame blocks), decoded to 24 kHz
// 16-bit mono PCM with the BSD-3-Clause pure-Go SILK SDK port and wrapped in a
// standard WAV file, then written into the zip under
// voices/<YYYY-MM-DD>/<createTime>_<localId>.wav — byte-format identical to
// CipherTalk's silkWasm.decode(24000) + createWavBuffer output.
//
// Matching replicates CipherTalk's exact fallback chain per voice message:
//
//	A. svr_id exact match (via Name2Id rowid for sessionId/myWxid)
//	B. chatNameId + createTime, ORDER BY rowid ASC, pick the Nth
//	   (sameTimeIndexMap) row for equal createTime
//	C. createTime only, ORDER BY rowid ASC, pick the Nth row
//
// Run once per export, after the image/video/emoji media loop, over the merged
// messages slice so mutations land where messages.json marshals.

// voiceDB is one probe result: a message/media shard that exposes readable
// VoiceInfo% tables with data + time columns, plus optional chat/svr/Name2Id
// columns (CipherTalk's VoiceDbInfo). Keeps the open shard + table pointers so
// lookups need no global registry (safe for concurrent exports).
type voiceDB struct {
	db          *sqlite.DB
	voiceTable  *sqlite.Table
	dataColumn  string
	timeColumn  string
	chatNameCol string // may be ""
	svrIDCol    string // may be ""
	name2IDTbl  string // may be ""
	name2IDTab  *sqlite.Table
	cols        []string
}

var (
	voiceDataColCands = []string{"voice_data", "buf", "voicebuf", "data"}
	voiceTimeColCands = []string{"create_time", "createtime", "time"}
	voiceChatColCands = []string{"chat_name_id", "chatnameid", "chat_nameid"}
	voiceSvrColCands  = []string{"msg_svr_id", "msgsvrid", "svr_id", "svrid", "server_id", "serverid"}
)

// siblingMediaDBs returns the media_*.db files in the same directory as the
// message shards (WeChat 4.1.12 keeps VoiceInfo% voice tables there).
func siblingMediaDBs(dbPath string) []string {
	dir := filepath.Dir(dbPath)
	entries, err := os.ReadDir(dir)
	if err != nil {
		return nil
	}
	seen := map[string]bool{}
	var out []string
	for _, e := range entries {
		if e.IsDir() {
			continue
		}
		l := strings.ToLower(e.Name())
		if !(l == "media.db" || mediaDbFileRe.MatchString(l)) {
			continue
		}
		p := filepath.Join(dir, e.Name())
		if !seen[p] {
			seen[p] = true
			out = append(out, p)
		}
	}
	sort.Strings(out)
	return out
}

var mediaDbFileRe = regexp.MustCompile(`^media_\d+\.db$`)

// probesVoiceDBs finds VoiceInfo% tables across every opened shard/message DB
// and records their data/time/chat/svr columns (CipherTalk step 4).
func probesVoiceDBs(dbs []*sqlite.DB) []voiceDB {
	var out []voiceDB
	for _, db := range dbs {
		var voiceTables []string
		for _, ti := range db.Tables() {
			if ti.Type != "table" {
				continue
			}
			if strings.HasPrefix(strings.ToLower(ti.Name), "voiceinfo") {
				voiceTables = append(voiceTables, ti.Name)
			}
		}
		sort.Strings(voiceTables)
		for _, tn := range voiceTables {
			ti, err := db.Table(tn)
			if err != nil {
				continue
			}
			var sqlStr string
			for _, t := range db.Tables() {
				if strings.EqualFold(t.Name, tn) {
					sqlStr = t.SQL
					break
				}
			}
			cols := parseCreateColumns(sqlStr)
			dataCol := pickCol(cols, voiceDataColCands)
			timeCol := pickCol(cols, voiceTimeColCands)
			if dataCol == "" || timeCol == "" {
				bridgeLog.Add("debug", "voice: %s lacks data/time column (got %v); skipping", tn, cols)
				continue
			}
			vdb := voiceDB{
				db:          db,
				voiceTable:  ti,
				dataColumn:  dataCol,
				timeColumn:  timeCol,
				chatNameCol: pickCol(cols, voiceChatColCands),
				svrIDCol:    pickCol(cols, voiceSvrColCands),
				cols:        cols,
			}
			// Name2Id tables in the same shard (CipherTalk probes them per DB).
			var n2i []string
			for _, ti2 := range db.Tables() {
				if ti2.Type != "table" {
					continue
				}
				if strings.HasPrefix(strings.ToLower(ti2.Name), "name2id") {
					n2i = append(n2i, ti2.Name)
				}
			}
			if len(n2i) > 0 {
				sort.Strings(n2i)
				vdb.name2IDTbl = n2i[0]
				vdb.name2IDTab, _ = db.Table(n2i[0])
			}
			out = append(out, vdb)
		}
	}
	return out
}

func pickCol(cols []string, cands []string) string {
	for _, c := range cols {
		for _, want := range cands {
			if strings.EqualFold(c, want) {
				return c
			}
		}
	}
	return ""
}

// isHexOnly reports whether s consists solely of hex digits.
func isHexOnly(s string) bool {
	if s == "" {
		return false
	}
	for _, c := range s {
		if !((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F')) {
			return false
		}
	}
	return true
}

// voiceMsgRef is one voice message to export (CipherTalk's VoiceMsgRef).
type voiceMsgRef struct {
	createTime int64
	localID    int64
	serverID   int64
}

// exportVoice runs the whole voice pipeline. Returns saved/failed counts and
// mutates msgs (content -> "[语音消息] voices/....wav"). Runs after the media
// loop so both loops never double-count; CipherTalk's voice loop is
// independent of images/videos by design.
func exportVoice(zw *zip.Writer, dbPath string, secret []byte, dbs []*sqlite.DB, msgs []extract.Message, sessionCands []string) (saved int, failed int) {
	// 1. Collect voice messages (CipherTalk step 1: LocalType 34, createTime
	//    within the export window already filtered by the extractor).
	var refs []voiceMsgRef
	for _, m := range msgs {
		if m.LocalType != 34 || m.CreateTime == 0 {
			continue
		}
		refs = append(refs, voiceMsgRef{
			createTime: m.CreateTime,
			localID:    m.LocalID,
			serverID:   m.PlatformMessageID,
		})
	}
	if len(refs) == 0 {
		return 0, 0
	}
	// CipherTalk sorts voice messages by (createTime, localID) to align with
	// VoiceInfo.rowid, then builds the same-time index keyed by localId.
	sort.SliceStable(refs, func(i, j int) bool {
		if refs[i].createTime != refs[j].createTime {
			return refs[i].createTime < refs[j].createTime
		}
		return refs[i].localID < refs[j].localID
	})
	sameTimeIdx := map[int64]map[int64]int{}
	for _, r := range refs {
		g := sameTimeIdx[r.createTime]
		if g == nil {
			g = map[int64]int{}
			sameTimeIdx[r.createTime] = g
		}
		if _, has := g[r.localID]; !has {
			g[r.localID] = len(g)
		}
	}
	bridgeLog.Add("info", "voice: collected %d voice message(s) (createTime-sorted, same-time index built)", len(refs))

	// 2. Probe VoiceInfo tables in every opened shard AND in the account's
//    media_*.db files. WeChat 4.1.12 keeps VoiceInfo% in media_0.db /
//    media_1.db (siblings of the message shards under db_storage/message),
//    NOT in the msg_<md5> shards — so a voice export that only probes the
//    opened message DBs finds nothing (the "no VoiceInfo table found"
//    symptom). Open the media DBs with the same key and probe them too.
	var mediaAll []*sqlite.DB
	var mediaFiles []*sqlcipher.WalSource
	defer func() {
		for _, f := range mediaFiles {
			f.Close()
		}
	}()
	if dbPath != "" {
		for _, p := range siblingMediaDBs(dbPath) {
			f, oerr := sqlcipher.OpenWal(p, secret, sqlcipher.ModeRaw, false)
			if oerr != nil {
				bridgeLog.Add("warn", "voice: skip media DB %s (open failed: %v)", filepath.Base(p), oerr)
				continue
			}
			if fr := f.Frames(); fr > 0 {
				bridgeLog.Add("info", "voice: %s wal overlay applied (%d frame(s))", filepath.Base(p), fr)
			} else if we := f.WalError(); we != nil {
				bridgeLog.Add("warn", "voice: %s -wal not applied (%v)", filepath.Base(p), we)
			}
			db, derr := sqlite.Open(f)
			if derr != nil {
				f.Close()
				bridgeLog.Add("warn", "voice: skip media DB %s (parse failed: %v)", filepath.Base(p), derr)
				continue
			}
			mediaFiles = append(mediaFiles, f)
			mediaAll = append(mediaAll, db)
		}
		if len(mediaAll) > 0 {
			bridgeLog.Add("info", "voice: opened %d media DB(s) for the VoiceInfo probe (%d already-open shard(s))", len(mediaAll), len(dbs))
		}
	}
	vdbs := probesVoiceDBs(append(dbs, mediaAll...))
	if len(vdbs) == 0 {
		bridgeLog.Add("info", "voice: no VoiceInfo table found in %d shard(s) (+%d media DB(s)); skipping voice export", len(dbs), len(mediaAll))
		return 0, len(refs)
	}
	bridgeLog.Add("info", "voice: probed %d VoiceInfo table(s) across %d shard(s) (+%d media DB(s))", len(vdbs), len(dbs), len(mediaAll))

	// 3. Resolve + decode + write each voice message.
	written := map[string]bool{}
	emitted := map[int64]string{} // localID -> relative path (zip reuse)
	for _, ref := range refs {
		targetIdx := sameTimeIdx[ref.createTime][ref.localID]
		data := findVoiceBlob(ref, sessionCands, vdbs, targetIdx)
		if data == nil {
			failed++
			bridgeLog.Add("debug", "voice: blob not found (createTime=%d localId=%d svrId=%d)", ref.createTime, ref.localID, ref.serverID)
			continue
		}
		wav := decodeSilkToWav(data)
		if wav == nil {
			failed++
			bridgeLog.Add("debug", "voice: SILK decode failed (createTime=%d localId=%d svrId=%d, %d bytes)", ref.createTime, ref.localID, ref.serverID, len(data))
			continue
		}
		df := time.Unix(ref.createTime, 0).Format("20060102") // CipherTalk dateFolder: YYYYMMDD, no dashes
		fileName := fmt.Sprintf("%d_%d.wav", ref.createTime, ref.localID)
		if ref.localID <= 0 {
			fileName = fmt.Sprintf("%d.wav", ref.createTime)
		}
		rel := fmt.Sprintf("voices/%s/%s", df, fileName)
		if !written[rel] {
			if werr := writeZipEntry(zw, rel, wav); werr != nil {
				failed++
				bridgeLog.Add("debug", "voice: %s zip failed: %v", rel, werr)
				continue
			}
			written[rel] = true
		}
		emitted[ref.localID] = rel
		saved++
		bridgeLog.Add("debug", "voice: %s -> %s (%d bytes)", ref.voiceKey(), rel, len(wav))
	}

	// 4. Rewrite message content to the voice path (CipherTalk voicePathMap).
	for i := range msgs {
		m := &msgs[i]
		if m.LocalType != 34 {
			continue
		}
		if rel, ok := emitted[m.LocalID]; ok {
			m.Content = "[语音消息] " + rel
			m.Type = "语音消息"
		}
	}
	bridgeLog.Add("info", "voice: saved=%d failed=%d", saved, failed)
	return saved, failed
}

func (v voiceMsgRef) voiceKey() string {
	if v.localID > 0 {
		return fmt.Sprintf("%d_%d", v.createTime, v.localID)
	}
	return fmt.Sprintf("%d", v.createTime)
}

// findVoiceBlob locates the SILK blob for one voice message across all probed
// VoiceInfo tables, CipherTalk strategies A -> B -> C.
func findVoiceBlob(ref voiceMsgRef, sessionCands []string, vdbs []voiceDB, targetIdx int) []byte {
	for _, vdb := range vdbs {
		// Strategy A: svr_id exact match.
		if vdb.svrIDCol != "" && ref.serverID > 0 {
			if vdb.chatNameCol != "" && vdb.name2IDTab != nil {
				for _, cand := range sessionCands {
					if rid := name2IDRowid(vdb, cand); rid > 0 {
						if rows := scanVoiceFiltered(vdb, -1, ref.serverID, rid); len(rows) > 0 {
							return rows[0]
						}
					}
				}
			}
			if rows := scanVoiceFiltered(vdb, -1, ref.serverID, -1); len(rows) > 0 {
				return rows[0]
			}
		}
		// Strategy B: chatNameId + createTime by rowid order.
		if vdb.chatNameCol != "" && vdb.name2IDTab != nil {
			for _, cand := range sessionCands {
				if rid := name2IDRowid(vdb, cand); rid > 0 {
					rows := scanVoiceFiltered(vdb, ref.createTime, -1, rid)
					if len(rows) == 0 {
						continue
					}
					if pick := rows[minInt(targetIdx, len(rows)-1)]; pick != nil {
						return pick
					}
				}
			}
		}
		// Strategy C: createTime only.
		rows := scanVoiceFiltered(vdb, ref.createTime, -1, -1)
		if len(rows) > 0 {
			if pick := rows[minInt(targetIdx, len(rows)-1)]; pick != nil {
				return pick
			}
		}
	}
	return nil
}

func minInt(a, b int) int {
	if a < b {
		return a
	}
	return b
}

// canMatch reports whether a VoiceInfo row satisfies the filter (time >= 0
// means exact, svrID >= 0 means exact, chatID >= 0 means exact; -1 = wildcard).
func rowMatchesFilter(vdb voiceDB, row []sqlite.Value, time, svrID, chatID int64) bool {
	if time >= 0 {
		ti := indexFold(vdb.cols, vdb.timeColumn)
		if ti < 0 || ti >= len(row) || rowIntVal(row[ti]) != time {
			return false
		}
	}
	if svrID >= 0 && vdb.svrIDCol != "" {
		si := indexFold(vdb.cols, vdb.svrIDCol)
		if si < 0 || si >= len(row) || rowIntVal(row[si]) != svrID {
			return false
		}
	}
	if chatID >= 0 && vdb.chatNameCol != "" {
		ci := indexFold(vdb.cols, vdb.chatNameCol)
		if ci < 0 || ci >= len(row) || rowIntVal(row[ci]) != chatID {
			return false
		}
	}
	return true
}

// scanVoiceFiltered reads every row of one VoiceInfo table with the raw
// reader, filters in Go (the WCDB raw-reader layer exposes no WHERE clause;
// this is the CipherTalk WHERE equivalent), and returns the data blobs in
// b-tree rowid order (ORDER BY rowid ASC).
func scanVoiceFiltered(vdb voiceDB, time, svrID, chatID int64) [][]byte {
	rows, _, rerr := vdb.voiceTable.ScanRowids(0)
	if rerr != nil {
		bridgeLog.Add("debug", "voice: scan %s failed: %v", vdb.voiceTable.Name(), rerr)
		return nil
	}
	dataIdx := indexFold(vdb.cols, vdb.dataColumn)
	if dataIdx < 0 {
		return nil
	}
	var out [][]byte
	for _, r := range rows {
		if !rowMatchesFilter(vdb, r, time, svrID, chatID) {
			continue
		}
		if dataIdx >= len(r) {
			continue
		}
		if b, ok := voiceBlobBytes(r[dataIdx]); ok {
			out = append(out, b)
		}
	}
	return out
}

// name2IDRowid returns the Name2Id rowid for a session candidate name in the
// VoiceInfo shard (CipherTalk: SELECT rowid FROM Name2Id WHERE user_name = ?).
func name2IDRowid(vdb voiceDB, name string) int64 {
	if vdb.name2IDTab == nil {
		return 0
	}
	rows, ids, rerr := vdb.name2IDTab.ScanRowids(0)
	if rerr != nil {
		return 0
	}
	for i, r := range rows {
		if len(r) == 0 {
			continue
		}
		if rowStrVal(r[0]) == name {
			if i < len(ids) {
				return ids[i]
			}
			return 0
		}
	}
	return 0
}

// decodeSilkToWav decodes a WeChat SILK v3 blob (0x02 + "#!SILK_V3" + frames)
// to 24 kHz 16-bit mono PCM and wraps it in a WAV container — byte-format
// identical to CipherTalk's silkWasm.decode(24000) + createWavBuffer(24000).
// Returns nil when the blob isn't SILK or decoding fails.
func decodeSilkToWav(silkData []byte) []byte {
	if len(silkData) < 10 {
		return nil
	}
	// Accept both 0x02 +#!SILK_V3 (WeChat container) and bare #!SILK_V3.
	offset := 0
	if silkData[0] == 0x02 {
		offset = 1
	}
	if string(silkData[offset:offset+9]) != "#!SILK_V3" {
		return nil
	}
	const sampleRate = 24000
	pcm, err := silk.DecodeSilkBuffToPcm(silkData, sampleRate)
	if err != nil || len(pcm) == 0 {
		return nil
	}
	return wavFromPCM(pcm, sampleRate, 1, 16)
}

// wavFromPCM wraps 16-bit little-endian PCM in a RIFF/WAVE container
// (CipherTalk createWavBuffer: 44-byte header, PCM format 1, 24 kHz).
func wavFromPCM(pcm []byte, sampleRate, channels, bits int) []byte {
	blockAlign := channels * bits / 8
	byteRate := sampleRate * blockAlign
	hdr := make([]byte, 44)
	copy(hdr[0:4], "RIFF")
	binary.LittleEndian.PutUint32(hdr[4:8], uint32(36+len(pcm)))
	copy(hdr[8:12], "WAVE")
	copy(hdr[12:16], "fmt ")
	binary.LittleEndian.PutUint32(hdr[16:20], 16)
	binary.LittleEndian.PutUint16(hdr[20:22], 1) // PCM
	binary.LittleEndian.PutUint16(hdr[22:24], uint16(channels))
	binary.LittleEndian.PutUint32(hdr[24:28], uint32(sampleRate))
	binary.LittleEndian.PutUint32(hdr[28:32], uint32(byteRate))
	binary.LittleEndian.PutUint16(hdr[32:34], uint16(blockAlign))
	binary.LittleEndian.PutUint16(hdr[34:36], uint16(bits))
	copy(hdr[36:40], "data")
	binary.LittleEndian.PutUint32(hdr[40:44], uint32(len(pcm)))
	return append(hdr, pcm...)
}

// voiceBlobBytes normalizes the VoiceInfo data column value to bytes: blob,
// hex string, or base64 string — byte-for-byte CipherTalk decodeVoiceBlob
// (blob / hex (even-length hex-only) / base64 / {data:[...]} array as last
// resort; any other value yields nil = "not found").
func voiceBlobBytes(v sqlite.Value) ([]byte, bool) {
	switch v.Kind {
	case sqlite.VBlob:
		return v.Blob, true
	case sqlite.VText:
		s := strings.TrimSpace(v.Text)
		if s == "" {
			return nil, false
		}
		if len(s)%2 == 0 && isHexOnly(s) {
			if b, err := hex.DecodeString(s); err == nil {
				return b, true
			}
		}
		if b, err := base64.StdEncoding.DecodeString(s); err == nil {
			return b, true
		}
		return nil, false
	default:
		return nil, false
	}
}