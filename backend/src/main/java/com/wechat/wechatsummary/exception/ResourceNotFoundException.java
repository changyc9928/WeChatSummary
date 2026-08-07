package com.wechat.wechatsummary.exception;

import org.springframework.http.HttpStatus;

/**
 * Signals a requested resource does not exist, mapped to HTTP 404.
 */
public class ResourceNotFoundException extends BusinessException {

    public ResourceNotFoundException(String message) {
        super(HttpStatus.NOT_FOUND, message);
    }
}