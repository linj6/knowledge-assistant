package com.lnjecit.knowledge.assistant.document.service;

import com.lnjecit.knowledge.assistant.common.error.BusinessException;
import com.lnjecit.knowledge.assistant.common.error.CommonErrorCode;
import com.lnjecit.knowledge.assistant.document.dto.CitationEvidenceResponse;
import com.lnjecit.knowledge.assistant.document.dto.DocumentListResponse;
import com.lnjecit.knowledge.assistant.document.dto.DocumentSummaryResponse;
import com.lnjecit.knowledge.assistant.document.entity.Document;
import com.lnjecit.knowledge.assistant.document.entity.DocumentChunk;
import com.lnjecit.knowledge.assistant.document.entity.IndexVersion;
import com.lnjecit.knowledge.assistant.document.entity.IndexVersionStatus;
import com.lnjecit.knowledge.assistant.document.repository.DocumentQueryRepository;
import com.lnjecit.knowledge.assistant.ingestion.dto.ImportTaskResponse;
import com.lnjecit.knowledge.assistant.ingestion.task.entity.ImportTask;
import com.lnjecit.knowledge.assistant.knowledge.service.KnowledgeBaseService;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DocumentQueryService {
    private static final Logger LOG = LoggerFactory.getLogger(DocumentQueryService.class);
    private final DocumentQueryRepository repository;
    private final KnowledgeBaseService knowledgeBases;

    public DocumentQueryService(DocumentQueryRepository repository, KnowledgeBaseService knowledgeBases) {
        this.repository = repository;
        this.knowledgeBases = knowledgeBases;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public DocumentListResponse list(Long kbId, int page, int pageSize) {
        return list(kbId, page, pageSize, null, null);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public DocumentListResponse list(Long kbId, int page, int pageSize, String keyword, String status) {
        if (kbId == null || kbId <= 0 || page < 1 || pageSize < 1 || pageSize > 100)
            throw new BusinessException(CommonErrorCode.INVALID_ARGUMENT);
        knowledgeBases.get(kbId);
        // 同一只读快照内完成计数、分页和批量关联，避免 N+1 及关联身份漂移。
        if (status != null
                && !List.of("UNPUBLISHED", "IMPORTING", "PUBLISHED", "FAILED").contains(status))
            throw new BusinessException(CommonErrorCode.INVALID_ARGUMENT);
        String pattern = keyword == null || keyword.isBlank()
                ? null
                : "%" + keyword.strip().replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
        long total = repository.count(kbId, pattern, status);
        List<Document> documents = repository.page(kbId, ((long) page - 1) * pageSize, pageSize, pattern, status);
        Map<Long, IndexVersion> versions = repository
                .versions(documents.stream()
                        .map(Document::getActiveVersionId)
                        .filter(Objects::nonNull)
                        .distinct()
                        .toList())
                .stream()
                .collect(Collectors.toMap(IndexVersion::getId, Function.identity()));
        Map<Long, ImportTask> tasks = repository
                .tasks(documents.stream().map(Document::getId).toList())
                .stream()
                .collect(Collectors.toMap(ImportTask::getDocumentId, Function.identity()));
        List<DocumentSummaryResponse> items = documents.stream()
                .map(d -> {
                    IndexVersion version = versions.get(d.getActiveVersionId());
                    // activeVersionId 是发布事实；不能仅凭版本状态或任务成功决定可见性。
                    if (d.getActiveVersionId() != null) {
                        if (version == null || !Objects.equals(d.getId(), version.getDocumentId()))
                            throw new BusinessException(CommonErrorCode.INTERNAL_ERROR);
                        if (version.getStatus() != IndexVersionStatus.PUBLISHED || version.getPublishedAt() == null)
                            LOG.warn(
                                    "event=PUBLICATION_METADATA_INCONSISTENT documentId={} indexVersionId={}",
                                    d.getId(),
                                    d.getActiveVersionId());
                    }
                    ImportTask task = tasks.get(d.getId());
                    return new DocumentSummaryResponse(
                            d.getId(),
                            d.getFilename(),
                            d.getCreatedAt(),
                            d.getActiveVersionId() == null ? "UNPUBLISHED" : "PUBLISHED",
                            d.getActiveVersionId(),
                            version == null ? null : version.getPublishedAt(),
                            task == null ? null : ImportTaskResponse.from(task));
                })
                .toList();
        return new DocumentListResponse(items, page, pageSize, total);
    }

    @Transactional
    public void delete(Long kbId, Long documentId, long userId) {
        // 与上传和知识库删除共用知识库行锁，再按发布事务的顺序锁定文档。
        knowledgeBases.lockOwnedActive(kbId, userId);
        if (!repository.lockActive(kbId, documentId)) throw new BusinessException(CommonErrorCode.NOT_FOUND);
        // 等待重试也属于未结束导入。普通读取任务状态，避免与任务领取互相等待。
        if (repository.hasActiveImport(documentId)) throw new BusinessException(CommonErrorCode.DOCUMENT_BUSY);
        if (!repository.markDeleted(kbId, documentId)) throw new BusinessException(CommonErrorCode.NOT_FOUND);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public CitationEvidenceResponse evidence(Long kbId, Long documentId, Long versionId, Long chunkId) {
        if (kbId == null
                || kbId <= 0
                || documentId == null
                || documentId <= 0
                || versionId == null
                || versionId <= 0
                || chunkId == null
                || chunkId <= 0) throw new BusinessException(CommonErrorCode.INVALID_ARGUMENT);
        knowledgeBases.get(kbId);
        Document d = repository.document(documentId);
        if (d == null || !Objects.equals(kbId, d.getKnowledgeBaseId()))
            throw new BusinessException(CommonErrorCode.NOT_FOUND);
        // 权威指针损坏返回内部错误；不能通过状态或未命中分支隐藏。
        if (d.getActiveVersionId() == null) throw new BusinessException(CommonErrorCode.NOT_FOUND);
        List<IndexVersion> versions = repository.versions(List.of(d.getActiveVersionId()));
        if (versions.isEmpty()
                || !Objects.equals(documentId, versions.getFirst().getDocumentId()))
            throw new BusinessException(CommonErrorCode.INTERNAL_ERROR);
        IndexVersion active = versions.getFirst();
        if (active.getStatus() != IndexVersionStatus.PUBLISHED || active.getPublishedAt() == null)
            LOG.warn(
                    "event=PUBLICATION_METADATA_INCONSISTENT documentId={} indexVersionId={}",
                    d.getId(),
                    d.getActiveVersionId());
        // 只允许当前活动版本，发布元数据不额外限制 M2 可见性。
        if (!Objects.equals(versionId, active.getId())) throw new BusinessException(CommonErrorCode.NOT_FOUND);
        DocumentChunk c = repository.chunk(chunkId);
        // 必须校验 Chunk 的知识库、文档、版本全部归属，防止仅凭 ID 读取正文。
        if (c == null
                || !Objects.equals(kbId, c.getKnowledgeBaseId())
                || !Objects.equals(documentId, c.getDocumentId())
                || !Objects.equals(versionId, c.getIndexVersionId()))
            throw new BusinessException(CommonErrorCode.NOT_FOUND);
        if (c.getSourceStart() == null
                || c.getSourceEnd() == null
                || c.getSourceStart() < 0
                || c.getSourceEnd() <= c.getSourceStart()) throw new BusinessException(CommonErrorCode.INTERNAL_ERROR);
        return new CitationEvidenceResponse(
                c.getId(),
                documentId,
                versionId,
                d.getFilename(),
                c.getHeadingPath(),
                c.getContent(),
                c.getSourceStart(),
                c.getSourceEnd());
    }
}
