package com.lnjecit.knowledge.assistant.conversation.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.lnjecit.knowledge.assistant.conversation.entity.Conversation;
import com.lnjecit.knowledge.assistant.conversation.entity.Message;
import com.lnjecit.knowledge.assistant.conversation.entity.MessageRole;
import com.lnjecit.knowledge.assistant.conversation.entity.MessageStatus;
import com.lnjecit.knowledge.assistant.conversation.mapper.ConversationMapper;
import com.lnjecit.knowledge.assistant.conversation.mapper.MessageMapper;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
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

    public Conversation lockOwnedConversation(Long id, long ownerUserId) {
        return conversationMapper.selectOne(new LambdaQueryWrapper<Conversation>()
                .eq(Conversation::getId, id)
                .eq(Conversation::getOwnerUserId, ownerUserId)
                .last("FOR UPDATE"));
    }

    public List<Message> findTurn(Long conversationId, UUID turnId) {
        return messageMapper.selectList(new LambdaQueryWrapper<Message>()
                .eq(Message::getConversationId, conversationId)
                .apply("turn_id = CAST({0} AS uuid)", turnId.toString()));
    }

    public boolean hasActiveTurn(Long conversationId) {
        return messageMapper.exists(new LambdaQueryWrapper<Message>()
                .eq(Message::getConversationId, conversationId)
                .eq(Message::getRole, MessageRole.ASSISTANT)
                .in(Message::getStatus, MessageStatus.PROCESSING, MessageStatus.CANCEL_REQUESTED));
    }

    public boolean hasUserMessages(Long conversationId) {
        return messageMapper.exists(new LambdaQueryWrapper<Message>()
                .eq(Message::getConversationId, conversationId)
                .eq(Message::getRole, MessageRole.USER));
    }

    public void touchConversation(Long id, Instant updatedAt, String title) {
        conversationMapper.update(new LambdaUpdateWrapper<Conversation>()
                .eq(Conversation::getId, id)
                .set(Conversation::getUpdatedAt, updatedAt)
                .set(title != null, Conversation::getTitle, title));
    }

    public void completeTurn(Long assistantId, Message result) {
        messageMapper.update(
                result,
                new LambdaUpdateWrapper<Message>()
                        .eq(Message::getId, assistantId)
                        .eq(Message::getRole, MessageRole.ASSISTANT)
                        .eq(Message::getStatus, MessageStatus.PROCESSING));
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
