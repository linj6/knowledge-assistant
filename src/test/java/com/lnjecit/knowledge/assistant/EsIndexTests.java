package com.lnjecit.knowledge.assistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.BulkRequest;
import co.elastic.clients.elasticsearch.core.BulkResponse;
import com.lnjecit.knowledge.assistant.document.entity.Document;
import com.lnjecit.knowledge.assistant.document.entity.DocumentChunk;
import com.lnjecit.knowledge.assistant.document.entity.IndexVersion;
import com.lnjecit.knowledge.assistant.document.entity.IndexVersionStatus;
import com.lnjecit.knowledge.assistant.infrastructure.elasticsearch.EsChunkDocument;
import com.lnjecit.knowledge.assistant.infrastructure.elasticsearch.EsIndexNames;
import com.lnjecit.knowledge.assistant.infrastructure.elasticsearch.EsIndexRepository;
import com.lnjecit.knowledge.assistant.ingestion.chunk.ChunkRepository;
import com.lnjecit.knowledge.assistant.ingestion.indexing.EsIndexService;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.List;
import org.junit.jupiter.api.Test;

class EsIndexTests {
    @Test
    void defaultBatchSizeResolvesInSpring() {
        try (org.springframework.context.annotation.AnnotationConfigApplicationContext context =
                new org.springframework.context.annotation.AnnotationConfigApplicationContext()) {
            context.getEnvironment()
                    .getPropertySources()
                    .addFirst(new org.springframework.core.env.MapPropertySource(
                            "test", java.util.Map.of("assistant.elasticsearch.enabled", "true")));
            context.registerBean(ChunkRepository.class, () -> mock(ChunkRepository.class));
            context.registerBean(EsIndexRepository.class, () -> mock(EsIndexRepository.class));
            context.register(EsIndexService.class);
            context.refresh();
            assertThat(context.getBean(EsIndexService.class)).isNotNull();
        }
    }

    @Test
    void endpointValidationIsSafe() {
        for (String endpoint : List.of(
                "ftp://localhost:9200",
                "http://user:secret@localhost:9200",
                "http://localhost:9200/path",
                "http://localhost:9200?secret=x",
                "http://localhost:9200#secret",
                "http://localhost:0",
                "http://localhost:65536",
                "garbage")) {
            assertThatThrownBy(() ->
                            com.lnjecit.knowledge.assistant.infrastructure.elasticsearch.EsConfiguration
                                    .validatedEndpoint(endpoint))
                    .hasMessage("ES endpoint invalid")
                    .hasCauseInstanceOf(IllegalArgumentException.class);
        }
        assertThat(com.lnjecit.knowledge.assistant.infrastructure.elasticsearch.EsConfiguration.validatedEndpoint(
                                "https://localhost:9200")
                        .getSchemeName())
                .isEqualTo("https");
    }

    @Test
    void malformedBulkResponsesAreRejected() throws Exception {
        ElasticsearchClient client = mock(ElasticsearchClient.class);
        EsIndexRepository repository = new EsIndexRepository(client);
        EsChunkDocument doc = new EsChunkDocument("1", "2", "3", "4", 0, "secret");
        BulkResponse response = mock(BulkResponse.class);
        when(client.bulk(any(BulkRequest.class))).thenReturn(null, response);
        assertThatThrownBy(() -> repository.create("test", List.of(doc))).hasMessage("ES_BULK_PROTOCOL_INVALID");
        when(response.items()).thenReturn(null);
        assertThatThrownBy(() -> repository.create("test", List.of(doc))).hasMessage("ES_BULK_PROTOCOL_INVALID");
        when(response.items())
                .thenReturn(
                        java.util.Arrays.asList((co.elastic.clients.elasticsearch.core.bulk.BulkResponseItem) null));
        assertThatThrownBy(() -> repository.create("test", List.of(doc))).hasMessage("ES_BULK_PROTOCOL_INVALID");
        for (co.elastic.clients.elasticsearch.core.bulk.OperationType type : List.of(
                co.elastic.clients.elasticsearch.core.bulk.OperationType.Index,
                co.elastic.clients.elasticsearch.core.bulk.OperationType.Delete)) {
            when(response.items())
                    .thenReturn(List.of(co.elastic.clients.elasticsearch.core.bulk.BulkResponseItem.of(
                            i -> i.operationType(type).index("test").id("1").status(201))));
            assertThatThrownBy(() -> repository.create("test", List.of(doc))).hasMessage("ES_BULK_PROTOCOL_INVALID");
        }
    }

    @Test
    void transportErrorsAreSafeAndUnknown() throws Exception {
        ElasticsearchClient client = mock(ElasticsearchClient.class);
        EsIndexRepository repository = new EsIndexRepository(client);
        EsChunkDocument doc = new EsChunkDocument("1", "2", "3", "4", 0, "secret");
        when(client.bulk(any(BulkRequest.class))).thenThrow(new SocketTimeoutException("secret"));
        assertThatThrownBy(() -> repository.create("test", List.of(doc))).hasMessage("ES_TIMEOUT_OUTCOME_UNKNOWN");
        when(client.bulk(any(BulkRequest.class))).thenThrow(new IOException("secret"));
        assertThatThrownBy(() -> repository.create("test", List.of(doc))).hasMessage("ES_CONNECTION_OUTCOME_UNKNOWN");
    }

