package com.lnjecit.knowledge.assistant.knowledge.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lnjecit.knowledge.assistant.knowledge.entity.KnowledgeBase;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface KnowledgeBaseMapper extends BaseMapper<KnowledgeBase> {
    Long lockForImportCapacity(@Param("kbId") Long kbId);
}
