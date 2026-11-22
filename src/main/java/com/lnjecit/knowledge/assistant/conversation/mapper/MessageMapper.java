package com.lnjecit.knowledge.assistant.conversation.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lnjecit.knowledge.assistant.conversation.entity.Message;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface MessageMapper extends BaseMapper<Message> {
    List<Message> selectHistory(
            @Param("conversationId") Long conversationId, @Param("limit") int limit, @Param("offset") long offset);
}
