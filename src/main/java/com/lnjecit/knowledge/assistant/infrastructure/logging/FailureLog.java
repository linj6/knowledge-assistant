package com.lnjecit.knowledge.assistant.infrastructure.logging;

import com.lnjecit.knowledge.assistant.common.RequestIdContext;
import com.lnjecit.knowledge.assistant.infrastructure.ai.ModelFailures;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import org.slf4j.Logger;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/** 只将经过重建的诊断异常交给日志系统，原始 Throwable 不进入 ThrowableProxy。 */
@Component
public final class FailureLog {
    private static final CopyOnWriteArrayList<String> SECRETS = new CopyOnWriteArrayList<>();

    public FailureLog(Environment environment) {
        for (String key : new String[] {
            "assistant.model.api-key",
            "assistant.rerank.api-key",
            "spring.datasource.password",
            "assistant.minio.access-key",
            "assistant.minio.secret-key"
        }) {
            String value = environment.getProperty(key);
            if (value != null && !value.isBlank()) SECRETS.addIfAbsent(value);
        }
    }

    /** 受控协议拒绝仅记录固定分类，不接收原始模型文本或异常。 */
    public static void rejected(Logger logger, String boundary, String classification) {
        try {
            logger.warn("requestId={} boundary={} error={}", RequestIdContext.current(), boundary, classification);
        } catch (RuntimeException loggingFailure) {
            // 日志失败不改变领域失败结果。
        }
    }

    public static String redact(String value) {
        if (value == null) return "";
        String result = value;
        for (String secret : SECRETS) result = result.replace(secret, "[REDACTED]");
        result = result.replaceAll("(?i)((?:https?|jdbc:postgresql)://)[^/\\s:@]+:[^/\\s@]+@", "$1[REDACTED]@");
        // 先处理带引号的值，避免密码中的空格、逗号导致只遮盖一部分。
        String credential =
                "(?:api[-_]?key|password|passwd|(?:access|secret)[-_]?key|secret|(?:access[-_]?|refresh[-_]?)?token|authorization|cookie|set-cookie)";
        result = result.replaceAll(
                "(?i)([\\\"']?" + credential + "[\\\"']?\\s*[:=]\\s*)\\\"(?:\\\\.|[^\\\"\\\\])*\\\"", "$1[REDACTED]");
        result = result.replaceAll("(?i)([\\\"']?" + credential + "[\\\"']?\\s*[:=]\\s*)'(?:[^']*)'", "$1[REDACTED]");
        result = result.replaceAll("(?i)((?:authorization|cookie|set-cookie)\\s*[:=]\\s*)[^\\r\\n]+", "$1[REDACTED]");
        result = result.replaceAll("(?i)(bearer\\s+)[^\\s,;\\\"']+", "$1[REDACTED]");
        result = result.replaceAll("(?i)(" + credential + "[\\\"']?\\s*[:=]\\s*)[^\\s,;&\\\"'}]+", "$1[REDACTED]");
        return result.replace('\r', ' ').replace('\n', ' ');
    }

    public static void error(Logger logger, String boundary, Throwable failure) {
        error(logger, boundary, failure, java.util.List.of());
    }

    public static void error(Logger logger, String boundary, Throwable failure, java.util.List<String> payloads) {
        try {
            logger.error(
                    "requestId={} boundary={} kind={}",
                    RequestIdContext.current(),
                    boundary,
                    ModelFailures.classify(failure),
                    diagnostic(failure, Collections.newSetFromMap(new IdentityHashMap<>()), 0, payloads));
        } catch (RuntimeException loggingFailure) {
            // 日志故障不能改变失败状态写入、补偿及对外错误协议，也不递归记录日志。
        }
    }

    private static String providerField(com.openai.errors.OpenAIServiceException service, String field) {
        // 只读取 error.message/code/type，不调用包含 headers 和完整 body 的 SDK message/toString。
        try {
            Object body = service.body().convert(java.util.Map.class);
            if (body instanceof java.util.Map<?, ?> fields) {
                Object error = fields.get("error");
                if (error instanceof java.util.Map<?, ?> nested) fields = nested;
                Object message = fields.get(field);
                if (message instanceof String text) return redact(text);
            }
            return "unavailable(missing string field or non-object error response)";
        } catch (RuntimeException invalidBody) {
            return "unavailable(error response cannot be decoded safely)";
        }
    }

    private static Throwable diagnostic(
            Throwable source, Set<Throwable> seen, int depth, java.util.List<String> payloads) {
        if (source == null || depth >= 16 || !seen.add(source)) return null;
        String type = source.getClass().getName();
        String message = redact(source.getMessage());
        if (!payloads.isEmpty() && source instanceof com.openai.errors.OpenAIInvalidDataException)
            message = "Invalid model response (raw data omitted)";
        if (source instanceof com.openai.errors.OpenAIServiceException service)
            message = "httpStatus=" + service.statusCode() + " providerCode="
                    + redact(service.code().orElseGet(() -> providerField(service, "code"))) + " providerType="
                    + redact(service.type().orElseGet(() -> providerField(service, "type"))) + " message="
                    + providerField(service, "message")
                    + " rawResponse=omitted(headers/body may contain credentials or request payload)";
        if (source instanceof java.sql.SQLException sql)
            message = "sqlState=" + sql.getSQLState() + " vendorCode=" + sql.getErrorCode() + " "
                    + redact(sql.getMessage());
        if (source instanceof co.elastic.clients.elasticsearch._types.ElasticsearchException es)
            message = "httpStatus=" + es.status() + " providerCode="
                    + redact(es.error().type()) + " reason=" + redact(es.error().reason());
        if (source instanceof io.minio.errors.ErrorResponseException minio)
            message = "httpStatus="
                    + (minio.response() == null
                            ? "unavailable"
                            : minio.response().code())
                    + " providerCode=" + redact(minio.errorResponse().code()) + " reason="
                    + redact(minio.errorResponse().message())
                    + " rawResponse=omitted(headers/body may contain credentials)";
        for (String payload : payloads) {
            if (payload != null && !payload.isBlank()) message = message.replace(redact(payload), "[PAYLOAD_OMITTED]");
        }
        RuntimeException copy = new RuntimeException(type + ": " + message);
        copy.setStackTrace(source.getStackTrace());
        copy.initCause(diagnostic(source.getCause(), seen, depth + 1, payloads));
        for (Throwable suppressed : source.getSuppressed()) {
            Throwable safe = diagnostic(suppressed, seen, depth + 1, payloads);
            if (safe != null) copy.addSuppressed(safe);
        }
        return copy;
    }
}
