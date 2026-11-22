package com.lnjecit.knowledge.assistant.conversation.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lnjecit.knowledge.assistant.conversation.entity.Conversation;
import com.lnjecit.knowledge.assistant.conversation.entity.Message;
import com.lnjecit.knowledge.assistant.conversation.mapper.ConversationMapper;
import com.lnjecit.knowledge.assistant.conversation.mapper.MessageMapper;
import java.util.List;
import org.springframework.stereotype.Repository;

@Repository
public class ConversationRepository {
    private final ConversationMapper conversationMapper;
    private final MessageMapper messageMapper;

    public ConversationRepository(ConversationMapper conversationMapper, MessageMapper messageMapper) {
        this.conversationMapper = conversationMapper;
        this.messageMapper = messageMapper;
    }

    public void insertConversation(Conversation conversation) {
        conversationMapper.insert(conversation);
    }

    public Conversation findConversation(Long id) {
        return conversationMapper.selectById(id);
    }

    public void insertMessage(Message message) {
        messageMapper.insert(message);
    }

    /** 同一写入时刻的消息按主键排序，保证读取顺序稳定。 */
    public List<Message> listMessages(Long conversationId) {
        return messageMapper.selectList(new LambdaQueryWrapper<Message>()
                .eq(Message::getConversationId, conversationId)
                .orderByAsc(Message::getCreatedAt, Message::getId));
    }
}
