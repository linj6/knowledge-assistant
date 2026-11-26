package com.lnjecit.knowledge.assistant.conversation.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("chat_conversation")
public class Conversation {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long ownerUserId;
    private Long knowledgeBaseId;
    private String title;
    private Instant createdAt;
    private Instant updatedAt;
    private Instant deletedAt;
    private Boolean titleManuallyEdited;
}
