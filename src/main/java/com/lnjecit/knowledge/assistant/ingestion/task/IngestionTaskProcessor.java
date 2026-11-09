package com.lnjecit.knowledge.assistant.ingestion.task;

import com.lnjecit.knowledge.assistant.infrastructure.ai.ModelFailures;
import com.lnjecit.knowledge.assistant.infrastructure.persistence.Document;
import com.lnjecit.knowledge.assistant.infrastructure.persistence.IndexVersion;
import com.lnjecit.knowledge.assistant.infrastructure.persistence.IndexVersionStatus;
import com.lnjecit.knowledge.assistant.infrastructure.storage.ObjectKeyPrefixes;
import com.lnjecit.knowledge.assistant.infrastructure.storage.OriginalFileStore;
import com.lnjecit.knowledge.assistant.ingestion.chunk.ChunkRepository;
import com.lnjecit.knowledge.assistant.ingestion.chunk.ChunkService;
import com.lnjecit.knowledge.assistant.ingestion.embedding.EmbeddingService;
import com.lnjecit.knowledge.assistant.ingestion.indexing.EsIndexService;
import com.lnjecit.knowledge.assistant.ingestion.parser.DocumentParserFactory;
import com.lnjecit.knowledge.assistant.ingestion.parser.ParseException;
import com.lnjecit.knowledge.assistant.ingestion.parser.ParsedDocument;
import com.lnjecit.knowledge.assistant.ingestion.publish.IndexPublishService;
import com.lnjecit.knowledge.assistant.ingestion.verification.IndexVerificationService;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
@ConditionalOnProperty(
        name = {"assistant.model.enabled", "assistant.elasticsearch.enabled"},
        havingValue = "true")
public class IngestionTaskProcessor implements TaskProcessor {
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(IngestionTaskProcessor.class);
    private final TaskProcessingRepository tasks;
    private final ChunkRepository records;
    private final OriginalFileStore files;
    private final ChunkService chunks;
    private final EmbeddingService embeddings;
    private final EsIndexService es;
    private final IndexVerificationService verification;
    private final IndexPublishService publisher;
    private final DocumentParserFactory parsers = new DocumentParserFactory();

    public IngestionTaskProcessor(
            TaskProcessingRepository tasks,
            ChunkRepository records,
            OriginalFileStore files,
            ChunkService chunks,
            EmbeddingService embeddings,
            EsIndexService es,
            IndexVerificationService verification,
            IndexPublishService publisher) {
        this.tasks = tasks;
        this.records = records;
        this.files = files;
        this.chunks = chunks;
        this.embeddings = embeddings;
        this.es = es;
        this.verification = verification;
        this.publisher = publisher;
    }

