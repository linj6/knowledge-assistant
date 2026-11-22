package com.lnjecit.knowledge.assistant.conversation.service;

import com.lnjecit.knowledge.assistant.common.RequestIdContext;
import com.lnjecit.knowledge.assistant.conversation.dto.TurnResponse;
import com.lnjecit.knowledge.assistant.conversation.entity.Message;
import com.lnjecit.knowledge.assistant.conversation.entity.MessageStatus;
import com.lnjecit.knowledge.assistant.conversation.repository.ConversationRepository;
import com.lnjecit.knowledge.assistant.rag.RagService;
import com.lnjecit.knowledge.assistant.rag.model.RagAnswer;
import com.lnjecit.knowledge.assistant.retrieval.model.RetrievalStrategy;
import jakarta.annotation.PreDestroy;
import java.time.Instant;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

@Component
public class TurnExecutor {
    private static final Logger log = LoggerFactory.getLogger(TurnExecutor.class);
    private final RagService rag;
    private final ConversationRepository repository;
    private final JsonMapper json;
    private final ThreadPoolExecutor executor = new ThreadPoolExecutor(
            4,
            4,
            0L,
            TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(32),
            Thread.ofPlatform().name("conversation-turn-", 1).factory());
    // 以全局唯一的 ASSISTANT 消息 ID 定位任务，供后续取消流程接入。
    private final ConcurrentMap<Long, FutureTask<Void>> tasks = new ConcurrentHashMap<>();

    public TurnExecutor(RagService rag, ConversationRepository repository, JsonMapper json) {
        this.rag = rag;
        this.repository = repository;
        this.json = json;
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public MessageStatus dispatch(Long knowledgeBaseId, TurnResponse turn) {
        Long assistantId = turn.assistantMessageId();
        String requestId = RequestIdContext.current();
        FutureTask<Void> task =
                new FutureTask<>(() -> {
                    if (requestId != null) {
                        RequestIdContext.set(requestId);
                    }
                    try {
                        execute(knowledgeBaseId, turn);
                    } finally {
                        RequestIdContext.clear();
                    }
                    return null;
                }) {
                    @Override
                    protected void done() {
                        tasks.remove(assistantId, this);
                    }
                };
        tasks.put(assistantId, task);
        try {
            executor.execute(task);
            return MessageStatus.PROCESSING;
        } catch (RejectedExecutionException rejected) {
            tasks.remove(assistantId, task);
            log.warn("event=TURN_TASK_REJECTED assistantMessageId={}", assistantId);
            repository.completeTurn(assistantId, failed("TURN_EXECUTOR_REJECTED"));
            return MessageStatus.FAILED;
        }
    }

    private void execute(Long knowledgeBaseId, TurnResponse turn) {
        Message result;
        try {
            // 模型调用在独立线程执行，结果通过单条条件更新写回。
            RagAnswer answer = rag.answer(knowledgeBaseId, turn.question(), RetrievalStrategy.HYBRID_RRF, null);
            result = new Message();
            result.setStatus(MessageStatus.valueOf(answer.status().name()));
            result.setContent(answer.answer());
            result.setCitations(json.writeValueAsString(answer.citations()));
            result.setErrorCode(answer.error() == null ? null : answer.error().name());
            result.setCompletedAt(Instant.now());
        } catch (RuntimeException failure) {
            log.error(
                    "event=TURN_EXECUTION_FAILED assistantMessageId={} failureType={}",
                    turn.assistantMessageId(),
                    failure.getClass().getSimpleName());
            result = failed("TURN_EXECUTION_FAILED");
        }
        try {
            repository.completeTurn(turn.assistantMessageId(), result);
        } catch (RuntimeException failure) {
            log.error(
                    "event=TURN_RESULT_WRITE_FAILED assistantMessageId={} failureType={}",
                    turn.assistantMessageId(),
                    failure.getClass().getSimpleName());
        }
    }

    private static Message failed(String errorCode) {
        Message result = new Message();
        result.setStatus(MessageStatus.FAILED);
        result.setErrorCode(errorCode);
        result.setCitations("[]");
        result.setCompletedAt(Instant.now());
        return result;
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdown();
    }
}
