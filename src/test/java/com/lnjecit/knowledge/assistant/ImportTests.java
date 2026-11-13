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
import com.lnjecit.knowledge.assistant.ingestion.chunk.ChunkingConfig;
import com.lnjecit.knowledge.assistant.ingestion.dto.ImportTaskResponse;
import com.lnjecit.knowledge.assistant.ingestion.upload.DocumentImportRepository;
import com.lnjecit.knowledge.assistant.ingestion.upload.DocumentImportService;
import com.lnjecit.knowledge.assistant.knowledge.service.KnowledgeBaseService;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.json.JsonMapper;

class ImportTests {
    @Test
    void configProtocol() {
        ChunkingConfig config = new ChunkingConfig(
                1, com.lnjecit.knowledge.assistant.ingestion.chunk.ChunkingStrategy.FIXED_CODE_POINT, 800, 120);
        JsonMapper json = JsonMapper.builder().build();
        assertThat(json.readValue(json.writeValueAsString(config), ChunkingConfig.class))
                .isEqualTo(config);
        for (int[] values : new int[][] {{0, 0}, {800, -1}, {800, 800}})
            assertThatThrownBy(() -> new ChunkingConfig(
                            1,
                            com.lnjecit.knowledge.assistant.ingestion.chunk.ChunkingStrategy.FIXED_CODE_POINT,
                            values[0],
                            values[1]))
                    .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ChunkingConfig(
                        2, com.lnjecit.knowledge.assistant.ingestion.chunk.ChunkingStrategy.FIXED_CODE_POINT, 800, 120))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ChunkingConfig(1, null, 800, 120)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void validationAndBom() throws Exception {
        DocumentImportRepository repository = mock(DocumentImportRepository.class);
        KnowledgeBaseService knowledge = mock(KnowledgeBaseService.class);
        OriginalFileStore store = mock(OriginalFileStore.class);
        UploadProperties upload = new UploadProperties();
        upload.setMaxFileBytes(16);
        DocumentImportService service = new DocumentImportService(
                repository,
                knowledge,
                store,
                upload,
                new ChunkingProperties(),
                JsonMapper.builder().build(),
                new com.lnjecit.knowledge.assistant.infrastructure.config.ModelProperties(),
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

    @Test
    void uploadPropertiesRange() {
        UploadProperties properties = new UploadProperties();
        assertThat(properties.getMaxFileBytes()).isEqualTo(2097152);
        for (int value : new int[] {1, 16, 2097152}) {
            properties.setMaxFileBytes(value);
            properties.afterPropertiesSet();
        }
        for (int value : new int[] {0, -1, 2097153}) {
            properties.setMaxFileBytes(value);
            assertThatThrownBy(properties::afterPropertiesSet).hasMessageContaining("1 and 2097152");
        }
    }

    @Test
    void modelOverridesAreCapturedBeforePersistence() throws Exception {
        DocumentImportRepository repository = mock(DocumentImportRepository.class);
        OriginalFileStore store = mock(OriginalFileStore.class);
        com.lnjecit.knowledge.assistant.infrastructure.config.ModelProperties model =
                new com.lnjecit.knowledge.assistant.infrastructure.config.ModelProperties();
        model.setEmbeddingModel("configured-model");
        model.setDimensions(768);
        ChunkingProperties chunking = new ChunkingProperties();
        chunking.setVersion(1);
        chunking.setStrategy(com.lnjecit.knowledge.assistant.ingestion.chunk.ChunkingStrategy.FIXED_CODE_POINT);
        chunking.setChunkSize(800);
        chunking.setChunkOverlap(120);
        ImportTask task = new ImportTask();
        task.setId(2L);
        task.setDocumentId(3L);
        task.setStatus(ImportTaskStatus.PENDING);
        when(repository.create(
                        org.mockito.ArgumentMatchers.anyLong(),
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString(),
                        org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(task);
        DocumentImportService service = new DocumentImportService(
                repository,
                mock(KnowledgeBaseService.class),
                store,
                new UploadProperties(),
                chunking,
                JsonMapper.builder().build(),
                model,
                mock(PlatformTransactionManager.class));
        assertThat(service.receive(1L, file("a.txt", new byte[] {65})).task().status())
                .isEqualTo("RECEIVED");
        org.mockito.Mockito.verify(repository)
                .create(eq(1L), eq("a.txt"), anyString(), anyString(), anyString(), eq("configured-model"), eq(768));
        model.setEmbeddingModel(" ");
        assertThatThrownBy(() -> service.receive(1L, file("a.txt", new byte[] {66})))
                .hasMessageContaining("Invalid embedding");
        model.setEmbeddingModel("configured-model");
        for (Integer dimensions : new Integer[] {null, 0, -1}) {
            model.setDimensions(dimensions);
            assertThatThrownBy(() -> service.receive(1L, file("a.txt", new byte[] {66})))
                    .hasMessageContaining("Invalid embedding");
        }
    }

    static MockMultipartFile file(String name, byte[] bytes) {
        return new MockMultipartFile("file", name, "text/plain", bytes);
    }
}
