package com.lnjecit.knowledge.assistant.ingestion.verification;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface VerificationMapper {
    int markVerified(@Param("indexVersionId") Long indexVersionId);
}
