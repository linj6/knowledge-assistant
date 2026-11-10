package com.lnjecit.knowledge.assistant.retrieval.vector;

import com.lnjecit.knowledge.assistant.retrieval.snapshot.PublishedVersionSnapshot;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface VectorSearchMapper {
    record Row(
            Long chunkId,
            Long documentId,
            Long indexVersionId,
            Long knowledgeBaseId,
            String content,
            int sourceStart,
            int sourceEnd,
            String headingPath,
            double distance) {}

    List<Row> search(
            @Param("snapshot") PublishedVersionSnapshot snapshot,
            @Param("vector") String vector,
            @Param("topK") int topK);
}
