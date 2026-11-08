package com.lnjecit.knowledge.assistant.ingestion;

import com.lnjecit.knowledge.assistant.common.error.BusinessException;
import com.lnjecit.knowledge.assistant.common.error.CommonErrorCode;
import com.lnjecit.knowledge.assistant.infrastructure.config.ChunkingProperties;
import com.lnjecit.knowledge.assistant.infrastructure.config.UploadProperties;
import com.lnjecit.knowledge.assistant.infrastructure.persistence.ImportTask;
import com.lnjecit.knowledge.assistant.infrastructure.storage.OriginalFileStore;
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
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.databind.json.JsonMapper;

@Service
public class ImportService {
    private static final Logger LOG = LoggerFactory.getLogger(ImportService.class);
    private final ImportRepository repository;
    private final KnowledgeBaseService knowledge;
    private final OriginalFileStore store;
    private final UploadProperties upload;
    private final ChunkingProperties chunking;
    private final JsonMapper json;
    private final TransactionTemplate transaction;

    public ImportService(
            ImportRepository repository,
            KnowledgeBaseService knowledge,
            OriginalFileStore store,
            UploadProperties upload,
            ChunkingProperties chunking,
            JsonMapper json,
            PlatformTransactionManager manager) {
        this.repository = repository;
        this.knowledge = knowledge;
        this.store = store;
        this.upload = upload;
        this.chunking = chunking;
        this.json = json;
        transaction = new TransactionTemplate(manager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public record Outcome(ImportTaskResponse task, boolean duplicate) {}

    public Outcome receive(Long kbId, MultipartFile file) throws Exception {
        knowledge.get(kbId);
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
        String hash =
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        ImportTask existing = repository.findExisting(kbId, hash);
        if (existing != null) return new Outcome(ImportTaskResponse.from(existing), true);
        String snapshot = json.writeValueAsString(chunking.snapshot());
        String key = "originals/" + UUID.randomUUID();
        // 网络上传在数据库事务之外；只补偿本请求生成的唯一对象 Key。
        try {
            store.put(key, bytes);
        } catch (RuntimeException ex) {
            cleanup(key);
            throw ex;
        }
        try {
            ImportTask task = transaction.execute(status -> repository.create(kbId, filename, hash, key, snapshot));
            return new Outcome(ImportTaskResponse.from(task), false);
        } catch (RuntimeException ex) {
            cleanup(key);
            // execute 抛出时新事务已回滚，再查询并发赢家，不能在失败事务内查询。
            if (ex instanceof DuplicateKeyException) {
                ImportTask winner = repository.findExisting(kbId, hash);
                if (winner != null) return new Outcome(ImportTaskResponse.from(winner), true);
            }
            throw ex;
        }
    }

    private void cleanup(String key) {
        try {
            store.delete(key);
        } catch (RuntimeException ex) {
            LOG.warn("code=ORIGINAL_OBJECT_CLEANUP_FAILED objectKey={}", key);
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
