package com.wechat.wechatsummary.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

/**
 * Server-wide key/value settings edited from the UI settings sidebar.
 *
 * <p>Used for the AI provider credentials/endpoints/models. A row present here overrides the
 * corresponding environment default; deleting all {@code *.api-key} rows (or the whole table
 * content) falls back to the {@code application.yaml} / environment configuration.
 */
@Entity
@Table(name = "app_settings")
@Getter
@Setter
public class AppSetting {

    @Id
    @Column(name = "setting_key", nullable = false, length = 128)
    private String key;

    @Column(name = "setting_value", nullable = false, columnDefinition = "TEXT")
    private String value;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
