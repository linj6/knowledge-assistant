package com.lnjecit.knowledge.assistant.infrastructure.ai;

import com.openai.errors.OpenAIServiceException;
import java.net.SocketTimeoutException;
import java.util.concurrent.TimeoutException;

public final class ModelFailures {
    public enum Kind {
        AUTHENTICATION,
        TIMEOUT,
        RATE_LIMIT,
        SERVER,
        INVALID_RESPONSE,
        CONFIGURATION,
        OTHER
    }

    private ModelFailures() {}

    public static Kind classify(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof SocketTimeoutException
                    || cause instanceof TimeoutException
                    || cause instanceof java.net.http.HttpTimeoutException) return Kind.TIMEOUT;
            if (cause instanceof OpenAIServiceException service) {
                int status = service.statusCode();
                if (status == 401 || status == 403) return Kind.AUTHENTICATION;
                if (status == 429) return Kind.RATE_LIMIT;
                if (status >= 500) return Kind.SERVER;
            }
            if (cause instanceof ModelResponseChecks.InvalidResponseException
                    || cause instanceof com.openai.errors.OpenAIInvalidDataException) return Kind.INVALID_RESPONSE;
        }
        return Kind.OTHER;
    }

    public static IllegalStateException safe(String stage, Throwable failure) {
        String safeStage =
                switch (stage) {
                    case "CONFIGURATION", "CHAT_PLAIN", "CHAT_JSON", "EMBEDDING_SINGLE", "EMBEDDING_BATCH" -> stage;
                    default -> "UNKNOWN";
                };
        Kind kind = "CONFIGURATION".equals(safeStage) ? Kind.CONFIGURATION : classify(failure);
        return new IllegalStateException("Model request failed: stage=" + safeStage + " kind=" + kind);
    }

    // 不传播供应商消息、请求头或异常 cause，防止错误正文回显密钥。
    public static IllegalStateException safe(Throwable failure) {
        return new IllegalStateException("Model request failed: " + classify(failure));
    }
}
