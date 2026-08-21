package bridge

import (
	"os"
	"strings"
	"testing"

	"wechatsummary/exporter/internal/extract"
)

// TestResolveChatRecordBlob resolves the real 其他消息 (chat-record) blob the
// user's export produced: localType 81604378673 = 0x1300000031 with an
// appmsg <type>19</type>. CipherTalk's getMessageTypeName maps XML type 19 ->
// 聊天记录 and parseType49 -> "[聊天记录] <title>".
func TestResolveChatRecordBlob(t *testing.T) {
	raw, err := os.ReadFile("blob_chatrecord.xml")
	if err != nil {
		t.Skipf("sample blob missing: %v", err)
	}
	content := string(raw)
	if got := messageTypeName(81604378673, content); got != "聊天记录" {
		t.Fatalf("type = %q, want 聊天记录", got)
	}
	got := parseMessageContent(content, 81604378673)
	if !strings.HasPrefix(got, "[聊天记录] 群聊的聊天记录") {
		t.Fatalf("content = %q, want [聊天记录] 群聊的聊天记录...", got)
	}
	// RawContent must stay intact for the backend's md5/refer extraction.
	if !strings.Contains(content, "<appmsg>") {
		t.Fatalf("raw content lost")
	}
}

// TestResolveRefer57Blob resolves the real 引用消息 blob: localType
// 244813135921 with appmsg <type>57</type>. CipherTalk maps XML type 57 ->
// 引用消息 and parseType49 returns the quoted title as content.
func TestResolveRefer57Blob(t *testing.T) {
	raw, err := os.ReadFile("blob_refer57.xml")
	if err != nil {
		t.Skipf("sample blob missing: %v", err)
	}
	content := string(raw)
	if got := messageTypeName(244813135921, content); got != "引用消息" {
		t.Fatalf("type = %q, want 引用消息", got)
	}
	if got := parseMessageContent(content, 244813135921); got != "是我知道的那个梁吗" {
		t.Fatalf("content = %q, want the quoted title", got)
	}
}

// TestResolveSenderPrefix strips the "wxid_xxx:\n" prefix from text content
// (CipherTalk stripSenderPrefix) — the common 文本消息 shape in the export.
func TestResolveSenderPrefix(t *testing.T) {
	cases := []struct{ in, want string }{
		{"wxid_o8ukrtbxo1uq22:\n@yuigon\u2005", "@yuigon\u2005"},
		{"hello", "hello"},
		{"wxid_abc: hi", "hi"},
		{"https://example.com", "https://example.com"}, // no match: not a sender prefix
	}
	for _, c := range cases {
		if got := parseMessageContent(c.in, 1); got != c.want {
			t.Fatalf("parseMessageContent(%q, 1) = %q, want %q", c.in, got, c.want)
		}
	}
}

// TestNormalizeSenderDisplayName wires the contact DB display name into
// senderDisplayName (client-side resolution parity: remark||nick||alias).
func TestNormalizeSenderDisplayName(t *testing.T) {
	msgs := []extract.Message{
		{LocalType: 1, RawContent: "wxid_o8ukrtbxo1uq22:\nhi", SenderUsername: "wxid_o8ukrtbxo1uq22"},
		{LocalType: 1, RawContent: "plain", SenderUsername: "wxid_unknown"},
	}
	normalizeExportMessages(msgs, map[string]string{"wxid_o8ukrtbxo1uq22": "小寻ovo"})
	if msgs[0].SenderDisplayName != "小寻ovo" {
		t.Fatalf("senderDisplayName = %q, want 小寻ovo", msgs[0].SenderDisplayName)
	}
	if msgs[0].Content != "hi" {
		t.Fatalf("content = %q, want hi (prefix stripped)", msgs[0].Content)
	}
	if msgs[1].SenderDisplayName != "" {
		t.Fatalf("unknown sender display = %q, want empty", msgs[1].SenderDisplayName)
	}
	if msgs[1].Content != "plain" {
		t.Fatalf("content = %q, want plain", msgs[1].Content)
	}
}

// TestSanitizeJSONEntryName covers the zip JSON entry naming parity.
func TestSanitizeJSONEntryName(t *testing.T) {
	cases := []struct{ name, want string }{
		{"相亲相爱的coser们", "相亲相爱的coser们.json"},
		{"", "messages.json"},
		{"A/B:C*D?", "A_B_C_D_.json"},
		{"...", "messages.json"},
	}
	for _, c := range cases {
		if got := sanitizeJSONEntryName(c.name); got != c.want {
			t.Fatalf("sanitizeJSONEntryName(%q) = %q, want %q", c.name, got, c.want)
		}
	}
}