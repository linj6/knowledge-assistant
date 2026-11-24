package com.lnjecit.knowledge.assistant.memory.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("user_memory")
public class UserMemory {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long userId;
    private MemoryType type;
    private String content;
    private String keywords;
    private String sourceType;
    private Boolean enabled;
    private Instant createdAt;
    private Instant updatedAt;
}
