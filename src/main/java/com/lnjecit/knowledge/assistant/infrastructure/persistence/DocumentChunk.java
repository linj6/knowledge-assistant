package com.lnjecit.knowledge.assistant.infrastructure.persistence;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/** DocumentChunk 持久化记录；时间统一为 UTC 瞬时，不作为 API DTO。 */
@TableName("document_chunk")
public class DocumentChunk {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    public Long getId() {
        return id;
    }

    public void setId(Long value) {
        this.id = value;
    }

    /** 所属知识库；无外键，写入事务必须校验归属。 */
    private Long knowledgeBaseId;

    public Long getKnowledgeBaseId() {
        return knowledgeBaseId;
    }

    public void setKnowledgeBaseId(Long value) {
        this.knowledgeBaseId = value;
    }

    /** 所属文档；写入事务必须校验关联。 */
    private Long documentId;

    public Long getDocumentId() {
        return documentId;
    }

    public void setDocumentId(Long value) {
        this.documentId = value;
    }

    /** 所属索引版本；跨记录归属由后续事务校验。 */
    private Long indexVersionId;

    public Long getIndexVersionId() {
        return indexVersionId;
    }

    public void setIndexVersionId(Long value) {
        this.indexVersionId = value;
    }

    private Integer chunkOrder;

    public Integer getChunkOrder() {
        return chunkOrder;
    }

    public void setChunkOrder(Integer value) {
        this.chunkOrder = value;
    }

    private String content;

    public String getContent() {
        return content;
    }

    public void setContent(String value) {
        this.content = value;
    }

    /** 原始文本 Unicode 码点起点，包含。 */
    private Integer sourceStart;

    public Integer getSourceStart() {
        return sourceStart;
    }

    public void setSourceStart(Integer value) {
        this.sourceStart = value;
    }

    /** 原始文本 Unicode 码点终点，不包含。 */
    private Integer sourceEnd;

    public Integer getSourceEnd() {
        return sourceEnd;
    }

    public void setSourceEnd(Integer value) {
        this.sourceEnd = value;
    }

    private String headingPath;

    public String getHeadingPath() {
        return headingPath;
    }

    public void setHeadingPath(String value) {
        this.headingPath = value;
    }

    @TableField(typeHandler = VectorTypeHandler.class)
    /** 1024 维向量文本，构建阶段允许为空。 */
    private String embedding;

    public String getEmbedding() {
        return embedding;
    }

    public void setEmbedding(String value) {
        this.embedding = value;
    }
}
