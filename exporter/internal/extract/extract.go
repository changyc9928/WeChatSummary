// Package extract converts a decrypted WeChat 4.x WCDB message database into
// the JSON export shape the WeChatSummary backend consumes (session +
// messages, mirroring backend dto.WeChatMessageDto).
//
// The WCDB schema differs between WeChat versions, so column mapping is
// heuristic: the CREATE TABLE text from sqlite_schema is parsed and each
// known logical field (time/type/content/talker/...) is resolved by column
// name with several fallbacks. Rows where every key field is NULL are
// skipped (WeChat 4.x leaves tombstones for deleted messages).
package extract

import (
	"encoding/json"
	"fmt"
	"regexp"
	"sort"
	"strconv"
	"strings"
	"time"

	"wechatsummary/exporter/internal/sqlite"
)

// Message mirrors backend/src/main/java/.../dto/WeChatMessageDto fields.
type Message struct {
	RowID             int64  `json:"rowid,omitempty"`
	LocalID           int64  `json:"localId,omitempty"`
	PlatformMessageID int64  `json:"platformMessageId,omitempty"`
	CreateTime        int64  `json:"createTime,omitempty"`
	FormattedTime     string `json:"formattedTime,omitempty"`
	Type              string `json:"type,omitempty"`
	LocalType         int64  `json:"localType,omitempty"`
	Content           string `json:"content,omitempty"`
	RawContent        string `json:"rawContent,omitempty"`
	IsSend            int    `json:"isSend,omitempty"`
	SenderUsername    string `json:"senderUsername,omitempty"`
	SenderDisplayName string `json:"senderDisplayName,omitempty"`
	Source            string `json:"source,omitempty"`
}

// Session describes the exported conversation (optional in the backend).
type Session struct {
	Nickname     string `json:"nickname"`
	MessageCount int    `json:"messageCount"`
}

// Export is the JSON document the backend reads from an upload ZIP.
type Export struct {
	Session  Session   `json:"session"`
	Messages []Message `json:"messages"`
}

// messageTableCandidates are legacy (WeChat 4.0.x) table names tried in order.
var messageTableCandidates = []string{"MSG", "message", "msg", "ChatMessage", "chat_log"}

// MessageTable is one chat-log table plus its parsed column names.
type MessageTable struct {
	Table   *sqlite.Table
	Columns []string
}

// WeChat 4.1.x names the per-session tables Msg_<md5> (capital M — observed
// on 4.1.12.26) or msg_<md5>; the md5 part is lowercase hex.
var msgTableNameRe = regexp.MustCompile(`(?i)^msg_[0-9a-f]{32}$`)

// FindMessageTables locates the chat-log table(s):
//   - WeChat 4.1.x: every per-session table named msg_<md5(session)>;
//   - WeChat 4.0.x: a single legacy table (MSG / message / msg / ...).
//
// Table columns are parsed from the CREATE TABLE text.
func FindMessageTables(db *sqlite.DB) ([]MessageTable, error) {
	tableSQL := map[string]string{}
	var names []string
	for _, ti := range db.Tables() {
		if ti.Type != "table" {
			continue
		}
		names = append(names, ti.Name)
		tableSQL[ti.Name] = ti.SQL
	}

	// 4.1: per-session msg_<md5> tables
	var perSession []MessageTable
	for _, name := range names {
		if msgTableNameRe.MatchString(name) {
			t, err := db.Table(name)
			if err != nil {
				continue
			}
			cols := parseColumnNames(tableSQL[name])
			if len(cols) > 0 {
				perSession = append(perSession, MessageTable{Table: t, Columns: cols})
			}
		}
	}
	if len(perSession) > 0 {
		sort.Slice(perSession, func(i, j int) bool { return perSession[i].Table.Name() < perSession[j].Table.Name() })
		return perSession, nil
	}

	// 4.0: single legacy table
	for _, name := range messageTableCandidates {
		for _, n := range names {
			if strings.EqualFold(n, name) {
				t, err := db.Table(n)
				if err != nil {
					break
				}
				cols := parseColumnNames(tableSQL[n])
				if len(cols) == 0 {
					return nil, fmt.Errorf("cannot parse columns of %s", n)
				}
				return []MessageTable{{Table: t, Columns: cols}}, nil
			}
		}
	}
	sort.Strings(names)
	return nil, fmt.Errorf("no chat-log table found; database has tables: %s", strings.Join(names, ", "))
}

