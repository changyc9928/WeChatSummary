// resolve.go implements CipherTalk's message-display resolution 1:1
// (electron/services/exportService.ts):
//
//   - messageTypeName  mirrors getMessageTypeName(localType, content)
//   - parseMessageContent mirrors parseMessageContent(content, localType)
//   - parseType49       mirrors parseType49(content)
//   - stripSenderPrefix  mirrors stripSenderPrefix(content)
//   - cleanSystemMessage mirrors cleanSystemMessage(content)
//
// The bridge previously only mapped localType 3/34/43/47 to bracket tokens,
// leaving every other type as the raw extracted content (raw appmsg XML with
// the "wxid_xxx:\n" sender prefix). This package resolves the readable text
// and Chinese type name the same way CipherTalk does, so the backend summary
// shows "[聊天记录] 群聊的聊天记录" instead of the raw XML blob.

package bridge

import (
	"html"
	"regexp"
	"strings"
)

// xmlValueReFor builds CipherTalk's extractXmlValue regex for one tag:
// <tag>([\s\S]*?)</tag> — the FIRST occurrence wins, matching the TypeScript
// RegExp semantics exactly (regardless of nested <msg> envelopes).
func xmlValueReFor(tag string) *regexp.Regexp {
	return regexp.MustCompile(`(?is)<` + regexp.QuoteMeta(tag) + `\s*>([\s\S]*?)</` + regexp.QuoteMeta(tag) + `\s*>`)
}

var xmlValueCache = map[string]*regexp.Regexp{}

// extractXmlValue returns the inner text of the first <tag>...</tag> in xml,
// with CDATA markers stripped, or "" when absent.
func extractXmlValue(xml, tag string) string {
	re := xmlValueCache[tag]
	if re == nil {
		re = xmlValueReFor(tag)
		if len(xmlValueCache) < 128 {
			xmlValueCache[tag] = re
		}
	}
	m := re.FindStringSubmatch(xml)
	if m == nil {
		return ""
	}
	v := strings.ReplaceAll(m[1], "<![CDATA[", "")
	v = strings.ReplaceAll(v, "]]>", "")
	return strings.TrimSpace(v)
}

// decodeHtmlEntities decodes &amp; &lt; &gt; &quot; &#xNN; etc. (CipherTalk's
// decodeHtmlEntities uses a DOM textContent round-trip; html.UnescapeString
// covers the standard entity set plus numeric references).
func decodeHtmlEntities(s string) string {
	return html.UnescapeString(s)
}

// senderPrefixRe matches a leading "wxid_xxx:" token plus whitespace. The
// negative lookahead in CipherTalk's TS regex (:(?!//)) is applied in code
// below: a match is only applied when the remainder does not start with "//"
// (so "https://..." is never treated as a sender prefix).
var senderPrefixRe = regexp.MustCompile(`^[\s]*([a-zA-Z0-9_-]+):`)

func stripSenderPrefix(content string) string {
	m := senderPrefixRe.FindStringSubmatchIndex(content)
	if m == nil {
		return content
	}
	rest := content[m[1]:]
	// m[0]..m[1] is the whole matched prefix; the colon sits just before a
	// whitespace run. Apply only when the text after the matched prefix does
	// not itself begin with "//" (scheme separator) — the "http(s)://" guard.
	after := rest
	if i := strings.IndexByte(rest, ':'); i >= 0 {
		after = rest[i+1:]
	}
	if strings.HasPrefix(strings.TrimSpace(after), "//") {
		return content
	}
	tail := content[m[1]:]
	return strings.TrimLeft(tail, " \t\r\n")
}

// messageTypeName mirrors CipherTalk's getMessageTypeName: an XML <type> tag
// (present for appmsg rows with large localTypes) overrides the localType
// name map; otherwise the localType map with "其他消息" fallback.
func messageTypeName(localType int64, content string) string {
	if content != "" {
		switch extractXmlValue(content, "type") {
		case "87":
			return "群公告"
		case "2000":
			return "转账消息"
		case "2001":
			return "红包消息"
		case "115":
			return "微信礼物"
		case "3":
			return "音乐分享"
		case "5":
			return "链接消息"
		case "6":
			return "文件消息"
		case "19":
			return "聊天记录"
		case "33", "36":
			return "小程序消息"
		case "57":
			return "引用消息"
		}
	}
	return localTypeName(localType)
}

