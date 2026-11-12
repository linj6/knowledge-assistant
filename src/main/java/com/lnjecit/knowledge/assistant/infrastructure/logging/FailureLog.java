package com.lnjecit.knowledge.assistant.infrastructure.logging;

import com.lnjecit.knowledge.assistant.common.RequestIdContext;
import com.lnjecit.knowledge.assistant.infrastructure.ai.ModelFailures;
import java.util.concurrent.CopyOnWriteArrayList;
import org.slf4j.Logger;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/** 统一失败边界传递真实异常；凭据由 Logback 输出转换器统一脱敏。 */
@Component
public final class FailureLog {
    private static final CopyOnWriteArrayList<String> SECRETS = new CopyOnWriteArrayList<>();

    public FailureLog(Environment environment) {
        registerCredentials(environment);
    }

    public static void registerCredentials(Environment environment) {
        for (String key : new String[] {
            "assistant.model.api-key",
            "assistant.rerank.api-key",
            "spring.datasource.password",
            "assistant.minio.access-key",
            "assistant.minio.secret-key",
            "spring.elasticsearch.password"
        }) {
            try {
                String value = environment.getProperty(key);
                if (value != null && !value.isBlank()) {
                    SECRETS.addIfAbsent(value);
                    SECRETS.addIfAbsent(java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8));
                }
            } catch (RuntimeException unresolvedConfiguration) {
                // 脱敏初始化不能改变原有配置校验及启动失败语义。
            }
        }
    }

    public static String redact(String value) {
        if (value == null) return "";
        String result = value;
        for (String secret : SECRETS) result = result.replace(secret, "[REDACTED]");
        result = result.replaceAll("(?i)((?:https?|jdbc:postgresql)://)[^/\\s:@]+:[^/\\s@]+@", "$1[REDACTED]@");
        // 先处理带引号的值，避免密码中的空格、逗号导致只遮盖一部分。
        String credential =
                "(?:api[-_]?key|password|passwd|(?:access|secret)[-_]?key|secret|(?:access[-_]?|refresh[-_]?)?token|authorization|cookie|set-cookie|x-amz-signature|x-amz-credential|x-amz-security-token)";
        result = result.replaceAll(
                "(?i)([\\\"']?" + credential + "[\\\"']?\\s*[:=]\\s*)\\\"(?:\\\\.|[^\\\"\\\\])*\\\"", "$1[REDACTED]");
        result = result.replaceAll("(?i)([\\\"']?" + credential + "[\\\"']?\\s*[:=]\\s*)'(?:[^']*)'", "$1[REDACTED]");
        result = result.replaceAll("(?i)((?:authorization|cookie|set-cookie)\\s*[:=]\\s*)[^\\r\\n]+", "$1[REDACTED]");
        result = result.replaceAll("(?i)((?:bearer|basic)\\s+)[^\\s,;\\\"']+", "$1[REDACTED]");
        result = result.replaceAll("(?i)(" + credential + "[\\\"']?\\s*[:=]\\s*)[^\\s,;&\\\"'}]+", "$1[REDACTED]");
        result = result.replaceAll(
                "(?i)(<(?:password|secretAccessKey|accessKeyId|token)>)[^<]*(</[^>]+>)", "$1[REDACTED]$2");
        return result;
    }

    public static void error(Logger logger, String boundary, Throwable failure) {
        try {
            logger.error(
                    "event=FAILURE requestId={} boundary={} kind={} sqlState={}",
                    RequestIdContext.current(),
                    boundary,
                    ModelFailures.classify(failure),
                    sqlState(failure),
                    failure);
        } catch (RuntimeException loggingFailure) {
            // 日志失败不得改变原异常传播、事务或失败状态更新。
        }
    }

    private static String sqlState(Throwable failure) {
        java.util.Set<Throwable> seen = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        for (Throwable current = failure; current != null && seen.add(current); current = current.getCause()) {
            if (current instanceof java.sql.SQLException sql) return sql.getSQLState();
        }
        return null;
    }
}
