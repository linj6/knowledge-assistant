package com.lnjecit.knowledge.assistant.ingestion.upload;

import com.lnjecit.knowledge.assistant.common.error.BusinessException;
import com.lnjecit.knowledge.assistant.common.error.CommonErrorCode;
import com.lnjecit.knowledge.assistant.infrastructure.config.ChunkingProperties;
import com.lnjecit.knowledge.assistant.infrastructure.config.ModelProperties;
import com.lnjecit.knowledge.assistant.infrastructure.config.UploadProperties;
import com.lnjecit.knowledge.assistant.infrastructure.persistence.ImportTask;
import com.lnjecit.knowledge.assistant.infrastructure.storage.ObjectKeyPrefixes;
import com.lnjecit.knowledge.assistant.infrastructure.storage.OriginalFileStore;
import com.lnjecit.knowledge.assistant.ingestion.task.ImportEvents;
import com.lnjecit.knowledge.assistant.knowledge.KnowledgeBaseService;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.databind.json.JsonMapper;

@Service
public class DocumentImportService {
    private static final Logger LOG = LoggerFactory.getLogger(DocumentImportService.class);
    private final DocumentImportRepository repository;
    private final KnowledgeBaseService knowledge;
    private final OriginalFileStore store;
    private final UploadProperties upload;
    private final ChunkingProperties chunking;
    private final JsonMapper json;
    private final ModelProperties model;
    private final TransactionTemplate transaction;

    public DocumentImportService(
            DocumentImportRepository repository,
            KnowledgeBaseService knowledge,
            OriginalFileStore store,
            UploadProperties upload,
            ChunkingProperties chunking,
            JsonMapper json,
            ModelProperties model,
            PlatformTransactionManager manager) {
        this.repository = repository;
        this.knowledge = knowledge;
        this.store = store;
        this.upload = upload;
        this.chunking = chunking;
        this.json = json;
        this.model = model;
        transaction = new TransactionTemplate(manager);
    }

    public record Outcome(ImportTaskResponse task, boolean duplicate) {}

    public Outcome receive(Long kbId, MultipartFile file) throws Exception {
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Upload cannot execute inside transaction");
        long started = System.nanoTime();
        knowledge.get(kbId);
        ValidatedUpload upload = validateAndRead(file);
        String filename = upload.filename();
        byte[] bytes = upload.bytes();
        String hash =
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        ImportTask existing = repository.findExisting(kbId, hash);
        if (existing != null) return outcome(existing, true, started);
        // 在上传前校验并捕获模型参数，后续配置变化不得改写该版本快照。
        String embeddingModel = model.getEmbeddingModel();
        Integer dimensions = model.getDimensions();
        if (embeddingModel == null || embeddingModel.isBlank() || dimensions == null || dimensions <= 0) {
            throw new IllegalStateException("Invalid embedding model configuration");
        }
        String snapshot = json.writeValueAsString(chunking.snapshot());
        String stagingKey = ObjectKeyPrefixes.STAGING + UUID.randomUUID();
        String finalKey = ObjectKeyPrefixes.ORIGINALS + UUID.randomUUID();
        // 上传、复制和补偿均在 PG 事务之外；每个请求只处理自己的随机 Key。
        try {
            uploadOriginal(stagingKey, finalKey, bytes);
            return createTask(kbId, filename, hash, finalKey, snapshot, embeddingModel, dimensions, started);
        } finally {
            // 临时对象删除失败不改变已经提交的业务结果，残留交给 staging 生命周期规则。
            cleanup(stagingKey);
        }
    }

    private void uploadOriginal(String stagingKey, String finalKey, byte[] bytes) {
        try {
            store.put(stagingKey, bytes);
            store.copy(stagingKey, finalKey);
        } catch (RuntimeException ex) {
            // 复制可能已经完成但响应丢失，仍须补偿本请求的目标对象。
            cleanup(finalKey);
            throw ex;
        }
    }

    private record CreatedImport(ImportTask task, boolean duplicate) {}

