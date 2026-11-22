package com.lnjecit.knowledge.assistant.conversation.service;

import com.lnjecit.knowledge.assistant.common.error.BusinessException;
import com.lnjecit.knowledge.assistant.common.error.CommonErrorCode;
import com.lnjecit.knowledge.assistant.conversation.dto.TurnResponse;
import com.lnjecit.knowledge.assistant.conversation.entity.Conversation;
import com.lnjecit.knowledge.assistant.conversation.entity.Message;
import com.lnjecit.knowledge.assistant.conversation.entity.MessageRole;
import com.lnjecit.knowledge.assistant.conversation.entity.MessageStatus;
import com.lnjecit.knowledge.assistant.conversation.repository.ConversationRepository;
import com.lnjecit.knowledge.assistant.rag.RagService;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class TurnService {
    private final ConversationService conversations;
    private final ConversationRepository repository;
    private final TurnExecutor executor;
    private final TransactionTemplate submission;

    public TurnService(
            ConversationService conversations,
            ConversationRepository repository,
            TurnExecutor executor,
            PlatformTransactionManager transactionManager) {
        this.conversations = conversations;
        this.repository = repository;
        this.executor = executor;
        submission = new TransactionTemplate(transactionManager);
        submission.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public TurnResponse.Accepted submit(long userId, Long conversationId, UUID turnId, String question) {
        RagService.validateQuestion(question);
        Submission result;
        try {
            result = submission.execute(status -> prepare(userId, conversationId, turnId, question));
        } catch (DuplicateKeyException conflict) {
            // V8 约束冲突会先回滚整轮，再读取已提交的消息。
            conversations.requireOwned(userId, conversationId);
            TurnResponse existing = findTurn(conversationId, turnId);
            if (existing == null) {
                throw new BusinessException(CommonErrorCode.TURN_IN_PROGRESS);
            }
            result = replay(existing, question);
        }

        // 只有创建这轮消息的请求会在事务提交后投递任务。
        MessageStatus status = result.turn().status();
        if (result.knowledgeBaseId() != null) {
            status = executor.dispatch(result.knowledgeBaseId(), result.turn());
        }
        return new TurnResponse.Accepted(turnId, status);
    }

    public TurnResponse.Accepted cancel(long userId, Long conversationId, UUID turnId) {
        TurnResponse turn = get(userId, conversationId, turnId);
        Long assistantId = turn.assistantMessageId();
        if (repository.requestCancellation(assistantId)
                || repository.turnStatus(assistantId) == MessageStatus.CANCEL_REQUESTED) {
            executor.cancel(assistantId);
        }
        return new TurnResponse.Accepted(turnId, repository.turnStatus(assistantId));
    }

    public TurnResponse get(long userId, Long conversationId, UUID turnId) {
        conversations.requireOwned(userId, conversationId);
        TurnResponse turn = findTurn(conversationId, turnId);
        if (turn == null) {
            throw new BusinessException(CommonErrorCode.NOT_FOUND);
        }
        return turn;
    }

    private Submission prepare(long userId, Long id, UUID turnId, String question) {
        // 锁定所属会话，串行化同一会话内的幂等检查和消息创建。
        Conversation conversation = repository.lockOwnedConversation(id, userId);
        if (conversation == null) {
            throw new BusinessException(CommonErrorCode.CONVERSATION_NOT_FOUND);
        }
        TurnResponse existing = findTurn(id, turnId);
        if (existing != null) {
            return replay(existing, question);
        }
        if (repository.hasActiveTurn(id)) {
            throw new BusinessException(CommonErrorCode.TURN_IN_PROGRESS);
        }

        String title = null;
        if (ConversationService.DEFAULT_TITLE.equals(conversation.getTitle()) && !repository.hasUserMessages(id)) {
            String stripped = question.strip();
            int length = Math.min(200, stripped.codePointCount(0, stripped.length()));
            title = stripped.substring(0, stripped.offsetByCodePoints(0, length));
        }
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        Message user = message(id, turnId, MessageRole.USER, now);
        user.setContent(question);
        repository.insertMessage(user);
        Message assistant = message(id, turnId, MessageRole.ASSISTANT, now);
        assistant.setStatus(MessageStatus.PROCESSING);
        repository.insertMessage(assistant);
        repository.touchConversation(id, now, title);
        return new Submission(TurnResponse.from(user, assistant), conversation.getKnowledgeBaseId());
    }

    private TurnResponse findTurn(Long conversationId, UUID turnId) {
        List<Message> messages = repository.findTurn(conversationId, turnId);
        if (messages.size() != 2) {
            return null;
        }
        Message first = messages.getFirst();
        Message second = messages.getLast();
        return first.getRole() == MessageRole.USER
                ? TurnResponse.from(first, second)
                : TurnResponse.from(second, first);
    }

    private static Submission replay(TurnResponse existing, String question) {
        if (!question.equals(existing.question())) {
            throw new BusinessException(CommonErrorCode.TURN_ID_CONFLICT);
        }
        return new Submission(existing, null);
    }

    private static Message message(Long conversationId, UUID turnId, MessageRole role, Instant now) {
        Message message = new Message();
        message.setConversationId(conversationId);
        message.setTurnId(turnId);
        message.setRole(role);
        message.setCreatedAt(now);
        message.setCitations("[]");
        return message;
    }

    private record Submission(TurnResponse turn, Long knowledgeBaseId) {}
}
