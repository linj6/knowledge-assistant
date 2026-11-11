package com.lnjecit.knowledge.assistant.ingestion.upload;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface ImportCapacityMapper {
    Long lockKnowledgeBase(@Param("kbId") Long kbId);
}
