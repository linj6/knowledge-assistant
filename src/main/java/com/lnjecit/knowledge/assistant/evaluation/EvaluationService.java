package com.lnjecit.knowledge.assistant.evaluation;

import com.lnjecit.knowledge.assistant.document.entity.Document;
import com.lnjecit.knowledge.assistant.document.mapper.DocumentMapper;
import com.lnjecit.knowledge.assistant.rag.RagService;
import com.lnjecit.knowledge.assistant.rag.model.RagStatus;
import com.lnjecit.knowledge.assistant.retrieval.model.RetrievalParameters;
import com.lnjecit.knowledge.assistant.retrieval.model.RetrievalStrategy;
import com.lnjecit.knowledge.assistant.retrieval.snapshot.PublishedDocument;
import com.lnjecit.knowledge.assistant.retrieval.snapshot.PublishedVersionSnapshot;
import com.lnjecit.knowledge.assistant.retrieval.snapshot.PublishedVersionSnapshotService;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** 本地单题评估编排；不计算质量分数。 */
@Service
@ConditionalOnProperty(name = "app.evaluation.enabled", havingValue = "true")
public class EvaluationService {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(EvaluationService.class);

    private final RagService rag;
    private final PublishedVersionSnapshotService snapshots;
    private final DocumentMapper documents;
    private final Environment environment;
    private final EvaluationRunStore store;

    @org.springframework.beans.factory.annotation.Autowired
    public EvaluationService(
            RagService rag,
            PublishedVersionSnapshotService snapshots,
            DocumentMapper documents,
            Environment environment) {
        this(rag, snapshots, documents, environment, EvaluationFiles.ROOT);
    }

    EvaluationService(
            RagService rag,
            PublishedVersionSnapshotService snapshots,
            DocumentMapper documents,
            Environment environment,
            Path root) {
        this.rag = rag;
        this.snapshots = snapshots;
        this.documents = documents;
        this.environment = environment;
        this.store = new EvaluationRunStore(root);
    }

    public record Source(String documentPath, String sourceSha256) {}

    public record GoldCase(String caseId, String question, List<Source> sources) {}

    public record Dataset(String protocol, int schemaVersion, String freezeStatus, List<GoldCase> cases) {}

    public record Request(
            String dataset,
            String caseId,
            Long knowledgeBaseId,
            RetrievalStrategy retrievalStrategy,
            RetrievalParameters parameters,
            boolean authorizeModelCost) {}

    public record Outcome(String runId, String status, String errorCode) {}

    private final EvaluationDatasetLoader loader = new EvaluationDatasetLoader();

    public EvaluationDatasetLoader.Description datasets() throws Exception {
        return loader.describe();
    }

