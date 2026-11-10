package com.lnjecit.knowledge.assistant.retrieval;

import java.util.List;
import org.springframework.stereotype.Repository;

@Repository
public class VectorSearchRepository {
    private final VectorSearchMapper mapper;

    public VectorSearchRepository(VectorSearchMapper mapper) {
        this.mapper = mapper;
    }

    public List<VectorSearchMapper.Row> search(PublishedVersionSnapshot snapshot, QueryVector vector, int topK) {
        if (topK < 1 || topK > 100) throw new IllegalArgumentException("VECTOR_TOP_K_INVALID");
        // 空快照不发 SQL，避免空 IN 或意外扩大可见范围。
        if (snapshot.documents().isEmpty()) return List.of();
        return List.copyOf(mapper.search(snapshot, vector.binding(), topK));
    }
}
