package com.lnjecit.knowledge.assistant.infrastructure.storage;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.lnjecit.knowledge.assistant.ingestion.task.TaskExecutionException;
import io.minio.MinioClient;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class MinioFailureTests {
    @Test
    void uploadKeepsCauseAndFinalApiBoundaryLogsItOnce() throws Exception {
        MinioClient client = mock(MinioClient.class);
        MinioOriginalFileStore store = new MinioOriginalFileStore("http://localhost:9000", "", "", "test");
        ReflectionTestUtils.setField(store, "client", client);
        java.io.IOException cause =
                new java.io.IOException("synthetic upload failure password=fixture-upload-password");
        when(client.putObject(any(io.minio.PutObjectArgs.class))).thenThrow(cause);
        Throwable failure =
                org.assertj.core.api.Assertions.catchThrowable(() -> store.put("staging/test", new byte[] {1}));
        org.assertj.core.api.Assertions.assertThat(failure)
                .hasMessage("Object upload failed")
                .hasCause(cause);
        ch.qos.logback.classic.Logger logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(
                com.lnjecit.knowledge.assistant.infrastructure.web.ApiExceptionHandler.class);
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> events =
                new ch.qos.logback.core.read.ListAppender<>();
        events.start();
        logger.addAppender(events);
        try {
            new com.lnjecit.knowledge.assistant.infrastructure.web.ApiExceptionHandler()
                    .unexpected((Exception) failure);
            org.assertj.core.api.Assertions.assertThat(events.list).hasSize(1);
            String output = com.lnjecit.knowledge.assistant.infrastructure.logging.FailureLog.redact(
                    ch.qos.logback.classic.spi.ThrowableProxyUtil.asString(
                            events.list.getFirst().getThrowableProxy()));
            org.assertj.core.api.Assertions.assertThat(output)
                    .contains("Object upload failed", "synthetic upload failure", "IOException")
                    .doesNotContain("fixture-upload-password");
        } finally {
            logger.detachAppender(events);
            events.stop();
        }
    }

    @Test
    void preservesTimeoutConnectionAndOtherReadCauses() throws Exception {
        MinioClient client = mock(MinioClient.class);
        MinioOriginalFileStore store = new MinioOriginalFileStore("http://localhost:9000", "", "", "test");
        ReflectionTestUtils.setField(store, "client", client);
        for (Exception cause : new Exception[] {
            new SocketTimeoutException("read timed out"), new ConnectException("connection refused")
        }) {
            when(client.getObject(any(io.minio.GetObjectArgs.class))).thenThrow(cause);
            assertThatThrownBy(() -> store.read("test"))
                    .isInstanceOf(TaskExecutionException.class)
                    .hasCause(cause)
                    .satisfies(
                            error -> org.assertj.core.api.Assertions.assertThat(((TaskExecutionException) error).code())
                                    .isEqualTo(TaskExecutionException.Code.INFRASTRUCTURE_TEMPORARY));
        }
        IllegalStateException cause = new IllegalStateException("storage detail");
        when(client.getObject(any(io.minio.GetObjectArgs.class))).thenThrow(cause);
        assertThatThrownBy(() -> store.read("test"))
                .hasMessage("Object read failed")
                .hasCause(cause);
    }
}
