package com.lnjecit.knowledge.assistant.conversation.service;

import com.lnjecit.knowledge.assistant.common.error.BusinessException;
import com.lnjecit.knowledge.assistant.common.error.CommonErrorCode;
import com.lnjecit.knowledge.assistant.conversation.entity.Conversation;
import com.lnjecit.knowledge.assistant.conversation.entity.Message;
import com.lnjecit.knowledge.assistant.conversation.repository.ConversationRepository;
import com.lnjecit.knowledge.assistant.knowledge.service.KnowledgeBaseService;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ConversationService {
    static final String DEFAULT_TITLE = "新会话";

    private final ConversationRepository repository;
    private final KnowledgeBaseService knowledgeBaseService;

    public ConversationService(ConversationRepository repository, KnowledgeBaseService knowledgeBaseService) {
        this.repository = repository;
        this.knowledgeBaseService = knowledgeBaseService;
    }

    @Transactional
    public Conversation create(long userId, Long knowledgeBaseId) {
        // 持有知识库行锁直
        // 到会话写入提交，避免与知识库删除交错。
        lockActiveKnowledgeBase(userId, knowledgeBaseId);
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        Conversation conversation = new Conversation();
        conversation.setOwnerUserId(userId);
        conversation.setKnowledgeBaseId(knowledgeBaseId);
        conversation.setTitle(DEFAULT_TITLE);
        conversation.setCreatedAt(now);
        conversation.setUpdatedAt(now);
        repository.insertConversation(conversation);
        return conversation;
    }

    public List<Conversation> list(long userId, Long knowledgeBaseId) {
        knowledgeBaseService.requireOwned(knowledgeBaseId, userId);
        return repository.listOwnedConversations(userId, knowledgeBaseId);
    }

    public Conversation requireOwned(long userId, Long id) {
        // 按会话 ID 和当前用户一起查询，越权与不存在返回相同结果。
        Conversation conversation = repository.findOwnedConversation(id, userId);
        if (conversation == null) {
            throw new BusinessException(CommonErrorCode.CONVERSATION_NOT_FOUND);
        }
        knowledgeBaseService.requireOwned(conversation.getKnowledgeBaseId(), userId);
        return conversation;
    }

    void lockActiveKnowledgeBase(long userId, Long knowledgeBaseId) {
        knowledgeBaseService.lockOwnedActive(knowledgeBaseId, userId);
    }

    public List<Message> listMessages(long userId, Long id, int page, int pageSize) {
        requireOwned(userId, id);
        // 多查一条判断是否还有下一页，无需统计全部消息。
        long offset = (page - 1L) * pageSize;
        return repository.listMessages(id, pageSize + 1, offset);
    }
}
