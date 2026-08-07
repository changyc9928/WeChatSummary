package com.wechat.wechatsummary.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wechat.wechatsummary.config.StorageConfig;
import com.wechat.wechatsummary.dto.WeChatMessageDto;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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

    private final WeChatSummaryCacheService cacheService;
    private final ObjectMapper objectMapper;
    private final StorageConfig storageConfig;

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
            Path sessionDir = storageConfig.getUploadDir().resolve(userId).resolve(uuid);
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
            Path userOutputDir = storageConfig.getUploadDir().resolve(userId).resolve("outputs");
            Files.createDirectories(userOutputDir);
            Path outputPath = userOutputDir.resolve(uuid + "_processed.md");

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
                String cleanContent = processMessageContent(userId, uuid, msg);
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
                if (processedCount % 500 == 0 && log.isDebugEnabled()) {
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
                    cleanName = "微信用户_" + wxid.substring(Math.max(0, wxid.length() - 4));
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
    private String processMessageContent(String userId, String uuid, WeChatMessageDto msg) {
        String content = msg.getContent();
        if (!StringUtils.hasText(content)) {
            content = "";
        }

        String type = msg.getType();
        long localType = msg.getLocalType() != null ? msg.getLocalType() : 0L;

        if ("图片消息".equals(type) || localType == 3) {
            String imageHash = extractPathHash(userId, uuid, msg.getContent(), msg.getRawContent());
            return "(图片描述：" + getImageSummary(imageHash) + ")";
        } else if ("动画表情".equals(type) || localType == 47) {
            String emojiHash = extractPathHash(userId, uuid, msg.getContent(), msg.getRawContent());
            return "(动画表情描述: " + getEmojiSummary(emojiHash, msg.getContent()) + ")";
        } else if ("语音消息".equals(type) || localType == 34) {
            String audioHash = extractPathHash(userId, uuid, msg.getContent(), msg.getRawContent());
            return "(语音转译: " + getAudioSummary(audioHash) + ")";
        } else if ("视频消息".equals(type) || localType == 43 || "文件".equals(type)
            || localType == 49) {
            return "[" + type + "消息，暂未处理]";
        } else if ("引用消息".equals(type) || localType == 244813135921L) {
            String raw = msg.getRawContent();
            if (raw != null && raw.contains("<refermsg>")) {
                Matcher typeMatcher = REFER_TYPE_PATTERN.matcher(raw);
                if (typeMatcher.find()) {
                    String referType = typeMatcher.group(1);
                    String mediaHash = extractHashFromXml(raw);

                    if ("3".equals(referType)) {
                        return content + " (引用了图片: " + getImageSummary(mediaHash) + ")";
                    } else if ("47".equals(referType)) {
                        return content + " (引用了动画表情: " + getEmojiSummary(mediaHash, raw)
                            + ")";
                    } else if ("34".equals(referType)) {
                        return content + " (引用了语音: " + getAudioSummary(mediaHash) + ")";
                    }
                }
            }
        }
        return content;
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
            || lowerPath.startsWith("voices")) {

            // Correct path construction: uploadDir / userId / uuid / relativePath
            Path resolvedPath = storageConfig.getUploadDir().resolve(userId).resolve(uuid)
                .resolve(relativePath);
            return sha256(resolvedPath.toAbsolutePath().normalize().toString());
        }

        String xmlHash = extractHashFromXml(rawContent);
        return StringUtils.hasText(xmlHash) ? xmlHash : sha256(content.trim());
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
            return sha256(matcher.group(1).replace("&amp;", "&").trim());
        }
        Matcher md5Matcher = MD5_EXTRACT_PATTERN.matcher(rawContent.toLowerCase());
        return md5Matcher.find() ? md5Matcher.group(1) : "";
    }

    /**
     * Computes SHA-256 representation string hashes over target identifiers.
     */
    private String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            log.error(
                "Cryptographic messaging digest algorithm instantiation configuration collapsed for SHA-256.",
                e);
            throw new RuntimeException(e);
        }
    }

    // ==========================================
    // 💡 Optimized Cache Interacting Layers
    // ==========================================

    private String getEmojiSummary(String hash, String sourceContent) {
        if (!StringUtils.hasText(hash)) {
            return "未知表情";
        }
        return cacheService.getImageSummary(hash)
            .or(() -> {
                if (sourceContent != null) {
                    Matcher m = MD5_EXTRACT_PATTERN.matcher(sourceContent.toLowerCase());
                    if (m.find()) {
                        String rawMd5 = m.group(1);
                        return cacheService.getImageSummary(rawMd5);
                    }
                }
                return java.util.Optional.empty();
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
}