    @Test
    void fakePartialFailureAndProtocol() throws Exception {
        ElasticsearchClient client = mock(ElasticsearchClient.class);
        EsIndexRepository repository = new EsIndexRepository(client);
        EsChunkDocument a = new EsChunkDocument("1", "2", "3", "4", 0, "secret"),
                b = new EsChunkDocument("2", "2", "3", "4", 1, "secret");
        when(client.bulk(any(BulkRequest.class))).thenReturn(BulkResponse.of(r -> r.took(1)
                .errors(true)
                .items(i -> i.operationType(co.elastic.clients.elasticsearch.core.bulk.OperationType.Create)
                        .index("test")
                        .id("1")
                        .status(201))
                .items(i -> i.operationType(co.elastic.clients.elasticsearch.core.bulk.OperationType.Create)
                        .index("test")
                        .id("2")
                        .status(400)
                        .error(e -> e.type("fake").reason("secret")))));
        assertThatThrownBy(() -> repository.create("test", List.of(a, b)))
                .hasMessage("ES_BULK_ITEM_REJECTED")
                .satisfies(error -> org.assertj.core.api.Assertions.assertThat(error.getSuppressed())
                        .hasSize(1)
                        .allSatisfy(detail -> org.assertj.core.api.Assertions.assertThat(detail)
                                .hasMessageContaining("httpStatus=400 providerCode=fake reason=secret")));
        assertThatThrownBy(() -> repository.create("test", List.of(a))).hasMessage("ES_BULK_PROTOCOL_INVALID");
    }

    @Test
    void snapshotAndPublicationGuards() {
        ChunkRepository chunks = mock(ChunkRepository.class);
        EsIndexRepository es = mock(EsIndexRepository.class);
        Document document = new Document();
        document.setId(3L);
        document.setKnowledgeBaseId(2L);
        IndexVersion version = new IndexVersion();
        version.setDocumentId(3L);
        version.setStatus(IndexVersionStatus.BUILDING);
        version.setExpectedChunkCount(1);
        DocumentChunk c = new DocumentChunk();
        c.setId(Long.MAX_VALUE);
        c.setKnowledgeBaseId(2L);
        c.setDocumentId(3L);
        c.setIndexVersionId(4L);
        c.setChunkOrder(0);
        c.setContent("中文");
        c.setVersion(7);
        when(chunks.document(3L)).thenReturn(document);
        when(chunks.version(4L)).thenReturn(version);
        when(chunks.find(4L)).thenReturn(List.of(c));
        EsIndexService service = new EsIndexService(chunks, es, 100);
        service.index(3L, 4L);
        verify(es).create(EsIndexNames.CHUNKS, List.of(EsChunkDocument.from(c)));
        assertThat(c.getVersion()).isEqualTo(7);
        assertThat(c.getId()).isEqualTo(Long.MAX_VALUE);
        assertThat(c.getContent()).isEqualTo("中文");
        assertThat(document.getActiveVersionId()).isNull();
        assertThat(version.getStatus()).isEqualTo(IndexVersionStatus.BUILDING);
        // 初始快照后修改正文再恢复，仅版本递增；批前校验必须拒绝。
        when(chunks.find(4L)).thenAnswer(invocation -> {
            c.setContent("临时");
            c.setContent("中文");
            c.setVersion(c.getVersion() + 1);
            return List.of(c);
        });
        assertThatThrownBy(() -> service.index(3L, 4L)).hasMessage("ES chunk snapshot changed");
        when(chunks.find(4L)).thenReturn(List.of(c));
        // 批请求后发生同样变化，最终校验也必须拒绝。
        org.mockito.Mockito.doAnswer(invocation -> {
                    c.setVersion(c.getVersion() + 1);
                    return null;
                })
                .when(es)
                .create(any(String.class), any());
        assertThatThrownBy(() -> service.index(3L, 4L)).hasMessage("ES chunk snapshot changed");
        document.setActiveVersionId(4L);
        assertThatThrownBy(() -> service.index(3L, 4L)).hasMessage("ES version unavailable");
        document.setActiveVersionId(null);
        version.setExpectedChunkCount(2);
        assertThatThrownBy(() -> service.index(3L, 4L)).hasMessage("ES chunk count invalid");
        version.setExpectedChunkCount(1);
        c.setChunkOrder(1);
        assertThatThrownBy(() -> service.index(3L, 4L)).hasMessage("ES chunk identity invalid");
        assertThatThrownBy(() -> new EsIndexService(chunks, es, 101)).isInstanceOf(IllegalArgumentException.class);
    }
}
