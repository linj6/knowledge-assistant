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
    List<DocumentChunk> chunks(Long kbId, Long id, Long versionId, int size, long offset);

    Document document(@Param("id") Long id);

    DocumentChunk chunk(@Param("id") Long id);

    long count(@Param("kbId") Long kbId, @Param("pattern") String pattern, @Param("status") String status);

    Long lockActive(@Param("kbId") Long kbId, @Param("id") Long id);

    boolean hasActiveImport(@Param("id") Long id);

    int markDeleted(@Param("kbId") Long kbId, @Param("id") Long id);

    List<Document> page(
            @Param("kbId") Long kbId,
            @Param("offset") long offset,
            @Param("size") int size,
            @Param("pattern") String pattern,
            @Param("status") String status);

    List<IndexVersion> versions(@Param("ids") List<Long> ids);

    List<ImportTask> tasks(@Param("ids") List<Long> ids);
}
