package com.wechat.wechatsummary.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Lightweight, per-summary-task person context.
 *
 * <p>This is intentionally NOT a persistent knowledge graph: it only covers
 * people actually appearing / mentioned / discussed in the current summary
 * window. It is stored as a sidecar file next to the summary outputs and is
 * keyed by the summary time window, so a different date range regenerates it.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PersonContextDto {

    private List<PersonDto> people = new ArrayList<>();
    private List<PersonRelationshipDto> relationships = new ArrayList<>();

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss[.SSS]")
    private LocalDateTime startTime;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss[.SSS]")
    private LocalDateTime endTime;
}
