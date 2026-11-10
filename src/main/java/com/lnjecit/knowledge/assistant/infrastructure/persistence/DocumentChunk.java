package com.lnjecit.knowledge.assistant.infrastructure.persistence;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/** 索引版本内的正文片段及向量，稳定主键供 PostgreSQL、ES 和引用共同使用；发布后不得重写。 */
@Getter
@Setter
@TableName("document_chunk")
public class DocumentChunk {
    /** 服务端生成的 Snowflake 主键，数据库不自增。 */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    /** 记录级乐观锁版本，与索引版本及 Worker fencing 无关。 */
    private Integer version;
    /** 所属知识库标识；无外键，关联存在性与归属由业务事务校验。 */
    private Long knowledgeBaseId;
    /** 所属文档标识；关联存在性与版本归属由业务事务校验。 */
    private Long documentId;
    /** 所属索引版本标识；必须与任务或 Chunk 的文档归属一致，由业务事务校验。 */
    private Long indexVersionId;
    /** 版本内从零开始的 Chunk 顺序，同版本内唯一。 */
    private Integer chunkOrder;
    /** Chunk 正文，供索引构建和引用使用。 */
    private String content;
    /** 解析前原始文本的 Unicode 码点起点，包含该位置且不得小于零，不是 UTF-16 下标。 */
    private Integer sourceStart;
    /** 解析前原始文本的 Unicode 码点终点，不包含该位置，必须大于起点，构成 [start,end) 区间。 */
    private Integer sourceEnd;
    /** Markdown 标题路径等辅助定位信息，允许为空。 */
    private String headingPath;
    /** 1024 维 PostgreSQL vector 的文本表示，由 TypeHandler 绑定；构建时可空，发布前非空由业务校验。 */
    @TableField(typeHandler = VectorTypeHandler.class)
    private String embedding;
}
