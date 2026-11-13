package com.lnjecit.knowledge.assistant.document.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lnjecit.knowledge.assistant.document.entity.IndexVersion;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface IndexVersionMapper extends BaseMapper<IndexVersion> {
    IndexVersion selectForUpdate(@Param("id") Long id);

    int markVerified(@Param("indexVersionId") Long indexVersionId);
}
