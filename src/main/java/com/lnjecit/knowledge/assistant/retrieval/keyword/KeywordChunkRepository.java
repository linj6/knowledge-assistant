package com.lnjecit.knowledge.assistant.retrieval.keyword;

import com.lnjecit.knowledge.assistant.retrieval.model.RankedChunk;
import com.lnjecit.knowledge.assistant.retrieval.snapshot.PublishedDocument;
import com.lnjecit.knowledge.assistant.retrieval.snapshot.PublishedVersionSnapshot;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Repository;

@Repository
public class KeywordChunkRepository {
    private final KeywordChunkMapper mapper;

    public KeywordChunkRepository(KeywordChunkMapper mapper) {
        this.mapper = mapper;
    }

    public List<RankedChunk> complete(
            PublishedVersionSnapshot snapshot, List<KeywordSearchRepository.Candidate> candidates) {
        if (candidates.isEmpty()) return List.of();
        List<Long> ids = candidates.stream()
                .map(c -> Long.valueOf(c.source().chunk_id()))
                .toList();
        Map<Long, KeywordChunkMapper.Row> rows = new HashMap<>();
        for (KeywordChunkMapper.Row row : mapper.find(ids))
            if (rows.put(row.chunkId(), row) != null) throw new IllegalStateException("BM25_FAILED");
        if (rows.size() != ids.size()) throw new IllegalStateException("BM25_FAILED");
        List<RankedChunk> result = new ArrayList<>();
        // 按 ES 原排名补全，PG 返回顺序不影响相关性和并列排序。
        for (KeywordSearchRepository.Candidate candidate : candidates) {
            com.lnjecit.knowledge.assistant.ingestion.indexing.EsChunkDocument source = candidate.source();
            KeywordChunkMapper.Row row = rows.get(Long.valueOf(source.chunk_id()));
            if (row == null
                    || !Objects.equals(row.knowledgeBaseId(), snapshot.knowledgeBaseId())
                    || !Objects.equals(row.documentKnowledgeBaseId(), row.knowledgeBaseId())
                    || !Objects.equals(row.versionDocumentId(), row.documentId())
                    || !Objects.equals(row.activeVersionId(), row.indexVersionId())
                    || !snapshot.documents().contains(new PublishedDocument(row.documentId(), row.indexVersionId()))
                    || !Objects.equals(
                            source.knowledge_base_id(), row.knowledgeBaseId().toString())
                    || !Objects.equals(source.document_id(), row.documentId().toString())
                    || !Objects.equals(
                            source.index_version_id(), row.indexVersionId().toString())
                    || !Objects.equals(source.content(), row.content())
                    || source.chunk_order() != row.chunkOrder()) throw new IllegalStateException("BM25_FAILED");
            result.add(new RankedChunk(
                    row.chunkId(),
                    row.documentId(),
                    row.indexVersionId(),
                    row.knowledgeBaseId(),
                    row.content(),
                    row.sourceStart(),
                    row.sourceEnd(),
                    row.headingPath(),
                    null,
                    null,
                    result.size() + 1,
                    candidate.score(),
                    null,
                    null,
                    null));
        }
        return List.copyOf(result);
    }
}
