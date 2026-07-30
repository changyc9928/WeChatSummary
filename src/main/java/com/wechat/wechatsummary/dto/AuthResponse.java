package com.wechat.wechatsummary.dto;

public class AuthResponse {

    private String uuid;

    public AuthResponse(String uuid) {
        this.uuid = uuid;
    }

    public String getUuid() {
        return uuid;
    }

    public void setUuid(String uuid) {
        this.uuid = uuid;
    }
}