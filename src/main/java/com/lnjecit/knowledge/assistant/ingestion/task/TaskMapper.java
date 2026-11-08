package com.lnjecit.knowledge.assistant.ingestion.task;

import com.lnjecit.knowledge.assistant.infrastructure.persistence.ImportTask;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface TaskMapper {
    ImportTask lockNext();

    ImportTask lockTask(@Param("id") Long id);

    boolean validOwnership(@Param("task") ImportTask task);

    int claim(@Param("id") Long id, @Param("owner") String owner);

    int fail(@Param("claim") ClaimedTask claim);
}
