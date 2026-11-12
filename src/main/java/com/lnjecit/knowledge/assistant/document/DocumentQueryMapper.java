package com.lnjecit.knowledge.assistant.document;

import com.lnjecit.knowledge.assistant.infrastructure.persistence.Document;
import com.lnjecit.knowledge.assistant.infrastructure.persistence.ImportTask;
import com.lnjecit.knowledge.assistant.infrastructure.persistence.IndexVersion;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface DocumentQueryMapper {
    com.lnjecit.knowledge.assistant.infrastructure.persistence.Document document(@Param("id") Long id);

    com.lnjecit.knowledge.assistant.infrastructure.persistence.DocumentChunk chunk(@Param("id") Long id);

    long count(@Param("kbId") Long kbId);

    List<Document> page(@Param("kbId") Long kbId, @Param("offset") long offset, @Param("size") int size);

    List<IndexVersion> versions(@Param("ids") List<Long> ids);

    List<ImportTask> tasks(@Param("ids") List<Long> ids);
}