// Column mapping: logical field -> accepted column names (case-insensitive).
// The first alias in the list wins when several columns match.
var columnAliases = map[string][]string{
	"time":      {"createTime", "create_time", "msgCreateTime", "timestamp"},
	"type":      {"type", "msgType", "msg_type"},
	"localType": {"localType", "local_type", "messageType", "message_type"},
	"content":   {"message_content", "messageContent", "content", "msg_content", "str_content", "strContent", "rawContent", "raw_content", "Content", "msgContent", "message", "text"},
	"compress":  {"compress_content", "compressContent", "compress", "compressed_content"},
	"raw":       {"rawContent", "raw", "raw_content", "rawMsgContent"},
	"talker":    {"sender_username", "senderUsername", "talker", "talkerId", "talker_id", "sender", "real_sender", "src", "userName", "username", "wxid"},
	"senderId":  {"real_sender_id", "realSenderId", "sender_id", "senderId"},
	"isSend":    {"is_send", "isSend", "isSender", "is_sender", "issend", "direction", "sendStatus"},
	"id":        {"local_id", "msgLocalID", "msgLocalId", "localId", "id", "msgId"},
	"svrId":     {"server_id", "msgSvrId", "msgSvrID", "serverId"},
}

// resolveCol finds the table index of the first alias present in cols.
func resolveCol(cols []string, aliases []string) int {
	for _, alias := range aliases {
		for i, c := range cols {
			if strings.EqualFold(c, alias) {
				return i
			}
		}
	}
	return -1
}

// TimeColumnIndex returns the column index of the createTime field for a
// table's parsed columns (-1 when absent). Used by fast per-table scans.
func TimeColumnIndex(cols []string) int {
	return resolveCol(cols, columnAliases["time"])
}

// indexedRows maps logical fields to per-row column indexes once.
type indexedRows struct {
	cols                []string
	time, typ           int
	localType           int
	content, compress   int
	raw                 int
	talker              int
	senderId            int
	isSend              int
	id, svrId           int
}

func prepareIndexed(cols []string) indexedRows {
	return indexedRows{
		cols:      cols,
		time:      resolveCol(cols, columnAliases["time"]),
		typ:       resolveCol(cols, columnAliases["type"]),
		localType: resolveCol(cols, columnAliases["localType"]),
		content:   resolveCol(cols, columnAliases["content"]),
		compress:  resolveCol(cols, columnAliases["compress"]),
		raw:       resolveCol(cols, columnAliases["raw"]),
		talker:    resolveCol(cols, columnAliases["talker"]),
		senderId:  resolveCol(cols, columnAliases["senderId"]),
		isSend:    resolveCol(cols, columnAliases["isSend"]),
		id:        resolveCol(cols, columnAliases["id"]),
		svrId:     resolveCol(cols, columnAliases["svrId"]),
	}
}

func (ix indexedRows) mapped() map[string]int {
	m := map[string]int{}
	for field, idx := range map[string]int{
		"time": ix.time, "type": ix.typ, "localType": ix.localType,
		"content": ix.content, "compress": ix.compress, "raw": ix.raw, "talker": ix.talker,
		"senderId": ix.senderId, "isSend": ix.isSend, "id": ix.id, "svrId": ix.svrId,
	} {
		if idx >= 0 {
			m[field] = idx
		}
	}
	return m
}

