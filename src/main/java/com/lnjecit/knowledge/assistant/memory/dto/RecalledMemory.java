package com.lnjecit.knowledge.assistant.memory.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.lnjecit.knowledge.assistant.memory.entity.MemoryType;

/** 模型侧 JSON 只包含类型和正文，ID 供应用内部追踪。 */
public record RecalledMemory(@JsonIgnore long id, MemoryType type, String content) {}
