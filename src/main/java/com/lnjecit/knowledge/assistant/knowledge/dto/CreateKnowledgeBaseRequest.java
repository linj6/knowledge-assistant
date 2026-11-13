package com.lnjecit.knowledge.assistant.knowledge.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 创建参数在绑定时标准化，校验针对标准化后的内容。 */
public record CreateKnowledgeBaseRequest(@NotBlank @Size(max = 100) String name, @Size(max = 1000) String description) {
    public CreateKnowledgeBaseRequest {
        name = name == null ? null : name.strip();
        description = description == null ? null : description.strip();
    }
}
