package com.lnjecit.knowledge.assistant.ingestion.task;

import com.lnjecit.knowledge.assistant.infrastructure.persistence.ImportTask;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface TaskMapper {
    ImportTask lockNext(@Param("maxAttempts") int maxAttempts);

    ImportTask lockExpired(@Param("owner") String owner);

    ImportTask lockTask(@Param("id") Long id);

    boolean validOwnership(@Param("task") ImportTask task);

    int claim(
            @Param("id") Long id,
            @Param("owner") String owner,
            @Param("leaseMillis") long leaseMillis,
            @Param("maxAttempts") int maxAttempts);

    int fail(
            @Param("claim") ClaimedTask claim,
            @Param("code") String code,
            @Param("message") String message,
            @Param("retryMillis") Long retryMillis);

    int recover(@Param("id") Long id, @Param("owner") String owner, @Param("retryMillis") Long retryMillis);
}
