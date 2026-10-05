package com.wechat.wechatsummary.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Simple undirected relationship between two people in the current chat scope.
 *
 * <p>{@code from} / {@code to} reference {@link PersonDto#getId()}.
 * {@code relationship} is free text (e.g. 朋友 / 熟人 / 同事). When the model
 * cannot determine it, callers may use "关系不确定" or omit the edge entirely.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PersonRelationshipDto {

    private String from;
    private String to;
    private String relationship;
}
