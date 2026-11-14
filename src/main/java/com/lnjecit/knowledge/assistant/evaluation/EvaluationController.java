package com.lnjecit.knowledge.assistant.evaluation;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 本地只读工作台；默认关闭，固定目录和文件白名单。 */
@RestController
@RequestMapping("/v1/evaluation/runs")
@ConditionalOnProperty(name = "app.evaluation.enabled", havingValue = "true")
public class EvaluationController {
    private final Path root;

    @org.springframework.beans.factory.annotation.Autowired
    public EvaluationController() {
        this(Path.of("data/evaluation"));
    }

    EvaluationController(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    private String hash(Path file) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
    }

    private final JsonMapper json = JsonMapper.builder().build();

    @GetMapping
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

    @GetMapping("/{runId}")
    public Map<String, Object> read(@PathVariable String runId) {
        if (!runId.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,99}")) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        try {
            Path directory = root.resolve(runId);
            if (!directory.toRealPath().getParent().equals(root.toRealPath())) throw new IllegalArgumentException();
            JsonNode header = json.readTree(Files.readString(directory.resolve("manifest.json")));
            if ("m4-d03-answer-v1".equals(header.path("runnerVersion").asString())) {
                if (header.path("schemaVersion").asInt() != 1
                        || !runId.equals(header.path("runId").asString())
                        || !List.of("NOT_READY", "FAILED", "COMPLETED")
                                .contains(header.path("status").asString())) throw new IllegalArgumentException();
                for (String name : List.of("results.json", "report.md")) {
                    Path artifact = directory.resolve(name);
                    if (!artifact.toRealPath().getParent().equals(directory.toRealPath())
                            || !hash(artifact)
                                    .equals(header.path("artifactSha256")
                                            .path(name)
                                            .asString())) throw new IllegalArgumentException();
                }
                return Map.of(
                        "runId",
                        runId,
                        "manifest",
                        header,
                        "results",
                        json.readTree(Files.readString(directory.resolve("results.json"))));
            }
            for (String name : List.of("manifest.json", "metrics.json", "retrieval-results.jsonl", "report.md")) {
                Path file = directory.resolve(name);
                if (!file.toRealPath().getParent().equals(directory.toRealPath()) || !Files.isRegularFile(file))
                    throw new IllegalArgumentException();
            }
            JsonNode manifest = json.readTree(Files.readString(directory.resolve("manifest.json")));
            if (manifest.path("schemaVersion").asInt() != 1
                    || !List.of("OFFLINE", "LIVE")
                            .contains(manifest.path("mode").asString())) throw new IllegalArgumentException();
            if (!List.of("m4-c01-offline-v1", "m4-c02-live-v1")
                    .contains(manifest.path("runnerVersion").asString()))
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "UNSUPPORTED_PROTOCOL");
            for (String name : List.of("metrics.json", "retrieval-results.jsonl", "report.md")) {
                if (!hash(directory.resolve(name))
                        .equals(manifest.path("artifactSha256").path(name).asString()))
                    throw new ResponseStatusException(
                            HttpStatus.UNPROCESSABLE_ENTITY, "ARTIFACT_HASH_MISMATCH: " + name);
            }
            JsonNode metrics = json.readTree(Files.readString(directory.resolve("metrics.json")));
            List<JsonNode> results = new ArrayList<>();
            for (String line : Files.readAllLines(directory.resolve("retrieval-results.jsonl"))) {
                if (!line.isBlank()) results.add(json.readTree(line));
            }
            return Map.of(
                    "runId",
                    runId,
                    "manifest",
                    manifest,
                    "metrics",
                    metrics,
                    "results",
                    results,
                    "gold",
                    gold(manifest));
        } catch (ResponseStatusException invalid) {
            throw invalid;
        } catch (java.nio.file.NoSuchFileException missing) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "MISSING_FILE");
        } catch (Exception invalid) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "INVALID_RECORD_JSON_OR_PROTOCOL");
        }
    }
    // 只读取固定的冻结来源；不使用 manifest 提供的任意本地路径。
    private Map<String, Object> gold(JsonNode manifest) {
        try {
            Path fixturePath = Path.of("src/test/resources/retrieval-metrics-v1.json");
            JsonNode fixture = json.readTree(Files.readString(fixturePath));
            if (!hash(fixturePath)
                            .equals(manifest.path("inputSha256")
                                    .path(fixturePath.toString().replace('\\', '/'))
                                    .asString())
                    && !manifest.path("mode").asString().equals("LIVE")) throw new IllegalArgumentException();
            Path source = Path.of("docs/engineering/m4-01-gold-evidence.json");
            JsonNode questions = json.readTree(Files.readString(source));
            // 离线通过已校验 fixture 中的 query/source hash 和区间逐题绑定。
            List<JsonNode> verified = new ArrayList<>();
            for (JsonNode question : questions) {
                for (JsonNode frozen : fixture.path("gold").path("questions")) {
                    if (!question.path("questionId").equals(frozen.path("questionId"))) continue;
                    String queryHash = HexFormat.of()
                            .formatHex(MessageDigest.getInstance("SHA-256")
                                    .digest(question.path("question")
                                            .asString()
                                            .getBytes(java.nio.charset.StandardCharsets.UTF_8)));
                    if (!queryHash.equals(frozen.path("querySha256").asString())
                            || !question.path("sha256").equals(frozen.path("sourceSha256"))) continue;
                    if (!question.path("documentPath").equals(frozen.path("documentPath"))
                            || question.path("goldEvidence").size()
                                    != frozen.path("evidence").size()) continue;
                    boolean valid = true;
                    for (int i = 0; i < question.path("goldEvidence").size(); i++) {
                        if (!question.path("goldEvidence")
                                .get(i)
                                .path("span")
                                .equals(frozen.path("evidence").get(i).path("sourceSpan"))) valid = false;
                    }
                    // Gold 文本本身还必须匹配已记录的来源文件哈希。
                    Path document = Path.of(question.path("documentPath").asString());
                    if (!hash(document).equals(question.path("sha256").asString())) continue;
                    String text = Files.readString(document);
                    for (JsonNode evidence : question.path("goldEvidence")) {
                        int start = evidence.path("span").path("start").asInt();
                        int end = evidence.path("span").path("end").asInt();
                        if (!text.substring(text.offsetByCodePoints(0, start), text.offsetByCodePoints(0, end))
                                .equals(evidence.path("text").asString())) valid = false;
                    }
                    if (valid) verified.add(question);
                }
            }
            return Map.of("status", "SOURCE_VERIFIED", "questions", verified);
        } catch (Exception unavailable) {
            return Map.of("status", "SOURCE_MISSING_OR_HASH_MISMATCH", "questions", List.of());
        }
    }
}
