package com.wechat.wechatsummary.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "emoji_summary")
@Getter
@Setter
public class EmojiSummaryEntity {

    @Id
    private String id;

    private String emojiHash;

    private String filePath;

    private String summary;

    private Instant createdAt;
}
