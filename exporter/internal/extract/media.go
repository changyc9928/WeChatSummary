package extract

import (
	"encoding/base64"
	"encoding/hex"
	"regexp"
	"strings"

	"wechatsummary/exporter/internal/sqlite"
)

// LocalTypeName maps WeChat's numeric message localType to the Chinese type
// name used in the export JSON the backend displays (mirrors CipherTalk's
// typeNames map). Unknown types fall back to "其他消息".
func LocalTypeName(localType int64) string {
	switch localType {
	case 1:
		return "文本消息"
	case 3:
		return "图片消息"
	case 34:
		return "语音消息"
	case 42:
		return "名片消息"
	case 43:
		return "视频消息"
	case 47:
		return "动画表情"
	case 48:
		return "位置消息"
	case 49:
		return "链接消息"
	case 50:
		return "通话消息"
	case 10000:
		return "系统消息"
	default:
		return "其他消息"
	}
}

var md5AttrRe = regexp.MustCompile(`(?i)\bmd5\s*=\s*['"]([a-f0-9]{16,32})['"]`)
var md5TagRe = regexp.MustCompile(`(?i)<md5>([a-f0-9]{16,32})</md5>`)

// MediaMd5FromXML returns the md5 attribute/tag from a WeChat message XML
// (e.g. the <img ... md5="..."/> envelope or <md5>...</md5>) — the identifier
// used to locate the cached media file. Empty when absent.
func MediaMd5FromXML(raw string) string {
	if m := md5AttrRe.FindStringSubmatch(raw); m != nil {
		return strings.ToLower(m[1])
	}
	if m := md5TagRe.FindStringSubmatch(raw); m != nil {
		return strings.ToLower(m[1])
	}
	return ""
}

// packedInfoColumnNames are the WCDB packed-info columns that carry the
// cached media file name for image rows (names are version-dependent).
var packedInfoColumnNames = []string{
	"packed_info_data", "packed_info", "packedInfoData", "packedInfo",
	"PackedInfoData", "PackedInfo",
	"packed_info_blob", "packedInfoBlob",
	"BytesExtra", "bytes_extra",
	"reserved0", "Reserved0",
	"WCDB_CT_packed_info_data", "WCDB_CT_packed_info",
	"WCDB_CT_PackedInfoData", "WCDB_CT_PackedInfo",
	"WCDB_CT_Reserved0",
}

var datNameRe = regexp.MustCompile(`([0-9a-fA-F]{8,})(?:\.t)?\.dat`)
var hexRunRe = regexp.MustCompile(`([0-9a-fA-F]{16,})`)

// ParseImageDatName decodes a packed-info blob (or hex/base64 string) into
// the cached .dat file base name, CipherTalk-style: extract printable ASCII
// from the blob, then prefer a "xxxx...dat" token, else the first long hex
// run. Empty when nothing usable is found.
func ParseImageDatName(packed any) string {
	buf, ok := packedBytes(packed)
	if !ok || len(buf) == 0 {
		return ""
	}
	var sb strings.Builder
	for _, b := range buf {
		if b >= 0x20 && b <= 0x7e {
			sb.WriteByte(b)
		} else {
			sb.WriteByte(' ')
		}
	}
	text := sb.String()
	if m := datNameRe.FindStringSubmatch(text); m != nil {
		return strings.ToLower(m[1])
	}
	if m := hexRunRe.FindStringSubmatch(text); m != nil {
		return strings.ToLower(m[1])
	}
	return ""
}

// packedBytes normalizes the various WCDB packed-info representations
// (blob, hex string, base64 string) to bytes.
func packedBytes(packed any) ([]byte, bool) {
	switch v := packed.(type) {
	case []byte:
		return v, true
	case string:
		s := strings.TrimSpace(v)
		if len(s) >= 2 && len(s)%2 == 0 && isHexOnly(s) {
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

func isHexOnly(s string) bool {
	for _, c := range s {
		if !((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F')) {
			return false
		}
	}
	return true
}

// PackedSample is a preview of one unparseable packed-info blob (the first
// few per table), so the bridge can log the real 4.1.12.26 packed format.
type PackedSample struct {
	RowID int64
	Blob  []byte // first 96 bytes
}

// ScanPackedInfo reads the packed-info column of every row in one message
// table and returns b-tree rowid -> cached dat base name. The bridge uses
// this to resolve the on-disk .dat file for each image message. Keyed by
// rowid rather than local_id: WeChat 4.1.12.26 rows can carry local_id=0,
// which made the old localId-keyed lookup miss every image. samples carries
// previews of up to 3 blobs that yielded no name (for format diagnosis).
func ScanPackedInfo(table *sqlite.Table, cols []string) (map[int64]string, []PackedSample, error) {
	pt := -1
	for i, c := range cols {
		for _, want := range packedInfoColumnNames {
			if strings.EqualFold(c, want) {
				pt = i
				break
			}
		}
		if pt >= 0 {
			break
		}
	}
	out := map[int64]string{}
	var samples []PackedSample
	if pt < 0 {
		return out, samples, nil // no packed-info column in this table
	}

	rows, ids, err := table.ScanRowids(0)
	if err != nil {
		return nil, nil, err
	}
	for i, r := range rows {
		if pt >= len(r) {
			continue
		}
		val := r[pt]
		var packed any
		switch val.Kind {
		case sqlite.VBlob:
			packed = val.Blob
		case sqlite.VText:
			packed = val.Text
		default:
			continue
		}
		name := ParseImageDatName(packed)
		rowid := int64(0)
		if i < len(ids) {
			rowid = ids[i]
		}
		if name == "" {
			if len(samples) < 3 {
				blob, _ := packedBytes(packed)
				if len(blob) > 96 {
					blob = blob[:96]
				}
				samples = append(samples, PackedSample{RowID: rowid, Blob: blob})
			}
			continue
		}
		out[rowid] = name
	}
	return out, samples, nil
}
