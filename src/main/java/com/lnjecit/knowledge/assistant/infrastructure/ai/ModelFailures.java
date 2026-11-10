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
        REQUEST_REJECTED,
        CONNECTION,
        OTHER
    }

    private ModelFailures() {}

    public static final class SafeModelException extends IllegalStateException {
        private final Kind kind;

        public Kind kind() {
            return kind;
        }

        private SafeModelException(Kind kind) {
            this(kind, "RERANK");
        }

        private SafeModelException(Kind kind, String stage) {
            super("Model request failed: stage=" + stage + " kind=" + kind);
            this.kind = kind;
        }
    }

    public static SafeModelException rerankFailure(Kind kind) {
        return new SafeModelException(kind);
    }

    public static Kind httpStatus(int status) {
        if (status == 401 || status == 403) return Kind.AUTHENTICATION;
        if (status == 429) return Kind.RATE_LIMIT;
        if (status >= 500 && status <= 599) return Kind.SERVER;
        if (status >= 400 && status <= 499) return Kind.REQUEST_REJECTED;
        return Kind.INVALID_RESPONSE;
    }

    public static Kind classify(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof SafeModelException safe) return safe.kind;
            if (configurationCode(cause) != null) return Kind.CONFIGURATION;
            if (cause instanceof SocketTimeoutException
                    || cause instanceof TimeoutException
                    || cause instanceof java.net.http.HttpTimeoutException) return Kind.TIMEOUT;
            if (cause instanceof OpenAIServiceException service) {
                int status = service.statusCode();
                if (status == 401 || status == 403) return Kind.AUTHENTICATION;
                if (status == 429) return Kind.RATE_LIMIT;
                if (status >= 500) return Kind.SERVER;
                if (status >= 400) return Kind.REQUEST_REJECTED;
            }
            if (cause instanceof ModelResponseChecks.InvalidResponseException
                    || cause instanceof com.openai.errors.OpenAIInvalidDataException) return Kind.INVALID_RESPONSE;
        }
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof java.io.IOException || cause instanceof com.openai.errors.OpenAIIoException)
                return Kind.CONNECTION;
        }
        return Kind.OTHER;
    }

    public static IllegalStateException safe(String stage, Throwable failure) {
        String safeStage =
                switch (stage) {
                    case "CONFIGURATION",
                            "CHAT_PLAIN",
                            "CHAT_JSON",
                            "EMBEDDING_SINGLE",
                            "EMBEDDING_BATCH",
                            "RERANK" -> stage;
                    default -> "UNKNOWN";
                };
        Kind kind = "CONFIGURATION".equals(safeStage) ? Kind.CONFIGURATION : classify(failure);
        String code = configurationCode(failure);
        if (code != null)
            return new IllegalStateException(
                    "Model request failed: stage=" + safeStage + " kind=" + kind + " code=" + code);
        return new SafeModelException(kind, safeStage);
    }

    private static String configurationCode(Throwable failure) {
        if (!(failure instanceof IllegalStateException) || failure.getMessage() == null) return null;
        return switch (failure.getMessage()) {
            case "assistant.model.api-key is required when enabled" -> "MISSING_API_KEY";
            case "assistant.model chat-model and embedding-model are required when enabled" -> "MISSING_MODEL_ID";
            case "assistant.model.embedding-model is required when enabled" -> "MISSING_EMBEDDING_MODEL";
            case "assistant.model.chat-model is required when enabled" -> "MISSING_CHAT_MODEL";
            case "assistant.model.base-url must be an HTTP endpoint without credentials" -> "INVALID_ENDPOINT";
            case "assistant.model.dimensions must be between 1 and 16000" -> "INVALID_DIMENSIONS";
            case "assistant.model.timeout-seconds must be between 1 and 300" -> "INVALID_TIMEOUT";
            case "Explicit model online authorization required" -> "MISSING_ONLINE_AUTHORIZATION";
            case "Explicit embedding baseline authorization required" -> "MISSING_BASELINE_AUTHORIZATION";
            default -> null;
        };
    }

    // 不传播供应商消息、请求头或异常 cause，防止错误正文回显密钥。
    public static IllegalStateException safe(Throwable failure) {
        return new IllegalStateException("Model request failed: " + classify(failure));
    }
}
