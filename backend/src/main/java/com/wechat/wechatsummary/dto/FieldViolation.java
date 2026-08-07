package com.wechat.wechatsummary.dto;

/**
 * Field-level validation violation, attached to the envelope returned for bad request payloads.
 */
public record FieldViolation(String field, String message) {
}