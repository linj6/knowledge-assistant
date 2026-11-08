package com.lnjecit.knowledge.assistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.lnjecit.knowledge.assistant.infrastructure.config.ChunkingProperties;
import com.lnjecit.knowledge.assistant.infrastructure.config.UploadProperties;
import com.lnjecit.knowledge.assistant.infrastructure.persistence.ImportTask;
import com.lnjecit.knowledge.assistant.infrastructure.persistence.ImportTaskStatus;
import com.lnjecit.knowledge.assistant.infrastructure.storage.OriginalFileStore;
import com.lnjecit.knowledge.assistant.ingestion.ChunkingConfig;
import com.lnjecit.knowledge.assistant.ingestion.ImportRepository;
import com.lnjecit.knowledge.assistant.ingestion.ImportService;
import com.lnjecit.knowledge.assistant.ingestion.ImportTaskResponse;
import com.lnjecit.knowledge.assistant.knowledge.KnowledgeBaseService;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.json.JsonMapper;

class ImportTests {
    @Test
    void configProtocol() {
        ChunkingConfig config = new ChunkingConfig(1, "FIXED_CODE_POINT", 800, 120);
        JsonMapper json = JsonMapper.builder().build();
        assertThat(json.readValue(json.writeValueAsString(config), ChunkingConfig.class))
                .isEqualTo(config);
        for (int[] values : new int[][] {{0, 0}, {800, -1}, {800, 800}})
            assertThatThrownBy(() -> new ChunkingConfig(1, "FIXED_CODE_POINT", values[0], values[1]))
                    .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ChunkingConfig(2, "FIXED_CODE_POINT", 800, 120))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ChunkingConfig(1, "OTHER", 800, 120)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void validationAndBom() throws Exception {
        ImportRepository repository = mock(ImportRepository.class);
        KnowledgeBaseService knowledge = mock(KnowledgeBaseService.class);
        OriginalFileStore store = mock(OriginalFileStore.class);
        UploadProperties upload = new UploadProperties();
        upload.setMaxFileBytes(16);
        ImportService service = new ImportService(
                repository,
                knowledge,
                store,
                upload,
                new ChunkingProperties(),
                JsonMapper.builder().build(),
                mock(PlatformTransactionManager.class));
        for (MockMultipartFile file : new MockMultipartFile[] {
            file("a.md", new byte[0]),
            file("a.txt", new byte[17]),
            file("a.pdf", new byte[] {65}),
            file("../a.md", new byte[] {65}),
            file("a.txt", new byte[] {(byte) 0xc3, 40})
        })
            assertThatThrownBy(() -> service.receive(1L, file))
                    .isInstanceOf(com.lnjecit.knowledge.assistant.common.error.BusinessException.class);
        verifyNoInteractions(store);
        ImportTask task = new ImportTask();
        task.setId(2L);
        task.setDocumentId(3L);
        task.setStatus(ImportTaskStatus.PENDING);
        when(repository.findExisting(eq(1L), anyString())).thenReturn(task);
        assertThat(service.receive(1L, file("limit.txt", "a".repeat(16).getBytes(StandardCharsets.UTF_8)))
                        .duplicate())
                .isTrue();
        assertThat(service.receive(1L, file("a.md", "中文".getBytes(StandardCharsets.UTF_8)))
                        .task()
                        .status())
                .isEqualTo("RECEIVED");
        assertThat(service.receive(1L, file("a.txt", new byte[] {(byte) 0xef, (byte) 0xbb, (byte) 0xbf, 65}))
                        .duplicate())
                .isTrue();
        for (ImportTaskStatus status : ImportTaskStatus.values()) {
            task.setStatus(status);
            assertThat(ImportTaskResponse.from(task).status())
                    .isEqualTo(
                            switch (status) {
                                case PENDING -> "RECEIVED";
                                case RUNNING -> "PROCESSING";
                                case SUCCEEDED -> "READY";
                                case FAILED -> "FAILED";
                            });
        }
        assertThatThrownBy(() -> service.get(99L))
                .isInstanceOf(com.lnjecit.knowledge.assistant.common.error.BusinessException.class);
    }

    static MockMultipartFile file(String name, byte[] bytes) {
        return new MockMultipartFile("file", name, "text/plain", bytes);
    }
}
