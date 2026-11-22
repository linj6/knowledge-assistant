package com.lnjecit.knowledge.assistant.conversation.service;

import com.lnjecit.knowledge.assistant.common.RequestIdContext;
import com.lnjecit.knowledge.assistant.conversation.dto.TurnResponse;
import com.lnjecit.knowledge.assistant.conversation.entity.Message;
import com.lnjecit.knowledge.assistant.conversation.entity.MessageStatus;
import com.lnjecit.knowledge.assistant.conversation.repository.ConversationRepository;
import com.lnjecit.knowledge.assistant.infrastructure.ai.ModelCallCancellation;
import com.lnjecit.knowledge.assistant.rag.RagService;
import com.lnjecit.knowledge.assistant.rag.model.RagAnswer;
import com.lnjecit.knowledge.assistant.retrieval.model.RetrievalStrategy;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.time.Instant;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

@Component
@DependsOnDatabaseInitialization
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
    private final ConcurrentMap<Long, TurnTask> tasks = new ConcurrentHashMap<>();

    public TurnExecutor(RagService rag, ConversationRepository repository, JsonMapper json) {
        this.rag = rag;
        this.repository = repository;
        this.json = json;
    }

    @PostConstruct
    void recoverInterruptedTurns() {
        int recovered = repository.recoverInterruptedTurns();
        log.info("event=TURN_STARTUP_RECOVERY recovered={}", recovered);
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public synchronized MessageStatus dispatch(Long knowledgeBaseId, TurnResponse turn) {
        Long assistantId = turn.assistantMessageId();
        // 投递与取消互斥。若取消先于投递，已提交的状态会阻止任务启动。
        MessageStatus status = repository.turnStatus(assistantId);
        if (status != MessageStatus.PROCESSING) {
            return status;
        }
        TurnTask task = new TurnTask(knowledgeBaseId, turn, RequestIdContext.current());
        tasks.put(assistantId, task);
        try {
            executor.execute(task);
            return MessageStatus.PROCESSING;
        } catch (RejectedExecutionException rejected) {
            tasks.remove(assistantId, task);
            log.warn("event=TURN_TASK_REJECTED assistantMessageId={}", assistantId);
            if (repository.completeTurn(assistantId, failed("TURN_EXECUTOR_REJECTED"))) {
                return MessageStatus.FAILED;
            }
            return repository.turnStatus(assistantId);
        }
    }

    public synchronized void cancel(Long assistantId) {
        TurnTask task = tasks.get(assistantId);
        if (task != null) {
            task.cancellation.cancel();
            if (!executor.remove(task)) {
                // 已出队的任务由工作线程确认结束并写回。
                return;
            }
            tasks.remove(assistantId, task);
        }
        finishCancellation(assistantId);
    }

    private void finishCancellation(Long assistantId) {
        try {
            if (repository.completeCancellation(assistantId)) {
                log.info("event=TURN_CANCELLED assistantMessageId={}", assistantId);
            }
        } catch (RuntimeException failure) {
            log.error(
                    "event=TURN_CANCEL_WRITE_FAILED assistantMessageId={} failureType={}",
                    assistantId,
                    failure.getClass().getSimpleName());
        }
    }

    private final class TurnTask implements Runnable {
        private final Long knowledgeBaseId;
        private final TurnResponse turn;
        private final String requestId;
        private final ModelCallCancellation cancellation = new ModelCallCancellation();

        private TurnTask(Long knowledgeBaseId, TurnResponse turn, String requestId) {
            this.knowledgeBaseId = knowledgeBaseId;
            this.turn = turn;
            this.requestId = requestId;
        }

        @Override
        public void run() {
            if (requestId != null) {
                RequestIdContext.set(requestId);
            }
            try {
                cancellation.run(() -> {
                    if (repository.turnStatus(turn.assistantMessageId()) == MessageStatus.PROCESSING) {
                        execute(knowledgeBaseId, turn);
                    }
                    return null;
                });
            } catch (CancellationException cancelled) {
                // 出队后、执行前收到取消时，不进入 RAG。
            } finally {
                Long assistantId = turn.assistantMessageId();
                tasks.remove(assistantId, this);
                try {
                    if (repository.turnStatus(assistantId) == MessageStatus.CANCEL_REQUESTED) {
                        finishCancellation(assistantId);
                    }
                } catch (RuntimeException failure) {
                    log.error(
                            "event=TURN_CANCEL_STATUS_READ_FAILED assistantMessageId={} failureType={}",
                            assistantId,
                            failure.getClass().getSimpleName());
                } finally {
                    RequestIdContext.clear();
                }
            }
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
        } catch (CancellationException cancelled) {
            throw cancelled;
        } catch (RuntimeException failure) {
            log.error(
                    "event=TURN_EXECUTION_FAILED assistantMessageId={} failureType={}",
                    turn.assistantMessageId(),
                    failure.getClass().getSimpleName());
            result = failed("TURN_EXECUTION_FAILED");
        }
        try {
            if (!repository.completeTurn(turn.assistantMessageId(), result)) {
                log.debug("event=TURN_RESULT_DISCARDED assistantMessageId={}", turn.assistantMessageId());
            }
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
