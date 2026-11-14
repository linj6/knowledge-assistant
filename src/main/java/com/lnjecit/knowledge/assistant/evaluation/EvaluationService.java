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
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

/** 本地单题评估编排；不计算质量分数。 */
@Service
@ConditionalOnProperty(name = "app.evaluation.enabled", havingValue = "true")
public class EvaluationService {
    private static final Path GOLD = Path.of("src/test/resources/answer-evaluation-v1.json");
    private final RagService rag;
    private final PublishedVersionSnapshotService snapshots;
    private final DocumentMapper documents;
    private final Environment environment;
    private final JsonMapper json = JsonMapper.builder().build();
    private final Path root;

    @org.springframework.beans.factory.annotation.Autowired
    public EvaluationService(
            RagService rag,
            PublishedVersionSnapshotService snapshots,
            DocumentMapper documents,
            Environment environment) {
        this(rag, snapshots, documents, environment, Path.of("data/evaluation"));
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
        this.root = root;
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

    private Dataset dataset() throws Exception {
        return json.readerFor(Dataset.class)
                .without(tools.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .readValue(Files.readString(GOLD));
    }

    public Object datasets() throws Exception {
        return Map.of("dataset", dataset(), "sha256", hash(GOLD));
    }

    public Outcome run(Request request) throws Exception {
        if (!"answer-evaluation-v1".equals(request.dataset())
                || request.knowledgeBaseId() == null
                || request.knowledgeBaseId() <= 0)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST");
        String fingerprint = FrozenFingerprint.answer(json.readTree(Files.readString(GOLD)));
        if (!"57c202a788d20e86a2ed166776c77dab6e66bed8b4ae08a1a925806458a5dbca".equals(fingerprint)) {
            return new Outcome(null, "NOT_READY", "GOLD_FINGERPRINT_MISMATCH");
        }
        tools.jackson.databind.JsonNode frozen = json.readTree(Files.readString(GOLD));
        if (!fingerprint.equals(frozen.path("freezeFingerprint").asString())
                || frozen.path("datasetVersion").asInt() != 1)
            return new Outcome(null, "NOT_READY", "GOLD_IDENTITY_MISMATCH");
        for (tools.jackson.databind.JsonNode item : frozen.path("cases")) {
            if (!"CONFIRMED".equals(item.path("reviewStatus").asString()))
                return new Outcome(null, "NOT_READY", "GOLD_UNCONFIRMED");
            for (String key : List.of("requiredFacts", "optionalFacts"))
                for (tools.jackson.databind.JsonNode fact : item.path(key))
                    if (!"CONFIRMED".equals(fact.path("reviewStatus").asString()))
                        return new Outcome(null, "NOT_READY", "GOLD_UNCONFIRMED");
        }
        Dataset dataset = dataset();
        GoldCase selected = dataset.cases().stream()
                .filter(c -> c.caseId().equals(request.caseId()))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "UNKNOWN_CASE"));
        RetrievalStrategy strategy =
                request.retrievalStrategy() == null ? RetrievalStrategy.BM25 : request.retrievalStrategy();
        RetrievalParameters parameters =
                request.parameters() == null ? RetrievalParameters.defaults() : request.parameters();
        parameters.validate(strategy);
        String runId = UUID.randomUUID().toString();
        String status = "NOT_READY";
        String error = null;
        PublishedVersionSnapshot snapshot = null;
        RagService.RagExecutionResult trace = null;
        try {
            if (!"answer-evaluation-v1".equals(dataset.protocol())
                    || !"FROZEN".equals(dataset.freezeStatus())
                    || dataset.schemaVersion() != 1) throw new IllegalStateException("GOLD_NOT_FROZEN");
            // 原文字节和发布版本均需匹配；只凭 KB ID 或文件名不能确认适用性。
            snapshot = snapshots.read(request.knowledgeBaseId());
            if (snapshot.documents().isEmpty()) throw new IllegalStateException("KB_NOT_PUBLISHED");
            for (Source source : selected.sources()) {
                if (!hash(Path.of(source.documentPath())).equals(source.sourceSha256()))
                    throw new IllegalStateException("SOURCE_HASH_MISMATCH");
                boolean matched = false;
                for (PublishedDocument published : snapshot.documents()) {
                    Document document = documents.selectById(published.documentId());
                    if (document != null
                            && Objects.equals(document.getKnowledgeBaseId(), request.knowledgeBaseId())
                            && Objects.equals(document.getActiveVersionId(), published.indexVersionId())
                            && source.sourceSha256().equals(document.getContentHash())) matched = true;
                }
                if (!matched) throw new IllegalStateException("PUBLISHED_SOURCE_MISMATCH");
            }
            if (!request.authorizeModelCost()
                    || !environment.getProperty("assistant.model.enabled", Boolean.class, false)
                    || !environment.getProperty("assistant.model.online-authorized", Boolean.class, false))
                throw new IllegalStateException("MODEL_COST_NOT_AUTHORIZED");
            com.lnjecit.knowledge.assistant.infrastructure.config.ModelProperties model =
                    org.springframework.boot.context.properties.bind.Binder.get(environment)
                            .bind(
                                    "assistant.model",
                                    com.lnjecit.knowledge.assistant.infrastructure.config.ModelProperties.class)
                            .orElseThrow(() -> new IllegalStateException("MODEL_CONFIGURATION_INVALID"));
            try {
                com.lnjecit.knowledge.assistant.infrastructure.ai.ModelConfiguration.validateChat(model);
                if (strategy != RetrievalStrategy.BM25)
                    com.lnjecit.knowledge.assistant.infrastructure.ai.ModelConfiguration.validateEmbedding(model);
            } catch (RuntimeException invalid) {
                throw new IllegalStateException("MODEL_CONFIGURATION_INVALID");
            }
            // 暂不允许独立 rerank 供应商费用绕过单模型授权。
            if (strategy == RetrievalStrategy.HYBRID_RERANK) throw new IllegalStateException("RERANK_NOT_SUPPORTED");
            String expectedSnapshot = snapshot.snapshotHash();
            trace = rag.executeWithTrace(
                    request.knowledgeBaseId(), selected.question(), strategy, parameters, result -> {
                        if (!expectedSnapshot.equals(result.diagnostics().snapshotHash()))
                            throw new IllegalStateException("EXECUTION_SNAPSHOT_MISMATCH");
                    });
            status = "FAILED";
            if (trace.retrieval() == null
                    || !snapshot.snapshotHash()
                            .equals(trace.retrieval().diagnostics().snapshotHash()))
                error = "EXECUTION_SNAPSHOT_MISMATCH";
            else if (trace.failureType() != null
                    || trace.answer() == null
                    || trace.answer().status() == RagStatus.FAILED) error = "RAG_EXECUTION_FAILED";
            else status = "COMPLETED";
        } catch (Exception failure) {
            error = "PRECHECK_UNAVAILABLE";
            if (failure instanceof IllegalStateException
                    && Set.of(
                                    "GOLD_NOT_FROZEN",
                                    "KB_NOT_PUBLISHED",
                                    "SOURCE_HASH_MISMATCH",
                                    "PUBLISHED_SOURCE_MISMATCH",
                                    "MODEL_COST_NOT_AUTHORIZED",
                                    "MODEL_CONFIGURATION_INVALID",
                                    "RERANK_NOT_SUPPORTED")
                            .contains(failure.getMessage())) error = failure.getMessage();
        }
        // 临时目录不符合 Run ID 白名单，完整写入后在同一文件系统原子发布。
        Files.createDirectories(root);
        Path temporary = Files.createTempDirectory(root, ".pending-");
        Map<String, Object> results = new LinkedHashMap<>();
        results.put("case", selected);
        results.put("execution", trace);
        Files.writeString(temporary.resolve("results.json"), json.writeValueAsString(results));
        Files.writeString(
                temporary.resolve("report.md"),
                "# Evaluation Run\n\n" + status + "\n\nNo quality scores were calculated.\n");
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("runnerVersion", "m4-d03-answer-v1");
        manifest.put("schemaVersion", 1);
        manifest.put("runId", runId);
        manifest.put("createdAt", Instant.now().toString());
        manifest.put("dataset", request.dataset());
        manifest.put("datasetSha256", fingerprint);
        manifest.put("configuredChatModel", environment.getProperty("assistant.model.chat-model"));
        manifest.put(
                "configuredEmbeddingModel",
                strategy == RetrievalStrategy.BM25 ? null : environment.getProperty("assistant.model.embedding-model"));
        manifest.put(
                "stage",
                trace == null
                        ? "PRECHECK"
                        : trace.context() == null
                                ? "RETRIEVAL"
                                : trace.answer() == null ? "GENERATION" : "ANSWER_RESULT");
        manifest.put("snapshot", snapshot);
        manifest.put("request", request);
        manifest.put("status", status);
        manifest.put("errorCode", error);
        manifest.put(
                "artifactSha256",
                Map.of(
                        "results.json",
                        hash(temporary.resolve("results.json")),
                        "report.md",
                        hash(temporary.resolve("report.md"))));
        Files.writeString(temporary.resolve("manifest.json"), json.writeValueAsString(manifest));
        Files.move(temporary, root.resolve(runId), StandardCopyOption.ATOMIC_MOVE);
        return new Outcome(runId, status, error);
    }

    static String hash(Path path) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
    }
}