    @Override
    public void process(ClaimedTask task, TaskExecutionContext context) {
        if (TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("PROCESSOR_TRANSACTION_NOT_ALLOWED");
        try {
            context.checkTimeout();
            if (task == null
                    || task.taskId() == null
                    || task.documentId() == null
                    || task.indexVersionId() == null
                    || task.leaseOwner() == null
                    || task.leaseOwner().isBlank()
                    || task.claimEpoch() <= 0) invalid();
            com.lnjecit.knowledge.assistant.infrastructure.persistence.ImportTask current = tasks.task(task.taskId());
            if (current == null
                    || !Objects.equals(current.getDocumentId(), task.documentId())
                    || !Objects.equals(current.getIndexVersionId(), task.indexVersionId())
                    || !Objects.equals(current.getClaimEpoch(), task.claimEpoch())) invalid();
            Document document = records.document(task.documentId());
            IndexVersion version = records.version(task.indexVersionId());
            if (document == null
                    || version == null
                    || !Objects.equals(document.getId(), task.documentId())
                    || !Objects.equals(version.getId(), task.indexVersionId())
                    || !Objects.equals(version.getDocumentId(), document.getId())) invalid();
            // 发布事实只能交给原发布事务核对，绝不重新写入索引。
            if (document.getActiveVersionId() != null
                    || version.getPublishedAt() != null
                    || version.getStatus() == IndexVersionStatus.PUBLISHED) {
                publish(task, context, true);
                return;
            }
            if (current.getStatus()
                            != com.lnjecit.knowledge.assistant.infrastructure.persistence.ImportTaskStatus.RUNNING
                    || !Objects.equals(current.getLeaseOwner(), task.leaseOwner())) invalid();
            if (version.getStatus() != IndexVersionStatus.BUILDING
                    && version.getStatus() != IndexVersionStatus.VERIFIED) invalid();
            if (version.getStatus() == IndexVersionStatus.BUILDING) {
                String key = document.getObjectKey();
                if (key == null
                        || !key.matches(ObjectKeyPrefixes.ORIGINALS + "[A-Za-z0-9-]+")
                        || document.getFilename() == null) invalid();
                // 对象位置只取数据库；先校验原始字节摘要，再解析和初始化。
                context.checkTimeout();
                byte[] bytes = files.read(key);
                context.checkTimeout();
                if (!sha256(bytes).equals(document.getContentHash())) invalid();
                ParsedDocument parsed = parsers.parse(document.getFilename(), bytes);
                context.checkTimeout();
                long started = System.nanoTime();
                int chunkCount = chunks.initialize(document.getId(), version.getId(), parsed)
                        .size();
                stage("IMPORT_CHUNK_COMPLETED", task, current.getAttemptCount(), chunkCount, started);
                started = System.nanoTime();
                context.checkTimeout();
                embeddings.embed(document.getId(), version.getId(), context::checkTimeout);
                context.checkTimeout();
                stage("IMPORT_EMBEDDING_COMPLETED", task, current.getAttemptCount(), chunkCount, started);
                started = System.nanoTime();
                es.index(document.getId(), version.getId());
                stage("IMPORT_ES_COMPLETED", task, current.getAttemptCount(), chunkCount, started);
                context.checkTimeout();
            }
            // VERIFIED 仍需重新核验；最终领取权由发布事务使用数据库时间裁决。
            context.checkTimeout();
            long started = System.nanoTime();
            IndexVerificationService.Result result = verification.verify(document.getId(), version.getId());
            if (result == null
                    || !"VERIFIED".equals(result.verificationStatus())
                    || !Objects.equals(result.documentId(), document.getId())
                    || !Objects.equals(result.indexVersionId(), version.getId())) invalid();
            context.checkTimeout();
            stage("IMPORT_VERIFIED", task, current.getAttemptCount(), result.expectedChunkCount(), started);
            publish(task, context, false);
            // 发布提交后不再检查截止时间，也不执行任何可能触发失败转换的阶段。
        } catch (ParseException failure) {
            throw new TaskExecutionException(
                    failure.kind() == ParseException.Kind.UNSUPPORTED_FORMAT
                            ? TaskExecutionException.Code.UNSUPPORTED_FORMAT
                            : TaskExecutionException.Code.INVALID_CONTENT);
        } catch (ModelFailures.SafeModelException failure) {
            throw new TaskExecutionException(
                    switch (failure.kind()) {
                        case TIMEOUT -> TaskExecutionException.Code.MODEL_TIMEOUT;
                        case CONNECTION, RATE_LIMIT, SERVER -> TaskExecutionException.Code.MODEL_UNAVAILABLE;
                        default -> TaskExecutionException.Code.EXECUTION_FAILED;
                    });
        } catch (IllegalStateException failure) {
            if ("ES_TIMEOUT_OUTCOME_UNKNOWN".equals(failure.getMessage())
                    || "ES_CONNECTION_OUTCOME_UNKNOWN".equals(failure.getMessage()))
                throw new TaskExecutionException(TaskExecutionException.Code.SEARCH_UNAVAILABLE);
            throw failure;
        }
    }

    private void publish(ClaimedTask task, TaskExecutionContext context, boolean confirmed) {
        long started = System.nanoTime();
        String outcome = confirmed ? "EXISTING_FACTS_CONFIRMED" : "FIRST_PUBLISH";
        try {
            publisher.publish(task);
        } catch (org.springframework.transaction.TransactionSystemException
                | org.springframework.dao.DataAccessResourceFailureException uncertain) {
            ImportEvents.warn(
                    LOG,
                    "event=IMPORT_PUBLISH_OUTCOME_UNKNOWN taskId={} documentId={} indexVersionId={} errorCode=PUBLISH_OUTCOME_UNKNOWN exceptionType={}",
                    task.taskId(),
                    task.documentId(),
                    task.indexVersionId(),
                    uncertain.getClass().getName());
            outcome = "UNCERTAIN_RESULT_RESOLVED";
            // 提交响应丢失时先读任务事实，再由发布事务核对完整发布事实。
            com.lnjecit.knowledge.assistant.infrastructure.persistence.ImportTask current = tasks.task(task.taskId());
            if (current == null
                    || current.getStatus()
                            != com.lnjecit.knowledge.assistant.infrastructure.persistence.ImportTaskStatus.SUCCEEDED)
                context.checkTimeout();
            publisher.publish(task);
        }
        // 代理事务方法返回后才记录发布成功；日志异常不会触发失败转换。
        ImportEvents.info(
                LOG,
                "event=IMPORT_PUBLISHED taskId={} documentId={} indexVersionId={} outcome={} elapsedMs={}",
                task.taskId(),
                task.documentId(),
                task.indexVersionId(),
                outcome,
                ImportEvents.elapsed(started));
    }

    private static void stage(String event, ClaimedTask task, Integer attemptCount, int count, long started) {
        ImportEvents.info(
                LOG,
                "event={} taskId={} documentId={} indexVersionId={} attemptCount={} chunkCount={} elapsedMs={}",
                event,
                task.taskId(),
                task.documentId(),
                task.indexVersionId(),
                attemptCount,
                count,
                ImportEvents.elapsed(started));
    }

    private static void invalid() {
        throw new TaskExecutionException(TaskExecutionException.Code.VALIDATION_FAILED);
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA256_UNAVAILABLE");
        }
    }
}
