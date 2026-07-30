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
}