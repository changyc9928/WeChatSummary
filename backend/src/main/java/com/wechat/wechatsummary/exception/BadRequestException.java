package com.wechat.wechatsummary.exception;

import org.springframework.http.HttpStatus;

/**
 * Signals a malformed or semantically invalid client request, mapped to HTTP 400.
 */
public class BadRequestException extends BusinessException {

    public BadRequestException(String message) {
        super(HttpStatus.BAD_REQUEST, message);
    }
}