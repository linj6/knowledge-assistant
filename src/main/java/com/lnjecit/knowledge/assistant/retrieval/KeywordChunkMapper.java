package com.lnjecit.knowledge.assistant.retrieval;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface KeywordChunkMapper {
    record Row(
            Long chunkId,
            Long documentId,
            Long indexVersionId,
            Long knowledgeBaseId,
            String content,
            int chunkOrder,
            int sourceStart,
            int sourceEnd,
            String headingPath,
            Long activeVersionId,
            Long documentKnowledgeBaseId,
            Long versionDocumentId) {}

    List<Row> find(@Param("ids") List<Long> ids);
}
