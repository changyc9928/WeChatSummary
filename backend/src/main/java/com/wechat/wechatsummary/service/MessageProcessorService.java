package com.wechat.wechatsummary.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wechat.wechatsummary.config.ProcessingConfig;
import com.wechat.wechatsummary.dto.WeChatMessageDto;
import com.wechat.wechatsummary.util.HashUtils;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * Service handling raw WeChat JSON database extracts parsing, mapping obfuscated WeChat IDs (wxid)
 * to user nicknames, stripping out complex emojis/metadata, integrating rich multi-media contextual
 * descriptions, and writing normalized clean Markdown/Text outputs.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MessageProcessorService {

    private static final Pattern XML_CDNURL_PATTERN = Pattern.compile(
        "cdnurl\\s*=\\s*\"([^\"]+)\"");
    private static final Pattern MD5_EXTRACT_PATTERN = Pattern.compile("([a-f0-9]{32})");
    private static final Pattern MD5_ATTR_PATTERN = Pattern.compile("md5=\"([a-f0-9]{32})\"");
    private static final Pattern REFER_TYPE_PATTERN = Pattern.compile(
        "<refermsg>.*<type>(\\d+)</type>.*</refermsg>", Pattern.DOTALL);

    /**
     * Pattern targeting a wide spectrum of standard emojis, modifiers, and non-standard visual
     * glyph ranges.
     */
    private static final Pattern NICKNAME_CLEAN_PATTERN = Pattern.compile(
        "[\\uD83C\\uDF00-\\uD83D\\uDE4F\\uD83D\\uDE80-\\uD83D\\uDEFF\\u2600-\\u27BF\\u2300-\\u23FF\\u2B50\\u3299\\u3297]|\\p{Co}"
    );

    private static final Pattern WXID_PATTERN = Pattern.compile("wxid_[a-zA-Z0-9]{10,25}");

    // Outbound HTTP client used to enrich shared links / URLs with their page title & description.
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build();

    // Matches URLs embedded directly in message text.
    private static final Pattern URL_PATTERN =
        Pattern.compile("https?://[^\\s<>\"'）)】,。、]+", Pattern.CASE_INSENSITIVE);
    // Captures the <url> element inside shared-link (appmsg) payloads.
    private static final Pattern URL_RAW_PATTERN = Pattern.compile("<url>(.*?)</url>");
    // Forwarded chat-record payload structures.
    private static final Pattern RECORDINFO_PATTERN =
        Pattern.compile("<recordinfo>(.*)</recordinfo>", Pattern.DOTALL);
    private static final Pattern DATAITEM_PATTERN =
        Pattern.compile("<dataitem\\b(.*?)</dataitem>", Pattern.DOTALL);
    private static final Pattern META_TAG_PATTERN = Pattern.compile("(?is)<meta\\b([^>]*)>");
    private static final Pattern TITLE_TAG_PATTERN =
        Pattern.compile("(?is)<title[^>]*>(.*?)</title>");
    private static final Pattern ENTITY_HEX_PATTERN = Pattern.compile("&#x([0-9a-fA-F]+);");
    private static final Pattern ENTITY_DEC_PATTERN = Pattern.compile("&#(\\d+);");
    private static final Pattern ATTR_PATTERN = Pattern.compile("(\\w+)\\s*=\\s*[\"'](.*?)[\"']");

    // WeChat localType code for forwarded chat-record messages.
    private static final long LOCAL_TYPE_CHAT_RECORD = 81604378673L;

    // WeChat message type protocol codes (localType)
    private static final long LOCAL_TYPE_IMAGE = 3;
    private static final long LOCAL_TYPE_AUDIO = 34;
    private static final long LOCAL_TYPE_VIDEO = 43;
    private static final long LOCAL_TYPE_EMOJI = 47;
    private static final long LOCAL_TYPE_FILE = 49;
    private static final long LOCAL_TYPE_REFER_MESSAGE = 244813135921L;

    // Referenced media type codes embedded in <refermsg> XML payloads
    private static final String REFER_TYPE_IMAGE = "3";
    private static final String REFER_TYPE_AUDIO = "34";
    private static final String REFER_TYPE_EMOJI = "47";

    // Number of trailing characters of a wxid kept in the anonymous fallback display name
    private static final int WXID_FALLBACK_TAIL_LENGTH = 4;

    private final WeChatSummaryCacheService cacheService;
    private final ObjectMapper objectMapper;
    private final StoragePaths storagePaths;
    private final ProcessingConfig processingConfig;

    /**
     * Parses the underlying raw chat JSON archive, structures content models, injects resolved
     * multi-media descriptions from cache layers, maps user identifiers, and outputs a normalized
     * text layout document by building paths dynamically using userId and uuid on the fly.
     *
     * @param userId unique user identifier isolating the file directories
     * @param uuid   active tracking transaction identifier for the execution pipeline
     */
    public void processJsonAndSave(String userId, String uuid) {
        log.info(
            "Starting JSON extraction and normalization pipeline for user: [{}] task UUID: [{}]",
            userId, uuid);
        try {
            // Correct user-isolated directory structure: uploadDir / userId / uuid
            Path sessionDir = storagePaths.sessionDir(userId, uuid);
            Path inputPath;

            if (!Files.exists(sessionDir)) {
                log.error(
                    "Aborting processing pipeline. Session workspace directory does not exist: {}",
                    sessionDir);
                return;
            }

            try (Stream<Path> list = Files.list(sessionDir)) {
                Optional<Path> jsonFile = list.filter(p -> p.toString().endsWith(".json"))
                    .findFirst();
                if (jsonFile.isEmpty()) {
                    log.error(
                        "Aborting processing pipeline. No raw chat JSON structure located inside directory: {}",
                        sessionDir);
                    return;
                }
                inputPath = jsonFile.get();
            }

            // Correct user-isolated output directory structure: uploadDir / userId / outputs
            Path userOutputDir = storagePaths.outputDir(userId);
            Files.createDirectories(userOutputDir);
            Path outputPath = storagePaths.processedMarkdown(userId, uuid);

            objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
            String jsonStr = Files.readString(inputPath, StandardCharsets.UTF_8);
            Map<String, Object> rootMap = objectMapper.readValue(jsonStr, new TypeReference<>() {
            });

            List<WeChatMessageDto> messages = objectMapper.convertValue(
                rootMap.get("messages"),
                new TypeReference<>() {
                }
            );

            if (messages == null || messages.isEmpty()) {
                log.warn(
                    "Terminating processing cycle. Zero chat messages found inside JSON collection for context UUID: {}",
                    uuid);
                return;
            }

            log.info(
                "Discovered {} text and multimedia frames. Initializing profile identity mappings...",
                messages.size());
            Map<String, String> userMap = buildUserMap(messages);
            // Per-run cache so the same URL is only fetched once during a single processing pass.
            Map<String, String> linkCache = new HashMap<>();
            StringBuilder textBuilder = new StringBuilder();

            @SuppressWarnings("unchecked")
            Map<String, Object> session = (Map<String, Object>) rootMap.get("session");
            if (session != null) {
                textBuilder.append("--- 群聊基本信息 ---\n")
                    .append("群名称: ").append(session.get("nickname")).append("\n")
                    .append("总消息数: ").append(session.get("messageCount")).append("\n")
                    .append("--------------------\n\n");
            }

            int processedCount = 0;
            for (WeChatMessageDto msg : messages) {
                String cleanContent = processMessageContent(userId, uuid, msg, linkCache);
                cleanContent = replaceWxidsWithNicknames(cleanContent, userMap);

                String rawName =
                    StringUtils.hasText(msg.getSenderDisplayName()) ? msg.getSenderDisplayName()
                        : msg.getSenderUsername();
                String cleanName = userMap.getOrDefault(msg.getSenderUsername(),
                    cleanNickname(rawName));

                if ("系统消息".equals(msg.getType())) {
                    cleanName = "系统消息";
                }

                textBuilder.append("[").append(msg.getFormattedTime()).append("] ")
                    .append(cleanName).append(": ")
                    .append(cleanContent).append("\n");

                processedCount++;
                if (processedCount % processingConfig.getLogProgressEvery() == 0
                    && log.isDebugEnabled()) {
                    log.debug(
                        "Task UUID: {} iteratively normalized {} messages out of {} total logs.",
                        uuid, processedCount, messages.size());
                }
            }

            Files.writeString(outputPath, textBuilder.toString(), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);

            log.info("Chat visualization extraction successful. Document compiled saved at: {}",
                outputPath.toAbsolutePath());

        } catch (IOException e) {
            log.error(
                "Fatal I/O pipeline exception encountered while compiling file structure for UUID: {}",
                uuid, e);
            throw new RuntimeException("数据转换纯文本失败", e);
        }
    }

    /**
     * Builds a tracking map connecting raw alphanumeric WeChat IDs to readable, sanitized human
     * display names.
     */
    private Map<String, String> buildUserMap(List<WeChatMessageDto> messages) {
        Map<String, String> userMap = new HashMap<>();
        for (WeChatMessageDto msg : messages) {
            String wxid = msg.getSenderUsername();
            if (StringUtils.hasText(wxid) && !userMap.containsKey(wxid)) {
                String rawName =
                    StringUtils.hasText(msg.getSenderDisplayName()) ? msg.getSenderDisplayName()
                        : wxid;
                String cleanName = cleanNickname(rawName);
                if (!StringUtils.hasText(cleanName)) {
                    cleanName = "微信用户_" + wxid.substring(Math.max(0,
                        wxid.length() - WXID_FALLBACK_TAIL_LENGTH));
                }
                userMap.put(wxid, cleanName);
            }
        }
        return userMap;
    }

    /**
     * Evaluates raw message texts via regex patterns, mapping target raw WXID occurrences to
     * readable identities.
     */
    private String replaceWxidsWithNicknames(String content, Map<String, String> userMap) {
        if (!StringUtils.hasText(content)) {
            return content;
        }
        Matcher matcher = WXID_PATTERN.matcher(content);
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            String wxid = matcher.group();
            String replacement = userMap.getOrDefault(wxid, wxid);
            matcher.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    /**
     * Strips multi-byte graphic emojis and unicode visual markers from profiles to improve text
     * scannability.
     */
    private String cleanNickname(String name) {
        if (!StringUtils.hasText(name)) {
            return "";
        }
        return NICKNAME_CLEAN_PATTERN.matcher(name).replaceAll("").trim();
    }

    /**
     * Core router assessing WeChat message configurations, mapping metadata payloads, and
     * evaluating cached summaries.
     */
    private String processMessageContent(String userId, String uuid, WeChatMessageDto msg,
        Map<String, String> linkCache) {
        String content = msg.getContent();
        if (!StringUtils.hasText(content)) {
            content = "";
        }

        String type = msg.getType();
        long localType = msg.getLocalType() != null ? msg.getLocalType() : 0L;

        if ("图片消息".equals(type) || localType == LOCAL_TYPE_IMAGE) {
            String imageHash = extractPathHash(userId, uuid, msg.getContent(), msg.getRawContent());
            Optional<String> imageSummary = StringUtils.hasText(imageHash)
                ? cacheService.getImageSummary(imageHash)
                : Optional.empty();
            if (imageSummary.isEmpty() && StringUtils.hasText(msg.getRawContent())) {
                // Some exports store the image only as a "[图片]" placeholder without a relative
                // path. Fall back to the md5 embedded in the raw <img> payload, which matches the
                // md5 in the on-disk file name.
                String md5 = extractRefMediaMd5(msg.getRawContent());
                if (StringUtils.hasText(md5)) {
                    imageSummary = cacheService.getImageSummaryByMd5(md5);
                }
            }
            return "(图片描述：" + imageSummary.orElse("图片无描述") + ")";
        } else if ("动画表情".equals(type) || localType == LOCAL_TYPE_EMOJI) {
            String emojiHash = extractPathHash(userId, uuid, msg.getContent(), msg.getRawContent());
            return "(动画表情描述: " + getEmojiSummary(emojiHash, msg.getContent()) + ")";
        } else if ("语音消息".equals(type) || localType == LOCAL_TYPE_AUDIO) {
            String audioHash = extractPathHash(userId, uuid, msg.getContent(), msg.getRawContent());
            return "(语音转译: " + getAudioSummary(audioHash) + ")";
        } else if ("视频消息".equals(type) || localType == LOCAL_TYPE_VIDEO) {
            String videoHash = extractPathHash(userId, uuid, msg.getContent(), msg.getRawContent());
            return "(视频描述：" + getVideoSummary(videoHash) + ")";
        } else if ("文件".equals(type) || localType == LOCAL_TYPE_FILE) {
            return "[" + type + "消息，暂未处理]";
        } else if ("聊天记录".equals(type) || localType == LOCAL_TYPE_CHAT_RECORD) {
            return expandChatRecord(msg.getRawContent());
        } else if ("引用消息".equals(type) || localType == LOCAL_TYPE_REFER_MESSAGE) {
            String raw = msg.getRawContent();
            if (raw != null && raw.contains("<refermsg>")) {
                Matcher typeMatcher = REFER_TYPE_PATTERN.matcher(raw);
                if (typeMatcher.find()) {
                    String referType = typeMatcher.group(1);
                    String mediaHash = extractHashFromXml(raw);

                    if (REFER_TYPE_IMAGE.equals(referType)) {
                        String refMd5 = extractRefMediaMd5(raw);
                        String desc = StringUtils.hasText(refMd5)
                            ? cacheService.getImageSummaryByMd5(refMd5).orElse("图片无描述")
                            : getImageSummary(mediaHash);
                        return content + " (引用了图片: " + desc + ")";
                    } else if (REFER_TYPE_EMOJI.equals(referType)) {
                        return content + " (引用了动画表情: " + getEmojiSummary(mediaHash, raw)
                            + ")";
                    } else if (REFER_TYPE_AUDIO.equals(referType)) {
                        return content + " (引用了语音: " + getAudioSummary(mediaHash) + ")";
                    }
                }
            }
        }
        return expandTextWithLinks(content, msg.getRawContent(), linkCache);
    }

    /**
     * Resolves local storage assets to compute an absolute system SHA-256 track token.
     */
    private String extractPathHash(String userId, String uuid, String content, String rawContent) {
        if (!StringUtils.hasText(content)) {
            return extractHashFromXml(rawContent);
        }

        String relativePath = content.trim();
        if (relativePath.contains("]")) {
            relativePath = relativePath.substring(relativePath.indexOf("]") + 1).trim();
        }

        String lowerPath = relativePath.toLowerCase();
        if (lowerPath.startsWith("images") || lowerPath.startsWith("emojis")
            || lowerPath.startsWith("voices") || lowerPath.startsWith("videos")) {

            // Stable, environment-independent key: the file path relative to the upload root
            // (e.g. "{userId}/{uuid}/images/..."). This guarantees the same image resolves to the
            // same hash whether processing runs inside the Docker container (/app/uploads) or on a
            // developer's host machine, preventing "图片无描述" fallback from path-prefix mismatches.
            Path root = storagePaths.uploadRoot();
            Path resolved = storagePaths.sessionDir(userId, uuid).resolve(relativePath)
                .toAbsolutePath().normalize();
            return HashUtils.sha256(root.relativize(resolved).toString());
        }

        String xmlHash = extractHashFromXml(rawContent);
        return StringUtils.hasText(xmlHash) ? xmlHash : HashUtils.sha256(content.trim());
    }

    /**
     * Evaluates raw XML envelopes to isolate source content trace components or fallback MD5
     * signatures.
     */
    private String extractHashFromXml(String rawContent) {
        if (!StringUtils.hasText(rawContent)) {
            return "";
        }
        Matcher matcher = XML_CDNURL_PATTERN.matcher(rawContent);
        if (matcher.find()) {
            return HashUtils.sha256(matcher.group(1).replace("&amp;", "&").trim());
        }
        Matcher md5Matcher = MD5_EXTRACT_PATTERN.matcher(rawContent.toLowerCase());
        return md5Matcher.find() ? md5Matcher.group(1) : "";
    }

    /**
     * Extracts the canonical image md5 from a reference message's XML payload. The md5 attribute on
     * the embedded {@code <img>} element matches the md5 embedded in the on-disk file name, allowing
     * referenced images to be resolved to their stored description.
     */
    private String extractRefMediaMd5(String rawContent) {
        if (!StringUtils.hasText(rawContent)) {
            return "";
        }
        Matcher matcher = MD5_ATTR_PATTERN.matcher(rawContent);
        return matcher.find() ? matcher.group(1) : "";
    }

    // ==========================================
    // 💡 Optimized Cache Interacting Layers
    // ==========================================

    private String getEmojiSummary(String hash, String sourceContent) {
        if (!StringUtils.hasText(hash)) {
            return "未知表情";
        }
        return cacheService.getEmojiSummary(hash)
            .or(() -> {
                if (sourceContent != null) {
                    Matcher m = MD5_EXTRACT_PATTERN.matcher(sourceContent.toLowerCase());
                    if (m.find()) {
                        String rawMd5 = m.group(1);
                        return cacheService.getEmojiSummary(rawMd5);
                    }
                }
                return Optional.empty();
            })
            .orElse("经典表情/暂无描述");
    }

    private String getImageSummary(String hash) {
        if (!StringUtils.hasText(hash)) {
            return "未找到图片";
        }
        return cacheService.getImageSummary(hash)
            .orElse("图片无描述");
    }

    private String getAudioSummary(String hash) {
        if (!StringUtils.hasText(hash)) {
            return "未找到语音";
        }
        return cacheService.getAudioSummary(hash)
            .orElse("语音无描述");
    }

    private String getVideoSummary(String hash) {
        if (!StringUtils.hasText(hash)) {
            return "未找到视频";
        }
        return cacheService.getVideoSummary(hash)
            .orElse("视频无描述");
    }

    // ==========================================
    // 💡 Chat-record & Link Expansion
    // ==========================================

    /**
     * Expands a forwarded chat-record (聊天记录) message into an indented list of its inner
     * messages, resolved from the embedded &lt;recordinfo&gt; payload.
     */
    private String expandChatRecord(String rawContent) {
        if (!StringUtils.hasText(rawContent)) {
            return "(聊天记录)";
        }
        String title = firstGroup(rawContent, TITLE_TAG_PATTERN, 1);
        StringBuilder sb = new StringBuilder();
        sb.append("(聊天记录：")
            .append(StringUtils.hasText(title) ? decodeEntities(title.trim()) : "群聊的聊天记录")
            .append(")\n");

        String rec = firstGroup(rawContent, RECORDINFO_PATTERN, 1);
        if (rec == null) {
            return sb.toString().stripTrailing();
        }

        Matcher items = DATAITEM_PATTERN.matcher(rec);
        while (items.find()) {
            String item = items.group(1);
            String datatype = firstGroup(item, Pattern.compile("datatype=\"(\\d+)\""), 1);
            String desc = decodeEntities(
                firstGroup(item, Pattern.compile("<datadesc>(.*?)</datadesc>", Pattern.DOTALL), 1));
            String name = decodeEntities(
                firstGroup(item, Pattern.compile("<sourcename>(.*?)</sourcename>"), 1));
            String stime = decodeEntities(
                firstGroup(item, Pattern.compile("<sourcetime>(.*?)</sourcetime>"), 1));
            String ctime = firstGroup(item,
                Pattern.compile("<srcMsgCreateTime>(\\d+)</srcMsgCreateTime>"), 1);
            String time = StringUtils.hasText(stime) ? stime : (ctime != null ? formatUnix(ctime) : "");
            String body = StringUtils.hasText(desc)
                ? desc.replace("\n", "\n     ")
                : mediaPlaceholder(datatype);
            sb.append("  - ")
                .append(StringUtils.hasText(name) ? name : "?")
                .append(" (").append(time).append("): ")
                .append(body).append("\n");
        }
        return sb.toString().stripTrailing();
    }

    /**
     * Keeps the original text but appends a fetched title/description block for every URL it
     * contains (and for any &lt;url&gt; element inside shared-link payloads).
     */
    private String expandTextWithLinks(String content, String rawContent,
        Map<String, String> linkCache) {
        if (!StringUtils.hasText(content)) {
            return content == null ? "" : content;
        }
        Set<String> urls = new LinkedHashSet<>();
        Matcher m = URL_PATTERN.matcher(content);
        while (m.find()) {
            urls.add(cleanUrl(m.group()));
        }
        if (rawContent != null) {
            Matcher u = URL_RAW_PATTERN.matcher(rawContent);
            while (u.find()) {
                urls.add(cleanUrl(u.group(1)));
            }
        }
        if (urls.isEmpty()) {
            return content;
        }
        StringBuilder extra = new StringBuilder();
        for (String url : urls) {
            String cached = linkCache.get(url);
            String preview;
            if (cached == null) {
                preview = fetchLinkPreview(url);
                linkCache.put(url, preview == null ? "" : preview);
            } else {
                preview = cached.isEmpty() ? null : cached;
            }
            if (preview != null) {
                extra.append("\n").append(preview);
            }
        }
        return extra.length() == 0 ? content : content + extra;
    }

    /**
     * Performs an outbound GET and extracts the page title and description (og/meta/twitter tags).
     * Returns null on any failure so the caller simply keeps the original URL text.
     */
    private String fetchLinkPreview(String url) {
        try {
            URI uri = URI.create(url);
            HttpRequest req = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(8))
                .header("User-Agent", "Mozilla/5.0 (compatible; WeChatSummary/1.0)")
                .header("Accept", "text/html,application/xhtml+xml")
                .GET()
                .build();
            HttpResponse<String> resp = HTTP_CLIENT.send(req,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            String body = resp.body();
            if (body == null) {
                return null;
            }
            if (body.length() > 800_000) {
                body = body.substring(0, 800_000);
            }
            String title = extractMeta(body, "title");
            String desc = extractMeta(body, "description");
            if (title == null && desc == null) {
                return null;
            }
            StringBuilder b = new StringBuilder("🔗 ");
            if (title != null) {
                b.append("[").append(sanitize(title)).append("](").append(url).append(")");
            } else {
                b.append(url);
            }
            if (desc != null && !desc.isBlank()) {
                b.append("\n   ").append(sanitize(desc));
            }
            return b.toString();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Extracts the page title or description from raw HTML, preferring OpenGraph / Twitter cards.
     */
    private String extractMeta(String html, String kind) {
        if ("title".equals(kind)) {
            String t = firstGroup(html, TITLE_TAG_PATTERN, 1);
            if (t != null) {
                t = decodeEntities(t.trim());
                if (!t.isEmpty()) {
                    return t;
                }
            }
        }
        Map<String, String> props = new HashMap<>();
        Matcher tag = META_TAG_PATTERN.matcher(html);
        while (tag.find()) {
            Map<String, String> a = parseAttrs(tag.group(1));
            String key = a.get("property");
            if (key == null) {
                key = a.get("name");
            }
            String content = a.get("content");
            if (key != null && content != null) {
                props.put(key.toLowerCase(), content);
            }
        }
        if ("title".equals(kind)) {
            String v = props.get("og:title");
            if (v != null && !v.isBlank()) {
                return decodeEntities(v.trim());
            }
            v = props.get("twitter:title");
            if (v != null && !v.isBlank()) {
                return decodeEntities(v.trim());
            }
            return null;
        }
        String v = props.get("og:description");
        if (v != null && !v.isBlank()) {
            return decodeEntities(v.trim());
        }
        v = props.get("twitter:description");
        if (v != null && !v.isBlank()) {
            return decodeEntities(v.trim());
        }
        v = props.get("description");
        if (v != null && !v.isBlank()) {
            return decodeEntities(v.trim());
        }
        return null;
    }

    private static Map<String, String> parseAttrs(String s) {
        Map<String, String> m = new HashMap<>();
        if (s == null) {
            return m;
        }
        Matcher a = ATTR_PATTERN.matcher(s);
        while (a.find()) {
            m.put(a.group(1).toLowerCase(), a.group(2));
        }
        return m;
    }

    private static String firstGroup(String s, Pattern p, int g) {
        if (s == null) {
            return null;
        }
        Matcher m = p.matcher(s);
        return m.find() ? m.group(g) : null;
    }

    private static String decodeEntities(String s) {
        if (s == null) {
            return "";
        }
        s = s.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
            .replace("&quot;", "\"").replace("&apos;", "'").replace("&nbsp;", " ");
        Matcher m = ENTITY_HEX_PATTERN.matcher(s);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            int cp = Integer.parseInt(m.group(1), 16);
            m.appendReplacement(sb, Matcher.quoteReplacement(new String(Character.toChars(cp))));
        }
        m.appendTail(sb);
        s = sb.toString();
        m = ENTITY_DEC_PATTERN.matcher(s);
        sb = new StringBuffer();
        while (m.find()) {
            int cp = Integer.parseInt(m.group(1));
            m.appendReplacement(sb, Matcher.quoteReplacement(new String(Character.toChars(cp))));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static String cleanUrl(String u) {
        if (u == null) {
            return "";
        }
        while (u.length() > 0 && ",.;:!?]）】,。、 ".indexOf(u.charAt(u.length() - 1)) >= 0) {
            u = u.substring(0, u.length() - 1);
        }
        return u;
    }

    private static String sanitize(String s) {
        if (s == null) {
            return "";
        }
        s = s.replaceAll("\\s+", " ").trim();
        if (s.length() > 300) {
            s = s.substring(0, 300) + "…";
        }
        return s;
    }

    private static String mediaPlaceholder(String datatype) {
        if ("3".equals(datatype)) {
            return "[图片]";
        }
        if ("34".equals(datatype)) {
            return "[语音]";
        }
        if ("43".equals(datatype)) {
            return "[视频]";
        }
        if ("47".equals(datatype)) {
            return "[表情]";
        }
        if ("49".equals(datatype)) {
            return "[文件]";
        }
        return "[消息]";
    }

    private static String formatUnix(String secs) {
        try {
            return new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
                .format(new java.util.Date(Long.parseLong(secs) * 1000L));
        } catch (Exception e) {
            return "";
        }
    }
}