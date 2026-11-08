package com.lnjecit.knowledge.assistant.ingestion.embedding;

import com.lnjecit.knowledge.assistant.infrastructure.persistence.DocumentChunk;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface EmbeddingMapper {
    int write(
            @Param("c") DocumentChunk chunk,
            @Param("vector") String vector,
            @Param("expectedVersion") int expectedVersion,
            @Param("model") String model,
            @Param("dimension") int dimension);
}
