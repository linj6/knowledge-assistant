package com.lnjecit.knowledge.assistant.knowledge.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lnjecit.knowledge.assistant.knowledge.entity.KnowledgeBase;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface KnowledgeBaseMapper extends BaseMapper<KnowledgeBase> {
    List<KnowledgeBase> selectOwnedWithCount(
            @Param("ownerUserId") long ownerUserId, @Param("id") Long id, @Param("pattern") String pattern);

    Long lockOwnedActive(@Param("kbId") Long kbId, @Param("ownerUserId") long ownerUserId);

    boolean hasActiveWork(@Param("kbId") Long kbId);

    Long lockForImportCapacity(@Param("kbId") Long kbId);
}
