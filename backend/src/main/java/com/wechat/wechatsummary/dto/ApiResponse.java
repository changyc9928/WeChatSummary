package com.wechat.wechatsummary.dto;

/**
 * Unified response envelope returned by every REST endpoint.
 *
 * <p>On success {@code code} is {@code 0} and {@code data} carries the payload. On failure
 * {@code code} mirrors the HTTP status (e.g. {@code 400}, {@code 401}, {@code 404},
 * {@code 500}) and {@code data} is {@code null}.
 */
public record ApiResponse<T>(int code, String message, T data) {

    public static final int SUCCESS_CODE = 0;
    public static final String SUCCESS_MESSAGE = "success";

    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>(SUCCESS_CODE, SUCCESS_MESSAGE, data);
    }

    public static <T> ApiResponse<T> success(String message, T data) {
        return new ApiResponse<>(SUCCESS_CODE, message, data);
    }

    public static <T> ApiResponse<T> error(int code, String message) {
        return new ApiResponse<>(code, message, null);
    }
}