// LoadName2Id reads the WeChat 4.x Name2Id table (rowid <-> user_name) into a
// map. Returns an empty map when the table is absent. Usable to resolve the
// real_sender_id column of 4.1 message tables into a wxid.
func LoadName2Id(db *sqlite.DB) map[int64]string {
	out := map[int64]string{}
	t, err := db.Table("Name2Id")
	if err != nil {
		return out
	}
	cols := parseColumnNames("")
	for _, ti := range db.Tables() {
		if ti.Type == "table" && ti.Name == "Name2Id" {
			cols = parseColumnNames(ti.SQL)
			break
		}
	}
	nameIdx := -1
	for i, c := range cols {
		if strings.EqualFold(c, "user_name") || strings.EqualFold(c, "userName") {
			nameIdx = i
			break
		}
	}
	rows, ids, err := t.ScanRowids(0)
	if err != nil {
		return out
	}
	for i, r := range rows {
		if i >= len(ids) {
			break
		}
		var name string
		if nameIdx >= 0 && nameIdx < len(r) {
			name = strVal(r[nameIdx])
		} else if len(r) > 0 {
			name = strVal(r[len(r)-1])
		}
		if name != "" {
			out[ids[i]] = name
		}
	}
	return out
}

func strVal(v sqlite.Value) string {
	switch v.Kind {
	case sqlite.VText:
		return v.Text
	case sqlite.VBlob:
		return string(v.Blob)
	case sqlite.VInt:
		return strconv.FormatInt(v.Int, 10)
	}
	return ""
}

// ExtractMessages scans one chat-log table and maps its rows to messages.
// name2id (optional) maps real_sender_id rowids to wxids for 4.1 tables.
// limit > 0 caps the number of rows read (ascending rowid).
// ExtractMessages scans a whole table (limit>0 caps rows).
func ExtractMessages(table *sqlite.Table, cols []string, limit int, name2id map[int64]string) ([]Message, error) {
	return ExtractMessagesRange(table, cols, limit, name2id, 0, 0)
}

// ExtractMessagesRange is ExtractMessages with an optional createTime window
// (unix seconds; from>0 and to>0 are inclusive bounds; 0 = unbounded).
func ExtractMessagesRange(table *sqlite.Table, cols []string, limit int, name2id map[int64]string, from, to int64) ([]Message, error) {
	rows, ids, err := table.ScanRowids(limit)
	if err != nil {
		return nil, err
	}
	ix := prepareIndexed(cols)
	idx := ix.mapped()

	var out []Message
	for ri, r := range rows {
		rowid := int64(0)
		if ri < len(ids) {
			rowid = ids[ri]
		}
		asInt := func(field string) (int64, bool) {
			i, ok := idx[field]
			if !ok || i < 0 || i >= len(r) {
				return 0, false
			}
			v := r[i]
			switch v.Kind {
			case sqlite.VInt:
				return v.Int, true
			case sqlite.VFloat:
				return int64(v.Float), true
			case sqlite.VText:
				n, err := strconv.ParseInt(strings.TrimSpace(v.Text), 10, 64)
				if err == nil {
					return n, true
				}
			}
			return 0, false
		}
		asStr := func(field string) string {
			i, ok := idx[field]
			if !ok || i < 0 || i >= len(r) {
				return ""
			}
			v := r[i]
			switch v.Kind {
			case sqlite.VText:
				return v.Text
			case sqlite.VBlob:
				return string(v.Blob)
			case sqlite.VInt:
				return strconv.FormatInt(v.Int, 10)
			}
			return ""
		}

		// valueAt returns the raw sqlite.Value for a mapped field, so the
		// content decoder can inspect blob vs text encodings.
		valueAt := func(m map[string]int, field string, r []sqlite.Value) sqlite.Value {
			i, ok := m[field]
			if !ok || i < 0 || i >= len(r) {
				return sqlite.Value{}
			}
			return r[i]
		}

		createTime, _ := asInt("time")
		if from > 0 && createTime < from {
			continue
		}
		if to > 0 && createTime > to {
			continue
		}
		content := asStr("content")
		if createTime == 0 && content == "" && asStr("talker") == "" {
			continue // 4.x tombstone row
		}

		typ, _ := asInt("type")
		localType := typ
		if lt, ok := asInt("localType"); ok {
			localType = lt
		}
		isSend, _ := asInt("isSend")
		svrId, _ := asInt("svrId")
		id, _ := asInt("id")

		raw := asStr("raw")
		if raw == "" {
			// WeChat 4.1.x stores the readable XML for non-text messages in
			// compress_content (or zstd/hex/base64-wrapped); message_content
			// itself is binary. Decode the same chain CipherTalk uses, then
			// fall back to the raw message_content string when the decoded
			// form is empty. The decoded text is what the user sees, so it
			// also becomes Content (CipherTalk passes the decoded value into
			// parseMessageContent).
			decoded := DecodeMessageContent(valueAt(idx, "content", r), valueAt(idx, "compress", r))
			if decoded != "" {
				raw = decoded
				content = decoded
			} else {
				raw = content
			}
		}

		sender := asStr("talker")
		if sender == "" {
			// 4.1: real_sender_id is a rowid into Name2Id
			if sid, ok := asInt("senderId"); ok && name2id != nil {
				sender = name2id[sid]
			}
		}

		msg := Message{
			RowID:             rowid,
			LocalID:           id,
			PlatformMessageID: svrId,
			CreateTime:        createTime,
			LocalType:         localType,
			Type:              strconv.FormatInt(typ, 10),
			Content:           content,
			RawContent:        raw,
			IsSend:            int(isSend),
			SenderUsername:    sender,
			Source:            "wechat-extract",
		}
		if createTime > 0 {
			msg.FormattedTime = time.Unix(createTime, 0).Format("2006-01-02 15:04:05")
		}
		out = append(out, msg)
	}
	return out, nil
}

