package com.wechat.wechatsummary.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

/**
 * Base exception for all business-level errors. Carries the HTTP status that should be returned
 * to the client alongside the unified error envelope.
 */
@Getter
public class BusinessException extends RuntimeException {

    private final HttpStatus status;

    public BusinessException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }
}