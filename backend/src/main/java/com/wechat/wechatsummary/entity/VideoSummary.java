package com.wechat.wechatsummary.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "video_summaries")
@Getter
@Setter
public class VideoSummary {

    @Id
    private String id;

    private String fileHash;

    private String filePath;

    private String transcript;

    private String summary;

    private LocalDateTime createdAt = LocalDateTime.now();
}
