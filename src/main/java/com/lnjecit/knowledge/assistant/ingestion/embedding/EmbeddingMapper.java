package com.lnjecit.knowledge.assistant.ingestion.embedding;

import com.lnjecit.knowledge.assistant.document.entity.Document;
import com.lnjecit.knowledge.assistant.document.entity.DocumentChunk;
import com.lnjecit.knowledge.assistant.document.entity.IndexVersion;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface EmbeddingMapper {
    Document lockDocument(@Param("id") Long id);

    IndexVersion lockVersion(@Param("id") Long id);

    int write(
            @Param("c") DocumentChunk chunk,
            @Param("vector") String vector,
            @Param("expectedVersion") int expectedVersion);
}