// localTypeName maps numeric localType to the Chinese type name used in the
// export JSON (mirrors CipherTalk's typeNames map).
func localTypeName(localType int64) string {
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

// isAppMsgXML reports whether content wraps an <appmsg> envelope.
func isAppMsgXML(content string) bool {
	return strings.Contains(strings.ToLower(content), "<appmsg")
}

// parseMessageContent mirrors CipherTalk's parseMessageContent, producing the
// readable display text for one message. Media types (3/34/43/47) keep the
// bracket tokens — the media/voice loops append the resolved relative path
// afterwards, matching the existing pipeline.
func parseMessageContent(content string, localType int64) string {
	if content == "" {
		return ""
	}
	xmlType := extractXmlValue(content, "type")
	if xmlType != "" && isAppMsgXML(content) {
		return parseType49(content)
	}

	switch localType {
	case 1: // 文本：剥离发送者前缀
		return stripSenderPrefix(content)
	case 3:
		return "[图片]"
	case 34:
		return "[语音消息]"
	case 42: // 名片
		if m := regexp.MustCompile(`nickname="([^"]*)"`).FindStringSubmatch(content); m != nil && m[1] != "" {
			return "[名片] " + m[1]
		}
		return "[名片]"
	case 43:
		return "[视频]"
	case 47:
		if m := regexp.MustCompile(`(?i)cdnurl\s*=\s*"([^"]+)"`).FindStringSubmatch(content); m != nil && m[1] != "" {
			return "[动画表情] " + decodeHtmlEntities(m[1])
		}
		return "[动画表情]"
	case 48: // 位置
		if m := regexp.MustCompile(`poiname="([^"]*)"`).FindStringSubmatch(content); m != nil && m[1] != "" {
			return "[位置] " + m[1]
		}
		if m := regexp.MustCompile(`label="([^"]*)"`).FindStringSubmatch(content); m != nil && m[1] != "" {
			return "[位置] " + m[1]
		}
		return "[位置]"
	case 49:
		return parseType49(content)
	case 50: // 通话
		if msg := extractXmlValue(content, "msg"); msg != "" {
			return "[通话] " + msg
		}
		return "[通话]"
	case 10000:
		return cleanSystemMessage(content)
	case 244813135921: // 引用消息
		if title := decodeHtmlEntities(extractXmlValue(content, "title")); title != "" {
			return title
		}
		return "[引用消息]"
	default:
		if xmlType != "" {
			return parseType49(content)
		}
		return stripSenderPrefix(content)
	}
}

// parseType49 mirrors CipherTalk's parseType49: appmsg (type 49) content —
// 转账、红包、礼物、音乐、链接、文件、小程序、群公告、聊天记录、引用.
func parseType49(content string) string {
	title := decodeHtmlEntities(extractXmlValue(content, "title"))
	tp := extractXmlValue(content, "type")

	switch tp {
	case "87": // 群公告
		if ta := extractXmlValue(content, "textannouncement"); ta != "" {
			return "[群公告] " + ta
		}
		return "[群公告]"
	case "2000": // 转账
		feedesc := extractXmlValue(content, "feedesc")
		payMemo := extractXmlValue(content, "pay_memo")
		if feedesc != "" {
			if payMemo != "" {
				return "[转账] " + feedesc + " " + payMemo
			}
			return "[转账] " + feedesc
		}
		return "[转账]"
	case "2001": // 红包
		greeting := extractXmlValue(content, "receivertitle")
		if greeting == "" {
			greeting = extractXmlValue(content, "sendertitle")
		}
		if greeting != "" {
			return "[红包] " + greeting
		}
		return "[红包]"
	case "115": // 微信礼物
		wish := extractXmlValue(content, "wishmessage")
		if wish == "" {
			wish = "送你一份心意"
		}
		if sku := extractXmlValue(content, "skutitle"); sku != "" {
			return "[微信礼物] " + wish + " - " + sku
		}
		return "[微信礼物] " + wish
	case "3": // 音乐分享
		if title != "" {
			if des := extractXmlValue(content, "des"); des != "" {
				return "[音乐] " + title + " - " + des
			}
			return "[音乐] " + title
		}
		return "[音乐]"
	}

	if title != "" {
		switch tp {
		case "5", "49":
			return "[链接] " + title
		case "6":
			return "[文件] " + title
		case "19":
			return "[聊天记录] " + title
		case "33", "36":
			return "[小程序] " + title
		case "57":
			return title // 引用消息，title 就是回复的内容
		default:
			return title
		}
	}
	return "[消息]"
}

// cleanSystemMessage mirrors CipherTalk's cleanSystemMessage: strip the XML
// declaration and known tags, leaving the human-readable text.
var systemMsgTagRe = regexp.MustCompile(`(?is)<[^>]+>`)
var systemMsgXmlDeclRe = regexp.MustCompile(`(?is)<\?xml[^?]*\?>`)

func cleanSystemMessage(content string) string {
	cleaned := systemMsgXmlDeclRe.ReplaceAllString(content, "")
	cleaned = systemMsgTagRe.ReplaceAllString(cleaned, " ")
	cleaned = strings.Join(strings.Fields(cleaned), " ")
	return strings.TrimSpace(cleaned)
}