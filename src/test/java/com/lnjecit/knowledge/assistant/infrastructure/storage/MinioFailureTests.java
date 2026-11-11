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
