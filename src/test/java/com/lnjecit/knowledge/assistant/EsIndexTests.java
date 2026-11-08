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
import com.lnjecit.knowledge.assistant.infrastructure.persistence.Document;
import com.lnjecit.knowledge.assistant.infrastructure.persistence.DocumentChunk;
import com.lnjecit.knowledge.assistant.infrastructure.persistence.IndexVersion;
import com.lnjecit.knowledge.assistant.infrastructure.persistence.IndexVersionStatus;
import com.lnjecit.knowledge.assistant.ingestion.chunk.ChunkRepository;
import com.lnjecit.knowledge.assistant.ingestion.indexing.EsChunkDocument;
import com.lnjecit.knowledge.assistant.ingestion.indexing.EsIndexRepository;
import com.lnjecit.knowledge.assistant.ingestion.indexing.EsIndexService;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.List;
import org.junit.jupiter.api.Test;

class EsIndexTests {
    @Test
    void transportErrorsAreSafeAndUnknown() throws Exception {
        ElasticsearchClient client = mock(ElasticsearchClient.class);
        EsIndexRepository repository = new EsIndexRepository(client);
        EsChunkDocument doc = new EsChunkDocument("1", "2", "3", "4", 0, "secret");
        when(client.bulk(any(BulkRequest.class))).thenThrow(new SocketTimeoutException("secret"));
        assertThatThrownBy(() -> repository.create("test", List.of(doc)))
                .hasMessage("ES_TIMEOUT_OUTCOME_UNKNOWN")
                .hasNoCause();
        when(client.bulk(any(BulkRequest.class))).thenThrow(new IOException("secret"));
        assertThatThrownBy(() -> repository.create("test", List.of(doc)))
                .hasMessage("ES_CONNECTION_OUTCOME_UNKNOWN")
                .hasNoCause();
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
                .hasNoCause();
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
        verify(es).create(EsIndexService.INDEX, List.of(EsChunkDocument.from(c)));
        assertThat(c.getVersion()).isEqualTo(7);
        assertThat(c.getId()).isEqualTo(Long.MAX_VALUE);
        assertThat(c.getContent()).isEqualTo("中文");
        assertThat(document.getActiveVersionId()).isNull();
        assertThat(version.getStatus()).isEqualTo(IndexVersionStatus.BUILDING);
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
