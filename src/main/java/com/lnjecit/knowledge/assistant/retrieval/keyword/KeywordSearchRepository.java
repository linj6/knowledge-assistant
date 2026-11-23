package com.lnjecit.knowledge.assistant.retrieval.keyword;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import co.elastic.clients.elasticsearch.indices.IndexSettings;
import co.elastic.clients.elasticsearch.indices.IndexState;
import co.elastic.clients.json.JsonData;
import com.lnjecit.knowledge.assistant.infrastructure.elasticsearch.EsChunkDocument;
import com.lnjecit.knowledge.assistant.infrastructure.elasticsearch.EsIndexNames;
import com.lnjecit.knowledge.assistant.infrastructure.elasticsearch.EsIndexRepository;
import com.lnjecit.knowledge.assistant.retrieval.snapshot.PublishedDocument;
import com.lnjecit.knowledge.assistant.retrieval.snapshot.PublishedVersionSnapshot;
import com.lnjecit.knowledge.assistant.retrieval.snapshot.PublishedVersionTerms;
import com.lnjecit.knowledge.assistant.retrieval.snapshot.TermsLimitExceededException;
import jakarta.json.JsonNumber;
import jakarta.json.JsonObject;
import jakarta.json.JsonString;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

@Repository
@ConditionalOnProperty(name = "assistant.elasticsearch.enabled", havingValue = "true")
public class KeywordSearchRepository {
    private final ElasticsearchClient client;
    private final String index;

    @Autowired
    public KeywordSearchRepository(ElasticsearchClient client) {
        this(client, EsIndexNames.CHUNKS);
    }

    public KeywordSearchRepository(ElasticsearchClient client, String index) {
        this.client = client;
        this.index = index;
    }

    public record Candidate(EsChunkDocument source, double score) {}

    public List<Candidate> search(PublishedVersionSnapshot snapshot, String query, int topK) {
        if (topK < 1 || topK > 100 || query == null || query.isBlank())
            throw new IllegalArgumentException("BM25_INPUT_INVALID");
        List<Long> versions = snapshot.versionIdsForTerms();
        if (versions.isEmpty()) return List.of();
        try {
            // 只读验证实际目标索引，禁止创建索引或修改设置迁就查询。
            new EsIndexRepository(client).validateIndex(index);
            if (Boolean.FALSE.equals(client.indices()
                    .getMapping(r -> r.index(index))
                    .result()
                    .get(index)
                    .mappings()
                    .properties()
                    .get("chunk_id")
                    .keyword()
                    .docValues())) throw new IllegalStateException();
            IndexState settings = client.indices()
                    .getSettings(r -> r.index(index).includeDefaults(true))
                    .result()
                    .get(index);
            Integer limit = termsLimit(settings.settings());
            if (limit == null && settings.defaults() != null) limit = termsLimit(settings.defaults());
            if (limit == null || limit <= 0) throw new IllegalStateException();
            if (versions.size() > Math.min(PublishedVersionTerms.MAX_UNIQUE_VERSIONS, limit))
                throw new TermsLimitExceededException();
            List<FieldValue> terms =
                    versions.stream().map(v -> FieldValue.of(v.toString())).toList();
            SearchResponse<JsonData> response = client.search(
                    r -> r.index(index)
                            .size(topK)
                            .allowPartialSearchResults(false)
                            .query(q -> q.bool(b -> b.must(
                                            m -> m.match(t -> t.field("content").query(query)))
                                    .filter(f -> f.term(t -> t.field("knowledge_base_id")
                                            .value(snapshot.knowledgeBaseId().toString())))
                                    .filter(f -> f.terms(
                                            t -> t.field("index_version_id").terms(v -> v.value(terms))))))
                            .sort(s -> s.score(v -> v.order(SortOrder.Desc)))
                            .sort(s -> s.field(v -> v.field("chunk_id").order(SortOrder.Asc))),
                    JsonData.class);
            if (response.timedOut()
                    || response.shards().failed().intValue() != 0
                    || response.hits().hits().size() > topK) throw new IllegalStateException();
            List<Candidate> result = new ArrayList<>();
            HashSet<Long> ids = new HashSet<>();
            for (Hit<JsonData> hit : response.hits().hits()) {
                if (hit.source() == null || hit.score() == null || !Double.isFinite(hit.score()) || hit.score() < 0)
                    throw new IllegalStateException();
                JsonObject raw = hit.source().toJson().asJsonObject();
                for (String field :
                        List.of("chunk_id", "knowledge_base_id", "document_id", "index_version_id", "content"))
                    if (!(raw.get(field) instanceof JsonString)) throw new IllegalStateException();
                if (!(raw.get("chunk_order") instanceof JsonNumber order) || !order.isIntegral())
                    throw new IllegalStateException();
                EsChunkDocument source = hit.source().to(EsChunkDocument.class);
                Long id = id(source.chunk_id()),
                        kb = id(source.knowledge_base_id()),
                        doc = id(source.document_id()),
                        version = id(source.index_version_id());
                if (!source.chunk_id().equals(hit.id())
                        || !ids.add(id)
                        || !kb.equals(snapshot.knowledgeBaseId())
                        || !snapshot.documents().contains(new PublishedDocument(doc, version))
                        || source.chunk_order() < 0
                        || source.content().isBlank()) throw new IllegalStateException();
                result.add(new Candidate(source, hit.score()));
            }
            return List.copyOf(result);
        } catch (TermsLimitExceededException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new IllegalStateException("BM25_FAILED", failure);
        }
    }

    // ES 设置响应通常将 index 包装为一层对象；缺失安全值时拒绝检索。
    private static Integer termsLimit(IndexSettings settings) {
        if (settings == null) return null;
        Integer nested = settings.index() == null ? null : settings.index().maxTermsCount();
        return nested != null ? nested : settings.maxTermsCount();
    }

    private static Long id(String value) {
        if (value == null || !value.matches("[1-9][0-9]*")) throw new IllegalStateException();
        return Long.valueOf(value);
    }
}
