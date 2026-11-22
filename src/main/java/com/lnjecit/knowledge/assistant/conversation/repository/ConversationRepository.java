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

    public void insertMessage(Message message) {
        messageMapper.insert(message);
    }

    public Conversation findOwnedConversation(Long id, long ownerUserId) {
        return conversationMapper.selectOne(new LambdaQueryWrapper<Conversation>()
                .eq(Conversation::getId, id)
                .eq(Conversation::getOwnerUserId, ownerUserId));
    }

    public List<Conversation> listOwnedConversations(long ownerUserId, Long knowledgeBaseId) {
        return conversationMapper.selectList(new LambdaQueryWrapper<Conversation>()
                .eq(Conversation::getOwnerUserId, ownerUserId)
                .eq(Conversation::getKnowledgeBaseId, knowledgeBaseId)
                .orderByDesc(Conversation::getUpdatedAt, Conversation::getId));
    }

    public List<Message> listMessages(Long conversationId, int limit, long offset) {
        return messageMapper.selectHistory(conversationId, limit, offset);
    }
}