    public Outcome run(Request request) throws Exception {
        if (!EvaluationFiles.ANSWER_PROTOCOL.equals(request.dataset())
                || request.knowledgeBaseId() == null
                || request.knowledgeBaseId() <= 0)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST");
        EvaluationDatasetLoader.Loaded loaded = loader.load();
        if (loaded.error() != null) return new Outcome(null, RunStatus.NOT_READY.name(), loaded.error());
        String fingerprint = loaded.fingerprint();
        Dataset dataset = loaded.dataset();
        GoldCase selected = dataset.cases().stream()
                .filter(c -> c.caseId().equals(request.caseId()))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "UNKNOWN_CASE"));
        RetrievalStrategy strategy =
                request.retrievalStrategy() == null ? RetrievalStrategy.BM25 : request.retrievalStrategy();
        RetrievalParameters parameters =
                request.parameters() == null ? RetrievalParameters.defaults() : request.parameters();
        try {
            parameters.validate(strategy);
        } catch (IllegalArgumentException invalid) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_RETRIEVAL_PARAMETERS");
        }
        String runId = UUID.randomUUID().toString();
        String status = RunStatus.NOT_READY.name();
        String error = null;
        PublishedVersionSnapshot snapshot = null;
        RagService.RagExecutionResult trace = null;
        try {
            snapshot = precheck(request, selected, strategy);
            String expectedSnapshot = snapshot.snapshotHash();
            trace = rag.executeWithTrace(
                    request.knowledgeBaseId(), selected.question(), strategy, parameters, result -> {
                        if (!expectedSnapshot.equals(result.diagnostics().snapshotHash()))
                            throw new PrecheckRejected("EXECUTION_SNAPSHOT_MISMATCH");
                    });
            status = RunStatus.FAILED.name();
            if (trace.retrieval() == null
                    || !snapshot.snapshotHash()
                            .equals(trace.retrieval().diagnostics().snapshotHash()))
                error = "EXECUTION_SNAPSHOT_MISMATCH";
            else if (trace.failureType() != null
                    || trace.answer() == null
                    || trace.answer().status() == RagStatus.FAILED) error = "RAG_EXECUTION_FAILED";
            else status = RunStatus.COMPLETED.name();
        } catch (PrecheckRejected rejected) {
            error = rejected.code;
        } catch (com.lnjecit.knowledge.assistant.common.error.BusinessException failure) {
            error = "KB_NOT_AVAILABLE";
        } catch (com.lnjecit.knowledge.assistant.retrieval.snapshot.SnapshotInvalidException failure) {
            error = "PUBLISHED_SNAPSHOT_INVALID";
        } catch (java.io.IOException failure) {
            error = "SOURCE_UNAVAILABLE";
        } catch (RuntimeException failure) {
            com.lnjecit.knowledge.assistant.infrastructure.logging.FailureLog.error(
                    log, "EVALUATION_PRECHECK errorCode=PRECHECK_UNAVAILABLE", failure);
            error = "PRECHECK_UNAVAILABLE";
        }
        save(runId, request, selected, strategy, fingerprint, snapshot, trace, status, error);
        return new Outcome(runId, status, error);
    }

    private void save(
            String runId,
            Request request,
            GoldCase selected,
            RetrievalStrategy strategy,
            String fingerprint,
            PublishedVersionSnapshot snapshot,
            RagService.RagExecutionResult trace,
            String status,
            String error) {
        AnswerEvaluationRun.Results results = new AnswerEvaluationRun.Results(selected, trace);
        String stage = trace == null
                ? "PRECHECK"
                : trace.context() == null ? "RETRIEVAL" : trace.answer() == null ? "GENERATION" : "ANSWER_RESULT";
        AnswerEvaluationRun.Manifest manifest = new AnswerEvaluationRun.Manifest(
                EvaluationFiles.RUN_PROTOCOL,
                1,
                runId,
                Instant.now().toString(),
                request.dataset(),
                fingerprint,
                environment.getProperty("assistant.model.chat-model"),
                strategy == RetrievalStrategy.BM25 ? null : environment.getProperty("assistant.model.embedding-model"),
                stage,
                snapshot,
                request,
                RunStatus.valueOf(status),
                error,
                Map.of());
        store.save(manifest, results);
    }

    /** 发布身份、来源字节和费用授权全部通过后才允许进入执行链。 */
    private PublishedVersionSnapshot precheck(Request request, GoldCase selected, RetrievalStrategy strategy)
            throws java.io.IOException {
        // 原文字节和发布版本均需匹配；只凭 KB ID 或文件名不能确认适用性。
        PublishedVersionSnapshot snapshot = snapshots.read(request.knowledgeBaseId());
        if (snapshot.documents().isEmpty()) throw new PrecheckRejected("KB_NOT_PUBLISHED");
        for (Source source : selected.sources()) {
            if (!hash(Path.of(source.documentPath())).equals(source.sourceSha256()))
                throw new PrecheckRejected("SOURCE_HASH_MISMATCH");
            boolean matched = false;
            for (PublishedDocument published : snapshot.documents()) {
                Document document = documents.selectById(published.documentId());
                if (document != null
                        && Objects.equals(document.getKnowledgeBaseId(), request.knowledgeBaseId())
                        && Objects.equals(document.getActiveVersionId(), published.indexVersionId())
                        && source.sourceSha256().equals(document.getContentHash())) matched = true;
            }
            if (!matched) throw new PrecheckRejected("PUBLISHED_SOURCE_MISMATCH");
        }
        if (!request.authorizeModelCost()
                || !environment.getProperty("assistant.model.enabled", Boolean.class, false)
                || !environment.getProperty("assistant.model.online-authorized", Boolean.class, false))
            throw new PrecheckRejected("MODEL_COST_NOT_AUTHORIZED");
        com.lnjecit.knowledge.assistant.infrastructure.config.ModelProperties model =
                org.springframework.boot.context.properties.bind.Binder.get(environment)
                        .bind(
                                "assistant.model",
                                com.lnjecit.knowledge.assistant.infrastructure.config.ModelProperties.class)
                        .orElseThrow(() -> new PrecheckRejected("MODEL_CONFIGURATION_INVALID"));
        try {
            com.lnjecit.knowledge.assistant.infrastructure.ai.ModelConfiguration.validateChat(model);
            if (strategy != RetrievalStrategy.BM25)
                com.lnjecit.knowledge.assistant.infrastructure.ai.ModelConfiguration.validateEmbedding(model);
        } catch (RuntimeException invalid) {
            throw new PrecheckRejected("MODEL_CONFIGURATION_INVALID");
        }
        // 暂不允许独立 rerank 供应商费用绕过单模型授权。
        if (strategy == RetrievalStrategy.HYBRID_RERANK) throw new PrecheckRejected("RERANK_NOT_SUPPORTED");
        return snapshot;
    }

    private static final class PrecheckRejected extends RuntimeException {
        private final String code;

        private PrecheckRejected(String code) {
            this.code = code;
        }
    }

    static String hash(Path path) throws java.io.IOException {
        return Sha256.file(path);
    }
}
