package com.lnjecit.knowledge.assistant.ingestion.chunk;

import com.lnjecit.knowledge.assistant.document.entity.Document;
import com.lnjecit.knowledge.assistant.document.entity.DocumentChunk;
import com.lnjecit.knowledge.assistant.document.entity.IndexVersion;
import com.lnjecit.knowledge.assistant.document.entity.IndexVersionStatus;
import com.lnjecit.knowledge.assistant.ingestion.embedding.EmbeddingProperties;
import com.lnjecit.knowledge.assistant.ingestion.parser.ParsedDocument;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

@Service
public class ChunkService {
    private final ChunkRepository repository;
    private final JsonMapper json;
    private final EmbeddingProperties embeddingProperties;

    public ChunkService(ChunkRepository repository, JsonMapper json, EmbeddingProperties embeddingProperties) {
        this.repository = repository;
        this.json = json;
        this.embeddingProperties = embeddingProperties;
    }
    /** v1 标题路径保存完整 Heading 数组，字段为 text、level、span(start/end)。 */
    public record HeadingPath(int version, List<ParsedDocument.Heading> headings) {
        public HeadingPath {
            headings = List.copyOf(headings);
            if (version != 1) throw new IllegalArgumentException("Unknown heading protocol");
        }
    }

    /** 调用方须先按 Document.objectKey 读取原始字节并核验 contentHash，再解析传入；此方法不读取对象存储。 */
    @Transactional
    public List<DocumentChunk> initialize(Long documentId, Long versionId, ParsedDocument parsed) {
        Document document = repository.document(documentId);
        IndexVersion version = repository.version(versionId);
        if (document == null || version == null || !documentId.equals(version.getDocumentId())) fail();
        // 发布指针、时间与诊断状态均检查；不能只凭 BUILDING 状态允许重写。
        if (document.getActiveVersionId() != null
                || version.getPublishedAt() != null
                || version.getStatus() != IndexVersionStatus.BUILDING) fail();
        ChunkingConfig config = json.readValue(version.getChunkingConfig(), ChunkingConfig.class);
        config.validateMaxCodePoints(embeddingProperties.getMaxInputCodePoints());
        DocumentSplitter splitter =
                switch (config.strategy()) {
                    case FIXED_CODE_POINT -> new FixedCodePointSplitter();
                    case MARKDOWN_STRUCTURE_AWARE -> new MarkdownStructureAwareSplitter();
                };
        List<DocumentSplitter.Chunk> split = splitter.split(parsed, config);
        List<DocumentChunk> existing = repository.find(versionId);
        int expected = version.getExpectedChunkCount();
        if (expected != 0 || !existing.isEmpty()) {
            if (expected != split.size() || existing.size() != expected) fail();
            for (int i = 0; i < expected; i++) {
                DocumentChunk stored = existing.get(i);
                DocumentSplitter.Chunk candidate = split.get(i);
                if (!Objects.equals(stored.getKnowledgeBaseId(), document.getKnowledgeBaseId())
                        || !documentId.equals(stored.getDocumentId())
                        || !versionId.equals(stored.getIndexVersionId())
                        || !Objects.equals(stored.getChunkOrder(), i)
                        || !Objects.equals(stored.getContent(), candidate.content())
                        || !Objects.equals(
                                stored.getSourceStart(), candidate.span().start())
                        || !Objects.equals(
                                stored.getSourceEnd(), candidate.span().end())
                        || !Objects.equals(
                                json.readValue(stored.getHeadingPath(), HeadingPath.class),
                                new HeadingPath(1, candidate.headingPath()))) fail();
            }
            return List.copyOf(existing);
        }
        // 初始集合和数量在同一事务提交；唯一约束冲突直接回滚，不覆盖已有记录。
        List<DocumentChunk> result = new ArrayList<>();
        for (DocumentSplitter.Chunk candidate : split) {
            DocumentChunk chunk = new DocumentChunk();
            chunk.setVersion(0);
            chunk.setKnowledgeBaseId(document.getKnowledgeBaseId());
            chunk.setDocumentId(documentId);
            chunk.setIndexVersionId(versionId);
            chunk.setChunkOrder(candidate.chunkOrder());
            chunk.setContent(candidate.content());
            chunk.setSourceStart(candidate.span().start());
            chunk.setSourceEnd(candidate.span().end());
            chunk.setHeadingPath(json.writeValueAsString(new HeadingPath(1, candidate.headingPath())));
            repository.insert(chunk);
            result.add(chunk);
        }
        repository.initializeCount(versionId, result.size());
        return List.copyOf(result);
    }

    private static void fail() {
        throw new IllegalStateException("Chunk initialization invariant violated");
    }
}
