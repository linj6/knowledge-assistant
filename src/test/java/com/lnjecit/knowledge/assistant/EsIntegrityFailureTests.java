package com.lnjecit.knowledge.assistant;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.ScrollResponse;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import co.elastic.clients.elasticsearch.indices.analyze.AnalyzeToken;
import co.elastic.clients.json.JsonData;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import com.lnjecit.knowledge.assistant.ingestion.indexing.EsChunkDocument;
import com.lnjecit.knowledge.assistant.ingestion.indexing.EsChunkFields;
import com.lnjecit.knowledge.assistant.ingestion.indexing.EsIndexRepository;
import com.lnjecit.knowledge.assistant.ingestion.verification.EsIntegrityService;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

class EsIntegrityFailureTests {
    @Test
    @SuppressWarnings("unchecked")
    void refreshPaginationAndSearchFailuresNeverSucceedOrRepair() throws Exception {
        for (String stage :
                List.of("refresh", "page", "search", "clearTimeout", "clearDisconnect", "primaryAndClear")) {
            ElasticsearchClient client = mock(ElasticsearchClient.class, RETURNS_DEEP_STUBS);
            EsIndexRepository repository = mock(EsIndexRepository.class);
            when(client.indices().exists(any(Function.class)).value()).thenReturn(true);
            when(client.clearScroll(any(Function.class)).succeeded()).thenReturn(true);
            SearchResponse<JsonData> first = mock(SearchResponse.class, RETURNS_DEEP_STUBS);
            when(first.scrollId()).thenReturn("cursor");
            Hit<JsonData> hit = mock(Hit.class);
            when(hit.id()).thenReturn("1");
            when(hit.source())
                    .thenReturn(JsonData.of(
                            Map.of(
                                    EsChunkFields.CHUNK_ID,
                                    "1",
                                    EsChunkFields.KNOWLEDGE_BASE_ID,
                                    "1",
                                    EsChunkFields.DOCUMENT_ID,
                                    "2",
                                    EsChunkFields.INDEX_VERSION_ID,
                                    "3",
                                    EsChunkFields.CHUNK_ORDER,
                                    0,
                                    EsChunkFields.CONTENT,
                                    "知识库"),
                            new JacksonJsonpMapper()));
            when(first.hits().hits()).thenReturn(List.of(hit));
            SearchResponse<JsonData> empty = mock(SearchResponse.class, RETURNS_DEEP_STUBS);
            when(empty.hits().hits()).thenReturn(List.of());
            when(client.search(any(Function.class), eq(JsonData.class))).thenReturn(first, empty);
            ScrollResponse<JsonData> last = mock(ScrollResponse.class, RETURNS_DEEP_STUBS);
            when(last.hits().hits()).thenReturn(List.of());
            when(last.scrollId()).thenReturn("cursor");
            when(client.scroll(any(Function.class), eq(JsonData.class))).thenReturn(last);
            AnalyzeToken token = mock(AnalyzeToken.class);
            when(token.token()).thenReturn("知识库");
            when(client.indices().analyze(any(Function.class)).tokens()).thenReturn(List.of(token));
            if (stage.equals("refresh"))
                when(client.indices().refresh(any(Function.class))).thenThrow(new java.io.IOException("sensitive"));
            if (stage.equals("page"))
                when(client.scroll(any(Function.class), eq(JsonData.class)))
                        .thenThrow(new java.io.IOException("sensitive"));
            if (stage.startsWith("clear")) {
                when(client.search(any(Function.class), eq(JsonData.class))).thenReturn(first);
                when(client.clearScroll(any(Function.class)))
                        .thenThrow(
                                stage.equals("clearTimeout")
                                        ? new java.net.SocketTimeoutException("sensitive")
                                        : new java.net.SocketException("sensitive"));
            }
            if (stage.equals("primaryAndClear")) {
                when(client.scroll(any(Function.class), eq(JsonData.class)))
                        .thenThrow(new IllegalStateException("sensitive"));
                when(client.clearScroll(any(Function.class)))
                        .thenThrow(new java.net.SocketTimeoutException("sensitive"));
            }
            EsIntegrityService service = new EsIntegrityService(client, repository);
            assertThatThrownBy(() ->
                            service.verify(1L, 2L, 3L, List.of(new EsChunkDocument("1", "1", "2", "3", 0, "知识库"))))
                    .hasMessage(stage.startsWith("clear") ? "ES_CONNECTION_OUTCOME_UNKNOWN" : "ES_VERIFICATION_FAILED")
                    .hasNoCause();
            if (!stage.equals("refresh")) verify(client).clearScroll(any(Function.class));
            if (stage.equals("page")) verify(client).scroll(any(Function.class), eq(JsonData.class));
            if (stage.equals("search")) verify(client, times(2)).search(any(Function.class), eq(JsonData.class));
            verify(repository, never()).create(any(), any());
        }
    }
}
