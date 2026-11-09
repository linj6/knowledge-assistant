package com.lnjecit.knowledge.assistant.ingestion.upload;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface OrphanObjectMapper {
    boolean referenced(@Param("key") String key);
}
