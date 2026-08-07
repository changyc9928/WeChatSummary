package com.wechat.wechatsummary.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "image_summary")
@Getter
@Setter
public class ImageSummaryEntity {

    @Id
    private String id;

    private String imageHash;

    private String filePath;

    private String summary;

    private Instant createdAt;
}