package com.lnjecit.knowledge.assistant.ingestion.publish;

import com.lnjecit.knowledge.assistant.ingestion.task.ClaimedTask;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface IndexPublishMapper {
    int publishDocument(@Param("claim") ClaimedTask claim);

    int publishVersion(@Param("claim") ClaimedTask claim);

    int succeedTask(@Param("claim") ClaimedTask claim);
}
