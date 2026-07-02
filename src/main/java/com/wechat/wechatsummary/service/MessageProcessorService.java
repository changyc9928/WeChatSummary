package com.wechat.wechatsummary.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wechat.wechatsummary.config.StorageConfig;
import com.wechat.wechatsummary.dto.WeChatMessageDto;
import com.wechat.wechatsummary.entity.AudioSummary;
import com.wechat.wechatsummary.repository.AudioSummaryRepository;
import com.wechat.wechatsummary.repository.ImageSummaryRepository;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
@Slf4j
public class MessageProcessorService {

    private static final Pattern XML_CDNURL_PATTERN = Pattern.compile(
        "cdnurl\\s*=\\s*\"([^\"]+)\"");
    private static final Pattern MD5_EXTRACT_PATTERN = Pattern.compile("([a-f0-9]{32})");
    private static final Pattern REFER_TYPE_PATTERN = Pattern.compile(
        "<refermsg>.*<type>(\\d+)</type>.*</refermsg>", Pattern.DOTALL);

    private static final Pattern NICKNAME_CLEAN_PATTERN = Pattern.compile(
        "[\\uD83C\\uDF00-\\uD83D\\uDE4F\\uD83D\\uDE80-\\uD83D\\uDEFF\\u2600-\\u27BF\\u2300-\\u23FF\\u2B50\\u3299\\u3297]|\\p{Co}"
    );

    private static final Pattern WXID_PATTERN = Pattern.compile("wxid_[a-zA-Z0-9]{10,25}");

    private final ImageSummaryCacheService imageSummaryCacheService;
    private final MediaSummaryCacheService mediaSummaryCacheService;
    private final ImageSummaryRepository imageSummaryRepository;
    private final AudioSummaryRepository audioSummaryRepository;
    private final ObjectMapper objectMapper;
    private final StorageConfig storageConfig;

    public void processJsonAndSave(String uuid, String inputJsonFilePath,
        String outputTxtFilePath) {
        try {
            Path inputPath = Paths.get(inputJsonFilePath);
            if (!Files.exists(inputPath)) {
                log.error("Input JSON file does not exist: {}", inputJsonFilePath);
                return;
            }

            objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
            String jsonStr = Files.readString(inputPath, StandardCharsets.UTF_8);
            Map<String, Object> rootMap = objectMapper.readValue(jsonStr,
                new TypeReference<Map<String, Object>>() {
                });

            List<WeChatMessageDto> messages = objectMapper.convertValue(
                rootMap.get("messages"),
                new TypeReference<List<WeChatMessageDto>>() {
                }
            );

            if (messages == null || messages.isEmpty()) {
                log.warn("No messages found. Skip.");
                return;
            }

            Map<String, String> userMap = buildUserMap(messages);
            StringBuilder textBuilder = new StringBuilder();

            Map<String, Object> session = (Map<String, Object>) rootMap.get("session");
            if (session != null) {
                textBuilder.append("--- 群聊基本信息 ---\n")
                    .append("群名称: ").append(session.get("nickname")).append("\n")
                    .append("总消息数: ").append(session.get("messageCount")).append("\n")
                    .append("--------------------\n\n");
            }

            for (WeChatMessageDto msg : messages) {
                String cleanContent = processMessageContent(uuid, msg);
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
            }

            Path outputPath = Paths.get(outputTxtFilePath);
            if (outputPath.getParent() != null) {
                Files.createDirectories(outputPath.getParent());
            }

            Files.writeString(outputPath, textBuilder.toString(), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);

            log.info("Successfully processed messages with wxid-mapping and saved to: {}",
                outputTxtFilePath);

        } catch (IOException e) {
            log.error("Failed to process files.", e);
            throw new RuntimeException("数据转换纯文本失败", e);
        }
    }

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

    private String cleanNickname(String name) {
        if (!StringUtils.hasText(name)) {
            return "";
        }
        return NICKNAME_CLEAN_PATTERN.matcher(name).replaceAll("").trim();
    }

