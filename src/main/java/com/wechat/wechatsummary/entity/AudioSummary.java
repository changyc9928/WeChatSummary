package com.wechat.wechatsummary.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.Data;

@Data
@Entity
@Table(name = "audio_summaries")
public class AudioSummary {

    @Id
    private String id;

    private String fileHash;

    private String filePath;

    private String transcript;

    private String summary;

    private LocalDateTime createdAt = LocalDateTime.now();
}
