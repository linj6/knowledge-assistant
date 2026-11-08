package com.lnjecit.knowledge.assistant.infrastructure.persistence;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

/** 原文件元数据及首次发布指针；关联完整性与发布后不可变要求由业务事务维护。 */
@Getter
@Setter
@TableName("document")
public class Document {
    /** 服务端生成的 Snowflake 主键，数据库不自增。 */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    /** 所属知识库标识；无外键，关联存在性与归属由业务事务校验。 */
    private Long knowledgeBaseId;
    /** 原始文件展示名称，不参与对象存储路径拼接。 */
    private String filename;
    /** 原始内容摘要，与知识库标识联合唯一，用于同库内容去重。 */
    private String contentHash;
    /** 原文件在对象存储中的服务端生成 Key。 */
    private String objectKey;
    /** 正式发布事实；首次发布前为空，必须属于当前文档，V1 发布后不可修改，均由发布事务保证。 */
    private Long activeVersionId;
    /** 记录创建时刻，使用 Instant 表达 UTC 时间线上的瞬时。 */
    private Instant createdAt;
}
