package com.lnjecit.knowledge.assistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.mapping.Property;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import co.elastic.clients.elasticsearch.indices.IndexState;
import co.elastic.clients.json.JsonData;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import com.lnjecit.knowledge.assistant.retrieval.keyword.KeywordSearchRepository;
import com.lnjecit.knowledge.assistant.retrieval.snapshot.PublishedDocument;
import com.lnjecit.knowledge.assistant.retrieval.snapshot.PublishedVersionSnapshot;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

class KeywordProtocolTests {
    @Test
    @SuppressWarnings("unchecked")
    void malformedResponsesAndDependencyFailuresAreRejected() throws Exception {
        for (String fault : List.of(
                "good",
                "timeout",
                "shard",
                "source",
                "id",
                "score",
                "duplicate",
                "network",
                "limit",
                "outer",
                "defaults",
                "missing")) {
            ElasticsearchClient client = mock(ElasticsearchClient.class, RETURNS_DEEP_STUBS);
            when(client.indices().exists(any(Function.class)).value()).thenReturn(true);
            Map<String, Property> fields = Map.of(
                    "chunk_id",
                    Property.of(p -> p.keyword(k -> k)),
                    "knowledge_base_id",
                    Property.of(p -> p.keyword(k -> k)),
                    "document_id",
                    Property.of(p -> p.keyword(k -> k)),
                    "index_version_id",
                    Property.of(p -> p.keyword(k -> k)),
                    "chunk_order",
                    Property.of(p -> p.integer(i -> i)),
                    "content",
                    Property.of(p -> p.text(t ->
                            t.analyzer("ik_max_word").searchAnalyzer("ik_smart").similarity("BM25"))));
            when(client.indices()
                            .getMapping(any(Function.class))
                            .result()
                            .get("test")
                            .mappings()
                            .properties())
                    .thenReturn(fields);
            when(client.indices().getSettings(any(Function.class)).result())
                    .thenReturn(Map.of("test", IndexState.of(s -> s.settings(i -> i.index(n -> n.maxTermsCount(2))))));
            if (fault.equals("outer"))
                when(client.indices().getSettings(any(Function.class)).result())
                        .thenReturn(Map.of(
                                "test",
                                IndexState.of(
                                        s -> s.settings(i -> i.index(n -> n).maxTermsCount(2)))));
            if (fault.equals("defaults"))
                when(client.indices().getSettings(any(Function.class)).result())
                        .thenReturn(Map.of("test", IndexState.of(s -> s.settings(i -> i.index(n -> n))
                                .defaults(i -> i.maxTermsCount(2)))));
            if (fault.equals("missing"))
                when(client.indices().getSettings(any(Function.class)).result())
                        .thenReturn(Map.of("test", IndexState.of(s -> s.settings(i -> i.index(n -> n)))));
            SearchResponse<JsonData> response = mock(SearchResponse.class, RETURNS_DEEP_STUBS);
            when(response.shards().failed()).thenReturn(fault.equals("shard") ? 1 : 0);
            when(response.timedOut()).thenReturn(fault.equals("timeout"));
            Hit<JsonData> hit = mock(Hit.class);
            when(hit.id()).thenReturn(fault.equals("id") ? "99" : "10");
            when(hit.score()).thenReturn(fault.equals("score") ? Double.NaN : 1.5);
            when(hit.source())
                    .thenReturn(
                            fault.equals("source")
                                    ? null
                                    : JsonData.of(
                                            Map.of(
                                                    "chunk_id",
                                                    "10",
                                                    "knowledge_base_id",
                                                    "1",
                                                    "document_id",
                                                    "2",
                                                    "index_version_id",
                                                    "3",
                                                    "chunk_order",
                                                    0,
                                                    "content",
                                                    "body"),
                                            new JacksonJsonpMapper()));
            when(response.hits().hits()).thenReturn(fault.equals("duplicate") ? List.of(hit, hit) : List.of(hit));
            when(client.search(any(Function.class), eq(JsonData.class))).thenReturn(response);
            if (fault.equals("network"))
                when(client.search(any(Function.class), eq(JsonData.class)))
                        .thenThrow(new java.io.IOException("private"));
            KeywordSearchRepository repository = new KeywordSearchRepository(client, "test");
            if (fault.equals("limit")) {
                PublishedVersionSnapshot large = PublishedVersionSnapshot.of(
                        1L,
                        List.of(
                                new PublishedDocument(2L, 3L),
                                new PublishedDocument(4L, 5L),
                                new PublishedDocument(6L, 7L)));
                assertThat(new com.lnjecit.knowledge.assistant.retrieval.keyword.KeywordRetriever(
                                        repository,
                                        mock(
                                                com.lnjecit.knowledge.assistant.retrieval.keyword.KeywordChunkRepository
                                                        .class))
                                .retrieve(new com.lnjecit.knowledge.assistant.retrieval.model.RetrievalContext(
                                        "r",
                                        "query",
                                        com.lnjecit.knowledge.assistant.retrieval.model.RetrievalStrategy.BM25,
                                        com.lnjecit.knowledge.assistant.retrieval.model.RetrievalParameters.defaults(),
                                        large))
                                .failure())
                        .isEqualTo(
                                com.lnjecit.knowledge.assistant.retrieval.model.RetrievalDiagnostics.Failure
                                        .TERMS_LIMIT_EXCEEDED);
                continue;
            }
            if (List.of("good", "outer", "defaults").contains(fault)) {
                assertThat(repository.search(
                                PublishedVersionSnapshot.of(1L, List.of(new PublishedDocument(2L, 3L))), "query", 20))
                        .hasSize(1);
                continue;
            }
            assertThatThrownBy(() -> repository.search(
                            PublishedVersionSnapshot.of(1L, List.of(new PublishedDocument(2L, 3L))), "query", 20))
                    .hasMessage("BM25_FAILED");
        }
    }
}
