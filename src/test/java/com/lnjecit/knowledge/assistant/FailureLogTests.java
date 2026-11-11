package com.lnjecit.knowledge.assistant;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.read.ListAppender;
import com.lnjecit.knowledge.assistant.infrastructure.ai.ModelFailures;
import com.lnjecit.knowledge.assistant.infrastructure.logging.FailureLog;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mock.env.MockEnvironment;

class FailureLogTests {
    @Test
    void preservesCauseAndRendersSanitizedThrowableIncludingSuppressed() {
        new FailureLog(new MockEnvironment()
                .withProperty("assistant.model.api-key", "fixture-key-value")
                .withProperty("spring.datasource.password", "fixture-password-value"));
        IllegalStateException cause = new IllegalStateException(
                "provider rejected fixture-key-value password=fixture-password-value Bearer fixture-token");
        cause.addSuppressed(new IllegalStateException("Cookie: session=fixture-cookie"));
        Throwable safe = ModelFailures.safe("EMBEDDING_SINGLE", cause);
        assertThat(safe.getCause()).isSameAs(cause);
        String output = capture(safe);
        assertThat(output)
                .contains("IllegalStateException", "provider rejected", "Caused by", "Suppressed", "FailureLogTests")
                .doesNotContain("fixture-key-value", "fixture-password-value", "fixture-token", "fixture-cookie");
    }

    @Test
    void recordsSqlStateNetworkTimeoutAndUnknownFailure() {
        assertThat(capture(new SQLException("constraint violated password=fixture-pass", "23505", 7)))
                .contains("23505", "vendorCode=7", "constraint violated")
                .doesNotContain("fixture-pass");
        assertThat(capture(new ConnectException("Connection refused"))).contains("CONNECTION", "Connection refused");
        assertThat(capture(new SocketTimeoutException("read timed out"))).contains("TIMEOUT", "read timed out");
        assertThat(capture(new IllegalStateException("unexpected internal failure")))
                .contains("OTHER", "unexpected internal failure");
    }

    @Test
    void keepsAllModelKindsWithoutDuplicatingStack() {
        for (ModelFailures.Kind kind : ModelFailures.Kind.values()) {
            String output = capture(ModelFailures.rerankFailure(kind, new IllegalStateException("diagnostic detail")));
            assertThat(output).contains("kind=" + kind, "diagnostic detail");
        }
    }

    @Test
    void retainsSdkAndJdbcMessagesAndRedactsQuotedCredentials() {
        String detail =
                "connection reset password=\"fixture password, with spaces\" token='fixture token' access_key=fixture-access";
        assertThat(capture(new com.openai.errors.OpenAIIoException(detail)))
                .contains("OpenAIIoException", "connection reset")
                .doesNotContain("fixture password", "with spaces", "fixture token", "fixture-access");
        assertThat(capture(new org.springframework.jdbc.BadSqlGrammarException(
                        "query failed", "select missing_column", new SQLException("column absent", "42703", 9))))
                .contains("BadSqlGrammarException", "query failed", "column absent", "42703", "vendorCode=9");
    }

    @Test
    void extractsRealSdkStatusCodeAndMessageWithoutHeadersOrOtherBodyFields() {
        com.openai.errors.UnauthorizedException failure = com.openai.errors.UnauthorizedException.builder()
                .headers(com.openai.core.http.Headers.builder().build())
                .error(com.openai.core.JsonValue.from(java.util.Map.of(
                        "message",
                        "invalid credential token=fixture-token",
                        "code",
                        "invalid_api_key",
                        "type",
                        "authentication_error",
                        "privatePayload",
                        "fixture-private-body")))
                .build();
        String output = capture(ModelFailures.safe("EMBEDDING_SINGLE", failure));
        assertThat(output)
                .contains(
                        "AUTHENTICATION",
                        "httpStatus=401",
                        "invalid_api_key",
                        "invalid credential",
                        "UnauthorizedException")
                .doesNotContain("fixture-token", "fixture-private-body");
    }

    @Test
    void handlesCyclesAndDeepChainsWithoutChangingOriginalThrowable() {
        RuntimeException first = new RuntimeException("first");
        RuntimeException second = new RuntimeException("second", first);
        first.initCause(second);
        assertThat(capture(first)).contains("first", "second");
        assertThat(first.getCause()).isSameAs(second);
    }

    @Test
    void loggingBackendFailureDoesNotReplaceBusinessFailure() {
        org.slf4j.Logger logger = org.mockito.Mockito.mock(org.slf4j.Logger.class);
        org.mockito.Mockito.doThrow(new IllegalStateException("backend unavailable"))
                .when(logger)
                .error(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any(Object[].class));
        org.assertj.core.api.Assertions.assertThatCode(
                        () -> FailureLog.error(logger, "TEST", new IllegalStateException("primary")))
                .doesNotThrowAnyException();
    }

    @Test
    void redactsRegisteredSecretsHeadersAndUrlCredentials() {
        new FailureLog(new MockEnvironment().withProperty("assistant.minio.secret-key", "fixture-minio-secret"));
        String output = capture(
                new IllegalStateException("storage fixture-minio-secret https://user:fixture-url-pass@host/path\n"
                        + "Authorization: Basic fixture-header\n{\"token\":\"fixture json token\"}\nreason=denied"));
        assertThat(output)
                .contains("storage", "reason=denied")
                .doesNotContain("fixture-minio-secret", "fixture-url-pass", "fixture-header", "fixture json token");
    }

    private String capture(Throwable failure) {
        Logger logger = (Logger) LoggerFactory.getLogger("failure-log-test");
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            FailureLog.error(logger, "TEST", failure);
            assertThat(appender.list).hasSize(1);
            ILoggingEvent event = appender.list.getFirst();
            assertThat(event.getLevel()).isEqualTo(ch.qos.logback.classic.Level.ERROR);
            return event.getFormattedMessage() + ThrowableProxyUtil.asString(event.getThrowableProxy());
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }
}
