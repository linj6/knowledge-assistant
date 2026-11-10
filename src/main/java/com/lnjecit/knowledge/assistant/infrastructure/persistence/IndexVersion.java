package com.lnjecit.knowledge.assistant.infrastructure.persistence;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

/** 单次索引构建版本；模型、维度和切分配置创建后不可变，已发布版本不得重写。 */
@Getter
@Setter
@TableName("document_index_version")
public class IndexVersion {
    /** 服务端生成的 Snowflake 主键，数据库不自增。 */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    /** 所属文档标识；关联存在性与版本归属由业务事务校验。 */
    private Long documentId;
    /** 索引构建诊断状态；不能单独赋予检索可见性，发布事实以文档 activeVersionId 为准。 */
    private IndexVersionStatus status;
    /** 预期 Chunk 数；构建初始可为零，验证或发布时必须大于零。 */
    private Integer expectedChunkCount;
    /** 创建时固化的向量模型，V1 固定 text-embedding-v4，后续业务不得修改。 */
    private String embeddingModel;
    /** 创建时固化的向量维度，V1 固定 1024。 */
    private Integer embeddingDimension;
    /** 创建时固化的切分配置文本快照，具体配置协议由切分任务定义，后续业务不得修改。 */
    private String chunkingConfig;
    /** 记录创建时刻，使用 Instant 表达 UTC 时间线上的瞬时。 */
    private Instant createdAt;
    /** 版本发布时刻，发布前允许为空，由发布事务写入。 */
    private Instant publishedAt;
}
