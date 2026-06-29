package com.wechat.wechatsummary.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

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
