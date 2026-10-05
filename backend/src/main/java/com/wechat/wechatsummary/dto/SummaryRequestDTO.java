package com.wechat.wechatsummary.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.time.LocalDateTime;
import lombok.Data;

@Data
public class SummaryRequestDTO {

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss[.SSS]")
    private LocalDateTime startTime;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss[.SSS]")
    private LocalDateTime endTime;

    /**
     * Optional inline user-confirmed person context (Step 3 extension).
     * When absent, the pipeline falls back to the stored sidecar file for the
     * same time window, or to no context at all — the original flow is kept.
     */
    private PersonContextDto personContext;
}