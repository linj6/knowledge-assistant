package com.lnjecit.knowledge.assistant.ingestion.task.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lnjecit.knowledge.assistant.ingestion.task.entity.ImportTask;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ImportTaskMapper extends BaseMapper<ImportTask> {}