    private Outcome createTask(
            Long kbId,
            String filename,
            String hash,
            String finalKey,
            String snapshot,
            String embeddingModel,
            int dimensions,
            long started) {
        try {
            CreatedImport result = transaction.execute(status -> {
                // 同 KB 创建先锁知识库，锁内重复内容优先于容量拒绝。
                repository.lockCapacity(kbId);
                ImportTask existing = repository.findExisting(kbId, hash);
                if (existing != null) return new CreatedImport(existing, true);
                repository.checkCapacity(kbId);
                return new CreatedImport(
                        repository.create(kbId, filename, hash, finalKey, snapshot, embeddingModel, dimensions), false);
            });
            // 锁内去重的请求未使用自己的正式对象，提交后按原引用保护规则补偿。
            if (result.duplicate()) cleanupUnreferencedOriginal(finalKey);
            return outcome(result.task(), result.duplicate(), started);
        } catch (RuntimeException ex) {
            // 提交异常不等于已回滚，必须先查主库是否引用了对象，再决定是否删除。
            cleanupUnreferencedOriginal(finalKey);
            if (ex instanceof DuplicateKeyException) {
                ImportTask winner = repository.findExisting(kbId, hash);
                if (winner != null) return outcome(winner, true, started);
            }
            throw ex;
        }
    }

    private void cleanupUnreferencedOriginal(String finalKey) {
        try {
            if (!repository.isObjectReferenced(finalKey)) cleanup(finalKey);
            else ImportEvents.warn(LOG, "code=ORIGINAL_OBJECT_REFERENCED_AFTER_COMMIT_ERROR objectKey={}", finalKey);
        } catch (RuntimeException checkFailure) {
            com.lnjecit.knowledge.assistant.infrastructure.logging.FailureLog.error(
                    LOG, "DocumentImportService", checkFailure);
            // 查询本身失败时保留对象，避免误删已经提交的业务文件。
            ImportEvents.warn(LOG, "code=ORIGINAL_OBJECT_REFERENCE_UNKNOWN objectKey={}", finalKey);
        }
    }

    private record ValidatedUpload(String filename, byte[] bytes) {}

    private ValidatedUpload validateAndRead(MultipartFile file) throws Exception {
        String filename = file.getOriginalFilename();
        if (filename == null
                || filename.isBlank()
                || filename.length() > 255
                || filename.contains("/")
                || filename.contains("\\")
                || filename.codePoints().anyMatch(Character::isISOControl)
                || !(filename.toLowerCase(Locale.ROOT).endsWith(".md")
                        || filename.toLowerCase(Locale.ROOT).endsWith(".txt"))) invalid();
        if (file.isEmpty() || file.getSize() > upload.getMaxFileBytes()) invalid();
        byte[] bytes;
        // 按上限读取，避免依赖上传方声明的大小；BOM 原样保留且纳入哈希。
        try (java.io.InputStream stream = file.getInputStream()) {
            bytes = stream.readNBytes(upload.getMaxFileBytes() + 1);
        }
        if (bytes.length == 0 || bytes.length > upload.getMaxFileBytes()) invalid();
        try {
            StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes));
        } catch (java.nio.charset.CharacterCodingException ex) {
            invalid();
        }
        return new ValidatedUpload(filename, bytes);
    }

    private Outcome outcome(ImportTask task, boolean duplicate, long started) {
        ImportEvents.info(
                LOG,
                "event={} taskId={} documentId={} indexVersionId={} attemptCount={} elapsedMs={}",
                duplicate ? "IMPORT_DUPLICATE" : "IMPORT_RECEIVED",
                task.getId(),
                task.getDocumentId(),
                task.getIndexVersionId(),
                task.getAttemptCount(),
                ImportEvents.elapsed(started));
        return new Outcome(ImportTaskResponse.from(task), duplicate);
    }

    private void cleanup(String key) {
        try {
            store.delete(key);
        } catch (RuntimeException ex) {
            com.lnjecit.knowledge.assistant.infrastructure.logging.FailureLog.error(LOG, "MINIO_COMPENSATION", ex);
            ImportEvents.warn(
                    LOG,
                    "code={} objectKey={}",
                    key.startsWith(ObjectKeyPrefixes.ORIGINALS)
                            ? "ORIGINAL_OBJECT_CLEANUP_FAILED"
                            : "STAGING_OBJECT_CLEANUP_FAILED",
                    key);
        }
    }

    private static void invalid() {
        throw new BusinessException(CommonErrorCode.INVALID_ARGUMENT);
    }

    public ImportTaskResponse get(Long id) {
        ImportTask task = repository.find(id);
        if (task == null) throw new BusinessException(CommonErrorCode.NOT_FOUND);
        return ImportTaskResponse.from(task);
    }
}
