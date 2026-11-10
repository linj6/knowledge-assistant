package com.lnjecit.knowledge.assistant.common.api;

import com.lnjecit.knowledge.assistant.common.RequestIdContext;
import com.lnjecit.knowledge.assistant.common.error.ErrorCode;

/** 成功和失败共用的 API 协议，沿用请求过滤器设置的追踪标识。 */
public record Result<T>(Integer code, String message, T data, String requestId) {
    public static <T> Result<T> success(T data) {
        return new Result<>(0, "成功", data, RequestIdContext.current());
    }

    public static Result<Void> failure(ErrorCode code) {
        return new Result<>(code.getCode(), code.getMessage(), null, RequestIdContext.current());
    }
}
