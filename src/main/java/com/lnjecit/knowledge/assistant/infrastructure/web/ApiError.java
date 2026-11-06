package com.lnjecit.knowledge.assistant.infrastructure.web;

/** 仅包装错误响应，成功响应保持原样。 */
public record ApiError(String code, String message, String requestId) {}
