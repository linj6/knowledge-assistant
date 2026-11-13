package com.lnjecit.knowledge.assistant.document.mapper;

import com.lnjecit.knowledge.assistant.document.entity.Document;
import com.lnjecit.knowledge.assistant.document.entity.DocumentChunk;
import com.lnjecit.knowledge.assistant.document.entity.IndexVersion;
import com.lnjecit.knowledge.assistant.ingestion.task.entity.ImportTask;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface DocumentQueryMapper {
    Document document(@Param("id") Long id);

    DocumentChunk chunk(@Param("id") Long id);

    long count(@Param("kbId") Long kbId);

    List<Document> page(@Param("kbId") Long kbId, @Param("offset") long offset, @Param("size") int size);

    List<IndexVersion> versions(@Param("ids") List<Long> ids);

    List<ImportTask> tasks(@Param("ids") List<Long> ids);
}
