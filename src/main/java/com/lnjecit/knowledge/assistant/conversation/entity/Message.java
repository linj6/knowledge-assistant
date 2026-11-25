package com.lnjecit.knowledge.assistant.conversation.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.lnjecit.knowledge.assistant.infrastructure.persistence.JsonbTypeHandler;
import com.lnjecit.knowledge.assistant.infrastructure.persistence.UuidTypeHandler;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;
import org.apache.ibatis.type.JdbcType;

@Getter
@Setter
@TableName(value = "chat_message", autoResultMap = true)
public class Message {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long conversationId;

    @TableField(jdbcType = JdbcType.OTHER, typeHandler = UuidTypeHandler.class)
    private UUID turnId;

    private MessageRole role;
    private String content;
    private MessageStatus status;
    private String errorCode;

    /** 引用数组的 JSON 文本，具体元素结构由后续业务定义。 */
    @TableField(jdbcType = JdbcType.OTHER, typeHandler = JsonbTypeHandler.class)
    private String citations;

    @TableField(jdbcType = JdbcType.OTHER, typeHandler = JsonbTypeHandler.class)
    private String memoryTrace;

    private Instant createdAt;
    private Instant completedAt;
}