// BuildExport wraps messages into the backend document.
func BuildExport(messages []Message, nickname string) Export {
	return Export{
		Session:  Session{Nickname: nickname, MessageCount: len(messages)},
		Messages: messages,
	}
}

// Marshal writes the export as indented JSON.
func Marshal(ex Export) ([]byte, error) {
	return json.MarshalIndent(ex, "", "  ")
}

// parseColumnNames extracts column names from a CREATE TABLE statement,
// preserving order. Handles double-quoted and backticked identifiers and
// stops at the closing parenthesis (depth 0). Constraint clauses containing
// parentheses (e.g. DEFAULT(...)) are handled by depth tracking.
func parseColumnNames(createSQL string) []string {
	open := strings.Index(createSQL, "(")
	if open < 0 {
		return nil
	}
	depth := 0
	var cur strings.Builder
	var out []string
	flush := func() {
		part := strings.TrimSpace(cur.String())
		cur.Reset()
		if part == "" {
			return
		}
		name := part
		for i, r := range part {
			isIdent := r == '_' || (r >= 'a' && r <= 'z') || (r >= 'A' && r <= 'Z') || (i > 0 && r >= '0' && r <= '9')
			if !isIdent {
				name = part[:i]
				break
			}
		}
		// table constraints (not columns) start with these keywords
		switch strings.ToLower(name) {
		case "unique", "primary", "check", "foreign", "constraint", "index":
			return
		}
		out = append(out, name)
	}
	for i := open; i < len(createSQL); i++ {
		c := createSQL[i]
		switch {
		case c == '"' || c == '`':
			q := c
			j := strings.IndexByte(createSQL[i+1:], q)
			if j < 0 {
				return out
			}
			cur.WriteString(createSQL[i+1 : i+1+j])
			i += j + 1
		case c == '(':
			depth++
		case c == ')':
			depth--
			if depth == 0 {
				flush()
				return out
			}
		case c == ',' && depth == 1:
			flush()
		default:
			if depth == 1 {
				cur.WriteByte(c)
			}
		}
	}
	return out
}
