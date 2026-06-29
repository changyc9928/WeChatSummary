package com.wechat.wechatsummary.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wechat.wechatsummary.config.StorageConfig;
import com.wechat.wechatsummary.dto.WeChatMessageDto;
import com.wechat.wechatsummary.entity.AudioSummary;
import com.wechat.wechatsummary.entity.ImageSummaryEntity;
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
    // 💡 将原来的 NICKNAME_CLEAN_PATTERN 替换为这个：
    // 专门剔除 Emoji 表情符号、高频特殊图形符号，但保留正常的各国文字和字母
    private static final Pattern NICKNAME_CLEAN_PATTERN = Pattern.compile(
        "[\\uD83C\\uDF00-\\uD83D\\uDE4F\\uD83D\\uDE80-\\uD83D\\uDEFF\\u2600-\\u27BF\\u2300-\\u23FF\\u2B50\\u3299\\u3297]|"
            +
            "\\p{Co}" // 剔除自定义私有区域符号（很多非主流花哨字体都在这里）
    );

    // 💡 新增：用于匹配文本中出现的微信 wxid 的正则表达式（通常是 wxid_ 开头，后面跟着字母数字，通常共14-20位或以上）
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

            // 💡 核心改造一：扫描全部消息，构建一个群友 "wxid -> 清洗后的昵称" 的全局字典
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
                // 1. 媒体替换 + 💡 核心改造二：用全局字典替换内容中被 Tag/艾特 的 wxid
                String cleanContent = processMessageContent(uuid, msg);
                cleanContent = replaceWxidsWithNicknames(cleanContent, userMap);

                // 2. 获取清洗后的发送人名称
                String rawName =
                    StringUtils.hasText(msg.getSenderDisplayName()) ? msg.getSenderDisplayName()
                        : msg.getSenderUsername();
                String cleanName = userMap.getOrDefault(msg.getSenderUsername(),
                    cleanNickname(rawName));

                if ("系统消息".equals(msg.getType())) {
                    cleanName = "系统消息";
                }

                // 3. 拼接输出
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

    /**
     * 💡 辅助方法：第一次遍历，收集群里所有发言人的 wxid 和昵称映射
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
                    cleanName =
                        "微信用户_" + wxid.substring(Math.max(0, wxid.length() - 4)); // 纯符号昵称用后4位兜底
                }
                userMap.put(wxid, cleanName);
            }
        }
        return userMap;
    }

    /**
     * 💡 辅助方法：将文本中所有的 wxid 替换为对应的昵称
     */
    private String replaceWxidsWithNicknames(String content, Map<String, String> userMap) {
        if (!StringUtils.hasText(content)) {
            return content;
        }
        Matcher matcher = WXID_PATTERN.matcher(content);
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            String wxid = matcher.group();
            // 如果在字典里找到了昵称，就替换成 "@昵称" 或 "昵称"，如果找不到，保留原本的 wxid
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

    // ==========================================
    // 原本的媒体逻辑保持不变
    // ==========================================
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

    private String getEmojiSummary(String hash, String sourceContent) {
        if (!StringUtils.hasText(hash)) {
            return "未知表情";
        }
        return imageSummaryCacheService.get(hash).orElseGet(() -> {
            ImageSummaryEntity entity = imageSummaryRepository.findByImageHash(hash);
            if (entity == null && sourceContent != null) {
                Matcher m = MD5_EXTRACT_PATTERN.matcher(sourceContent.toLowerCase());
                if (m.find()) {
                    entity = imageSummaryRepository.findByImageHash(m.group(1));
                    if (entity != null && StringUtils.hasText(entity.getSummary())) {
                        imageSummaryCacheService.put(hash, entity.getSummary());
                    }
                }
            }
            if (entity != null && StringUtils.hasText(entity.getSummary())) {
                imageSummaryCacheService.put(hash, entity.getSummary());
                return entity.getSummary();
            }
            return "经典表情/暂无描述";
        });
    }

    private String getImageSummary(String hash, String sourceContent) {
        if (!StringUtils.hasText(hash)) {
            return "未找到图片";
        }
        return imageSummaryCacheService.get(hash).orElseGet(() -> {
            ImageSummaryEntity entity = imageSummaryRepository.findByImageHash(hash);
            if (entity == null && sourceContent != null) {
                Matcher m = MD5_EXTRACT_PATTERN.matcher(sourceContent.toLowerCase());
                if (m.find()) {
                    entity = imageSummaryRepository.findByImageHash(m.group(1));
                }
            }
            if (entity != null && StringUtils.hasText(entity.getSummary())) {
                imageSummaryCacheService.put(hash, entity.getSummary());
                return entity.getSummary();
            }
            return "图片无描述";
        });
    }

    private String getAudioSummary(String hash, String sourceContent) {
        if (!StringUtils.hasText(hash)) {
            return "未找到语音";
        }
        return mediaSummaryCacheService.getAudioSummary(hash).orElseGet(() -> {
            return audioSummaryRepository.findByFileHash(hash).map(AudioSummary::getSummary)
                .map(summary -> {
                    mediaSummaryCacheService.putAudioSummary(hash, summary);
                    return summary;
                }).orElse("语音无描述");
        });
    }
}