package com.lnjecit.knowledge.assistant.retrieval.snapshot;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface PublishedVersionSnapshotMapper {
    record Row(Long knowledgeBaseId, Long documentId, Long activeVersionId, Long versionId, Long versionDocumentId) {}

    List<Row> read(@Param("kbId") Long kbId);
}