    private String processMessageContent(String uuid, WeChatMessageDto msg) {
        String content = msg.getContent();
        if (!StringUtils.hasText(content)) {
            content = "";
        }

        String type = msg.getType();
        long localType = msg.getLocalType() != null ? msg.getLocalType() : 0L;

        if ("图片消息".equals(type) || localType == 3) {
            String imageHash = extractPathHash(uuid, msg.getContent(), msg.getRawContent());
            return "(" + getImageSummary(imageHash, msg.getContent()) + ")";
        } else if ("动画表情".equals(type) || localType == 47) {
            String emojiHash = extractPathHash(uuid, msg.getContent(), msg.getRawContent());
            return "(动画表情描述: " + getEmojiSummary(emojiHash, msg.getContent()) + ")";
        } else if ("语音消息".equals(type) || localType == 34) {
            String audioHash = extractPathHash(uuid, msg.getContent(), msg.getRawContent());
            return "(语音转译: " + getAudioSummary(audioHash, msg.getContent()) + ")";
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
                        return content + " (引用了图片: " + getImageSummary(mediaHash, raw) + ")";
                    } else if ("47".equals(referType)) {
                        return content + " (引用了动画表情: " + getEmojiSummary(mediaHash, raw)
                            + ")";
                    } else if ("34".equals(referType)) {
                        return content + " (引用了语音: " + getAudioSummary(mediaHash, raw) + ")";
                    }
                }
            }
        }
        return content;
    }

    private String extractPathHash(String uuid, String content, String rawContent) {
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
            return sha256(
                storageConfig.getUploadDir().resolve(uuid).resolve(relativePath).toAbsolutePath()
                    .toString());
        }
        String xmlHash = extractHashFromXml(rawContent);
        return StringUtils.hasText(xmlHash) ? xmlHash : sha256(content.trim());
    }

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

    private String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // ==========================================
    // 💡 优化后的缓存调用区
    // ==========================================

    private String getEmojiSummary(String hash, String sourceContent) {
        if (!StringUtils.hasText(hash)) {
            return "未知表情";
        }
        // 1. 优先使用当前的 Hash 从自动化缓存机制里拿数据
        return imageSummaryCacheService.getSummary(hash)
            .or(() -> {
                // 2. 如果没拿到，且带有 XML 原始内容，提取出里面的纯 MD5 字符串再尝试拿一次（兼容微信老格式）
                if (sourceContent != null) {
                    Matcher m = MD5_EXTRACT_PATTERN.matcher(sourceContent.toLowerCase());
                    if (m.find()) {
                        String rawMd5 = m.group(1);
                        return imageSummaryCacheService.getSummary(rawMd5);
                    }
                }
                return java.util.Optional.empty();
            })
            // 3. 如果两边都拿不到，返回兜底文本
            .orElse("经典表情/暂无描述");
    }

    private String getImageSummary(String hash, String sourceContent) {
        if (!StringUtils.hasText(hash)) {
            return "未找到图片";
        }
        // 同样享受一键自动化缓存检索的待遇
        return imageSummaryCacheService.getSummary(hash)
            .or(() -> {
                if (sourceContent != null) {
                    Matcher m = MD5_EXTRACT_PATTERN.matcher(sourceContent.toLowerCase());
                    if (m.find()) {
                        return imageSummaryCacheService.getSummary(m.group(1));
                    }
                }
                return java.util.Optional.empty();
            })
            .orElse("图片无描述");
    }

    private String getAudioSummary(String hash, String sourceContent) {
        if (!StringUtils.hasText(hash)) {
            return "未找到语音";
        }
        // 保持原样不变，后续你的 MediaSummaryCacheService 也可以参考 Image 方案进行改造
        return mediaSummaryCacheService.getAudioSummary(hash).orElseGet(() -> {
            return audioSummaryRepository.findByFileHash(hash).map(AudioSummary::getSummary)
                .map(summary -> {
                    mediaSummaryCacheService.putAudioSummary(hash, summary);
                    return summary;
                }).orElse("语音无描述");
        });
    }
}