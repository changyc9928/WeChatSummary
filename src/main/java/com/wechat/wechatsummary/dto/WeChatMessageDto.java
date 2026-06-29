package com.wechat.wechatsummary.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class WeChatMessageDto {
    private Long localId;
    private String platformMessageId;
    private Long createTime;
    private String formattedTime;
    private String type;
    private Long localType;
    private Integer chatLabType;
    private String content;
    private String rawContent;
    private Integer isSend;
    private String senderUsername;
    private String senderDisplayName;
    private String senderAvatar;
    private String source;
}