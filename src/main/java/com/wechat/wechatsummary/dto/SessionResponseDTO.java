package com.wechat.wechatsummary.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class SessionResponseDTO {

    private String uuid;
    private String jsonFilename; // This will hold the Chat Name + Time Window
    private String uploadedAt;   // Clean formatted upload timestamp string
}
