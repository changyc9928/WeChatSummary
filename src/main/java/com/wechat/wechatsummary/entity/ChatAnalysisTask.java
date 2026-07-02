package com.wechat.wechatsummary.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "chat_analysis_task")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ChatAnalysisTask {

    @Id
    @Column(length = 36)
    private UUID id; // 存 UUID.toString()

    @Column(nullable = false, length = 20)
    private String status; // PROCESSING, SUCCESS, FAILED

    // 💡 针对 Postgres 的优化：直接声明为 text 属性，去掉易报错的 @Lob
    @Column(columnDefinition = "text")
    private String result;

    @Column(name = "error_message", columnDefinition = "text")
    private String errorMessage;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }
}