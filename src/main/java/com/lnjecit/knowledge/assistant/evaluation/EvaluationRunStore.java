package com.lnjecit.knowledge.assistant.evaluation;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 本地只读工作台；默认关闭，固定目录和文件白名单。 */
@org.springframework.stereotype.Component
@ConditionalOnProperty(name = "app.evaluation.enabled", havingValue = "true")
public class EvaluationRunStore {
    private final Path root;

    @org.springframework.beans.factory.annotation.Autowired
    public EvaluationRunStore() {
        this(EvaluationFiles.ROOT);
    }

    EvaluationRunStore(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    private String hash(Path file) throws Exception {
        return Sha256.file(file);
    }

    private final JsonMapper json = JsonMapper.builder().build();

    public List<Object> list() throws Exception {
        List<Object> result = new ArrayList<>();
        if (!Files.isDirectory(root)) return result;
        try (java.util.stream.Stream<Path> paths = Files.list(root)) {
            for (Path path : paths.sorted().toList()) {
                try {
                    result.add(read(path.getFileName().toString()));
                } catch (ResponseStatusException ignored) {
                    /* 不完整或损坏记录不作为有效 Run 展示。 */
                }
            }
        }
        return result;
    }

    public EvaluationRunResponse read(String runId) {
        if (!runId.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,99}")) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        try {
            Path directory = root.resolve(runId);
            if (!directory.toRealPath().getParent().equals(root.toRealPath())) throw new IllegalArgumentException();
            JsonNode header = json.readTree(Files.readString(directory.resolve(EvaluationFiles.MANIFEST)));
            if (EvaluationFiles.RUN_PROTOCOL.equals(header.path("runnerVersion").asString())) {
                AnswerEvaluationRun.Manifest manifest = json.treeToValue(header, AnswerEvaluationRun.Manifest.class);
                if (manifest.schemaVersion() != 1 || !runId.equals(manifest.runId()) || manifest.status() == null)
                    throw new IllegalArgumentException();
                for (String name : List.of(EvaluationFiles.RESULTS, EvaluationFiles.REPORT)) {
                    Path artifact = directory.resolve(name);
                    if (!artifact.toRealPath().getParent().equals(directory.toRealPath())
                            || !hash(artifact).equals(manifest.artifactSha256().get(name)))
                        throw new IllegalArgumentException();
                }
                return new AnswerEvaluationRun.Response(
                        runId,
                        manifest,
                        json.readValue(
                                Files.readString(directory.resolve(EvaluationFiles.RESULTS)),
                                AnswerEvaluationRun.Results.class));
            }

            for (String name : List.of(
                    EvaluationFiles.MANIFEST,
                    EvaluationFiles.METRICS,
                    EvaluationFiles.RETRIEVAL,
                    EvaluationFiles.REPORT)) {
                Path file = directory.resolve(name);
                if (!file.toRealPath().getParent().equals(directory.toRealPath()) || !Files.isRegularFile(file))
                    throw new IllegalArgumentException();
            }
            RetrievalRunData.RetrievalRunManifest manifest =
                    json.treeToValue(header, RetrievalRunData.RetrievalRunManifest.class);
            if (manifest.schemaVersion() != 1 || !List.of("OFFLINE", "LIVE").contains(manifest.mode()))
                throw new IllegalArgumentException();
            if (!List.of("m4-c01-offline-v1", "m4-c02-live-v1").contains(manifest.runnerVersion()))
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "UNSUPPORTED_PROTOCOL");
            for (String name : List.of(EvaluationFiles.METRICS, EvaluationFiles.RETRIEVAL, EvaluationFiles.REPORT))
                if (!hash(directory.resolve(name))
                        .equals(manifest.artifactSha256().get(name)))
                    throw new ResponseStatusException(
                            HttpStatus.UNPROCESSABLE_ENTITY, "ARTIFACT_HASH_MISMATCH: " + name);
            if (manifest.runnerVersion().equals("m4-c01-offline-v1")) {
                List<RetrievalRunData.OfflineRetrievalRunResult> results = new ArrayList<>();
                for (String line : Files.readAllLines(directory.resolve(EvaluationFiles.RETRIEVAL)))
                    if (!line.isBlank())
                        results.add(json.readValue(line, RetrievalRunData.OfflineRetrievalRunResult.class));
                return new OfflineResponse(
                        runId,
                        manifest,
                        json.readValue(
                                Files.readString(directory.resolve(EvaluationFiles.METRICS)),
                                RetrievalRunData.OfflineMetricsReport.class),
                        results,
                        gold(manifest));
            }
            List<RetrievalRunData.LiveRetrievalRunResult> results = new ArrayList<>();
            for (String line : Files.readAllLines(directory.resolve(EvaluationFiles.RETRIEVAL)))
                if (!line.isBlank()) results.add(json.readValue(line, RetrievalRunData.LiveRetrievalRunResult.class));
            return new LiveResponse(
                    runId,
                    manifest,
                    json.readValue(
                            Files.readString(directory.resolve(EvaluationFiles.METRICS)),
                            RetrievalRunData.LiveMetricsReport.class),
                    results,
                    gold(manifest));
        } catch (ResponseStatusException invalid) {
            throw invalid;
        } catch (java.nio.file.NoSuchFileException missing) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "MISSING_FILE");
        } catch (tools.jackson.core.JacksonException | IllegalArgumentException invalid) {
            throw new ResponseStatusException(
                    HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_RECORD_JSON_OR_PROTOCOL", invalid);
        } catch (java.io.IOException failure) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "RUN_READ_FAILED", failure);
        } catch (Exception failure) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "RUN_READ_FAILED", failure);
        }
    }

    record GoldResponse(String status, List<RetrievalRunData.GoldQuestion> questions) {}

    record OfflineResponse(
            String runId,
            RetrievalRunData.RetrievalRunManifest manifest,
            RetrievalRunData.OfflineMetricsReport metrics,
            List<RetrievalRunData.OfflineRetrievalRunResult> results,
            GoldResponse gold)
            implements EvaluationRunResponse {}

    record LiveResponse(
            String runId,
            RetrievalRunData.RetrievalRunManifest manifest,
            RetrievalRunData.LiveMetricsReport metrics,
            List<RetrievalRunData.LiveRetrievalRunResult> results,
            GoldResponse gold)
            implements EvaluationRunResponse {}

    /** 历史路径仅作为冻结输入哈希的键，不作为运行时加载位置。 */
    private GoldResponse gold(RetrievalRunData.RetrievalRunManifest manifest) {
        try {
            byte[] fixtureBytes = new org.springframework.core.io.ClassPathResource(
                            "evaluation/retrieval-metrics-v1.json")
                    .getContentAsByteArray();
            RetrievalRunData.RetrievalFixture fixture =
                    json.readValue(fixtureBytes, RetrievalRunData.RetrievalFixture.class);
            if (!manifest.mode().equals("LIVE")
                    && !Sha256.digest(fixtureBytes)
                            .equals(manifest.inputSha256().get("src/test/resources/retrieval-metrics-v1.json")))
                throw new IllegalArgumentException();
            RetrievalRunData.GoldQuestion[] questions = json.readValue(
                    new org.springframework.core.io.ClassPathResource("evaluation/m4-01-gold-evidence.json")
                            .getContentAsByteArray(),
                    RetrievalRunData.GoldQuestion[].class);
            List<RetrievalRunData.GoldQuestion> verified = new ArrayList<>();
            for (RetrievalRunData.GoldQuestion question : questions) {
                for (RetrievalRunData.GoldQuestionIdentity frozen :
                        fixture.gold().questions()) {
                    if (!question.questionId().equals(frozen.questionId())) continue;
                    if (!Sha256.digest(question.question().getBytes(java.nio.charset.StandardCharsets.UTF_8))
                                    .equals(frozen.querySha256())
                            || !question.sha256().equals(frozen.sourceSha256())
                            || !question.documentPath().equals(frozen.documentPath())
                            || question.goldEvidence().size()
                                    != frozen.evidence().size()) continue;
                    if (!Sha256.file(Path.of(question.documentPath())).equals(question.sha256())) continue;
                    String text = Files.readString(Path.of(question.documentPath()));
                    boolean valid = true;
                    for (int i = 0; i < question.goldEvidence().size(); i++) {
                        RetrievalRunData.GoldTextEvidence evidence =
                                question.goldEvidence().get(i);
                        if (!evidence.span().equals(frozen.evidence().get(i).sourceSpan())) valid = false;
                        int start = evidence.span().start().intValue(),
                                end = evidence.span().end().intValue();
                        if (!text.substring(text.offsetByCodePoints(0, start), text.offsetByCodePoints(0, end))
                                .equals(evidence.text())) valid = false;
                    }
                    if (valid) verified.add(question);
                }
            }
            return new GoldResponse("SOURCE_VERIFIED", verified);
        } catch (Exception unavailable) {
            return new GoldResponse("SOURCE_MISSING_OR_HASH_MISMATCH", List.of());
        }
    }
    /** 只有完整产物目录才会发布为可读 Run。 */
    void save(AnswerEvaluationRun.Manifest manifest, AnswerEvaluationRun.Results results) {
        try {
            Files.createDirectories(root);
            Path temporary = Files.createTempDirectory(root, ".pending-");
            Files.writeString(temporary.resolve(EvaluationFiles.RESULTS), json.writeValueAsString(results));
            Files.writeString(
                    temporary.resolve(EvaluationFiles.REPORT),
                    "# Evaluation Run\n\n" + manifest.status() + "\n\nNo quality scores were calculated.\n");
            manifest = manifest.withArtifacts(Map.of(
                    EvaluationFiles.RESULTS,
                    hash(temporary.resolve(EvaluationFiles.RESULTS)),
                    EvaluationFiles.REPORT,
                    hash(temporary.resolve(EvaluationFiles.REPORT))));
            Files.writeString(temporary.resolve(EvaluationFiles.MANIFEST), json.writeValueAsString(manifest));
            Files.move(temporary, root.resolve(manifest.runId()), java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception failure) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "RUN_PERSISTENCE_FAILED", failure);
        }
    }
}
