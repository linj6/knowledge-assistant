package com.lnjecit.knowledge.assistant.infrastructure;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import com.lnjecit.knowledge.assistant.infrastructure.elasticsearch.EsChunkDocument;
import com.lnjecit.knowledge.assistant.infrastructure.elasticsearch.EsIndexRepository;
import com.lnjecit.knowledge.assistant.ingestion.verification.EsIntegrityService;
import java.util.List;
import org.junit.jupiter.api.Test;

class EsIntegrityTests {
    @Test
    void invalidSnapshotsNeverRequestOrWrite() {
        ElasticsearchClient client = mock(ElasticsearchClient.class);
        EsIndexRepository repository = mock(EsIndexRepository.class);
        EsIntegrityService service = new EsIntegrityService(client, repository);
        EsChunkDocument row = new EsChunkDocument("9223372036854775806", "1", "2", "3", 0, "知识库");
        for (List<EsChunkDocument> rows : List.of(
                List.<EsChunkDocument>of(),
                List.of(row, row),
                List.of(new EsChunkDocument("1", "9", "2", "3", 0, "知识库")))) {
            assertThatThrownBy(() -> service.verify(1L, 2L, 3L, rows)).hasMessage("ES_VERIFICATION_FAILED");
        }
        verifyNoInteractions(client, repository);
    }

    @Test
    void requestFailureIsSanitized() {
        ElasticsearchClient client = mock(ElasticsearchClient.class);
        when(client.indices()).thenThrow(new IllegalStateException("sensitive response"));
        EsIntegrityService service = new EsIntegrityService(client, mock(EsIndexRepository.class));
        assertThatThrownBy(() -> service.verify(1L, 2L, 3L, List.of(new EsChunkDocument("1", "1", "2", "3", 0, "知识库"))))
                .hasMessage("ES_VERIFICATION_FAILED");
    }
}
