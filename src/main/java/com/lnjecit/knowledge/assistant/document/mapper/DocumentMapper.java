package com.lnjecit.knowledge.assistant.document.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lnjecit.knowledge.assistant.document.entity.Document;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface DocumentMapper extends BaseMapper<Document> {
    Document selectForUpdate(@Param("id") Long id);
}
