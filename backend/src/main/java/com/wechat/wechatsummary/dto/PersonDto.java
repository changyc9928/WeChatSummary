package com.wechat.wechatsummary.dto;

import java.util.ArrayList;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Single person in the lightweight per-summary person context.
 *
 * <p>Only the display name plus observed aliases are kept. No profiling
 * (age, gender, occupation, ...) is inferred.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PersonDto {

    private String id;
    private String name;
    private List<String> aliases = new ArrayList<>();
}
