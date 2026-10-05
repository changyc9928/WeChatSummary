package com.wechat.wechatsummary.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class AuthResponse {

    /** Opaque Bearer token; the user UUID stays server-side in Redis. */
    private String token;
}
