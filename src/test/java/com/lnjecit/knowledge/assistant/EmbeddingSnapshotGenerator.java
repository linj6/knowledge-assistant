package com.lnjecit.knowledge.assistant;

import com.lnjecit.knowledge.assistant.infrastructure.ai.ModelResponseChecks;
import com.lnjecit.knowledge.assistant.retrieval.embedding.QueryVector;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 测试目录独立入口，不由 Spring 或默认测试自动运行。 */
public final class EmbeddingSnapshotGenerator {
    static final JsonMapper JSON = JsonMapper.builder().build();

    enum Mode {
        DRY_RUN,
        EXECUTE
    }

    record Budget(
            boolean authorized,
            String model,
            String apiKey,
            int maxInputs,
            int maxRequests,
            long maxTokens,
            Map<String, Long> verifiedTokenCounts) {}

    record Dataset(EmbeddingSnapshot.Manifest manifest, Map<EmbeddingSnapshot.Input, String> inputs) {}

    record Plan(
            int logical,
            int unique,
            int existing,
            int pending,
            int batchSize,
            int requests,
            String target,
            String inputFingerprint,
            String configFingerprint) {}

    static Dataset corpus() throws Exception {
        Map<EmbeddingSnapshot.Input, String> inputs = new LinkedHashMap<>();
        for (RetrievalExperimentCorpus.Document document : RetrievalExperimentCorpus.load()) {
            String source = Files.readString(Path.of(document.path()));
            for (String strategy : List.of("FIXED_CODE_POINT", "MARKDOWN_STRUCTURE_AWARE")) {
                List<MarkdownStructureExperiment.Piece> pieces =
                        strategy.equals("FIXED_CODE_POINT") ? document.fixed() : document.structure();
                for (int i = 0; i < pieces.size(); i++) {
                    MarkdownStructureExperiment.Piece piece = pieces.get(i);
                    if (!piece.span().extract(source).equals(piece.sourceContent()))
                        throw new IllegalStateException("CHUNK_CONTENT_CHANGED");
                    inputs.put(
                            new EmbeddingSnapshot.Input(
                                    "CHUNK",
                                    null,
                                    document.sha(),
                                    strategy,
                                    i,
                                    piece.span().start(),
                                    piece.span().end(),
                                    EmbeddingSnapshot.sha(piece.sourceContent())),
                            piece.sourceContent());
                }
            }
        }
        JsonNode questions = JSON.readTree(Files.readString(Path.of("docs/engineering/m4-01-gold-evidence.json")));
        JsonNode protocol = JSON.readTree(Files.readString(Path.of("docs/engineering/m4-02c1-gold-protocol.json")));
        int queryIndex = 0;
        for (JsonNode q : questions) {
            String text = q.path("question").asString();
            JsonNode mapping = protocol.path("questions").get(queryIndex++);
            if (!mapping.path("questionId")
                            .asString()
                            .equals(q.path("questionId").asString())
                    || !mapping.path("querySha256").asString().equals(EmbeddingSnapshot.sha(text)))
                throw new IllegalStateException("QUERY_PROTOCOL_MISMATCH");
            inputs.put(
                    new EmbeddingSnapshot.Input(
                            "QUERY",
                            q.path("questionId").asString(),
                            null,
                            null,
                            null,
                            null,
                            null,
                            EmbeddingSnapshot.sha(text)),
                    text);
        }
        if (questions.size() != 12 || inputs.size() != 153) throw new IllegalStateException("INPUT_COUNT_CHANGED");
        String fingerprint = EmbeddingSnapshot.sha(JSON.writeValueAsString(inputs.keySet()));
        if (!fingerprint.equals("d8080d19731b571a32985c3889c398138fb1c661bfef780bc8994c54398492d8"))
            throw new IllegalStateException("FROZEN_INPUT_FINGERPRINT_CHANGED actual=" + fingerprint);
        return new Dataset(
                new EmbeddingSnapshot.Manifest(
                        1,
                        "dashscope",
                        "text-embedding-v4",
                        1024,
                        "FLOAT",
                        EmbeddingSnapshot.sha("dashscope-compatible-v1;text-embedding-v4;1024;FLOAT;maxRetries=0"),
                        fingerprint,
                        EmbeddingSnapshot.sha("FIXED_CODE_POINT:800/120;MARKDOWN_STRUCTURE_AWARE:M4-01")),
                inputs);
    }

    static Plan run(
            Dataset data,
            Path target,
            int batchSize,
            Mode mode,
            Budget budget,
            Supplier<? extends EmbeddingModel> factory)
            throws Exception {
        if (data.manifest().schemaVersion() != 1
                || data.manifest().dimensions() != 1024
                || !"text-embedding-v4".equals(data.manifest().modelId())
                || !"FLOAT".equals(data.manifest().encoding()))
            throw new IllegalArgumentException("MANIFEST_MODEL_MISMATCH");
        if (batchSize < 1 || batchSize > 10) throw new IllegalArgumentException("BATCH_SIZE");
        target = target.toAbsolutePath().normalize();
        if (Files.isSymbolicLink(target) || (Files.exists(target) && !Files.isDirectory(target)))
            throw new IllegalArgumentException("TARGET_PATH");
        Map<String, String> unique = new LinkedHashMap<>();
        data.inputs().forEach((input, text) -> {
            if (!EmbeddingSnapshot.sha(text).equals(input.inputSha256()))
                throw new IllegalArgumentException("INPUT_SHA");
            String previous = unique.putIfAbsent(input.inputSha256(), text);
            if (previous != null && !previous.equals(text)) throw new IllegalArgumentException("HASH_COLLISION");
        });
        Map<EmbeddingSnapshot.Input, String> present = new LinkedHashMap<>();
        Path manifest = target.resolve("manifest.json");
        Path vectors = target.resolve("vectors.jsonl");
        Map<String, float[]> saved = new LinkedHashMap<>();
        if (Files.exists(vectors)) {
            for (String line : Files.readAllLines(vectors)) {
                EmbeddingSnapshot.Entry entry = JSON.readValue(line, EmbeddingSnapshot.Entry.class);
                if (!data.inputs().containsKey(entry.input())) throw new IllegalArgumentException("UNKNOWN_INPUT");
                present.put(entry.input(), data.inputs().get(entry.input()));
            }
            // 复用完整加载校验，但期望集合仅限检查点内已有输入；正式加载仍要求全部输入。
            EmbeddingSnapshot.load(manifest, vectors, data.manifest(), present).forEach((input, vector) -> {
                float[] previous = saved.putIfAbsent(input.inputSha256(), vector.values());
                if (previous != null && !java.util.Arrays.equals(previous, vector.values()))
                    throw new IllegalArgumentException("TEXT_VECTOR_CONFLICT");
            });
        } else if (Files.exists(manifest)
                && !JSON.readValue(Files.readString(manifest), EmbeddingSnapshot.Manifest.class)
                        .equals(data.manifest())) {
            throw new IllegalArgumentException("MANIFEST_MISMATCH");
        }
        List<String> missing = unique.keySet().stream()
                .filter(hash -> !saved.containsKey(hash))
                .toList();
        int requests = (missing.size() + batchSize - 1) / batchSize;
        Plan plan = new Plan(
                data.inputs().size(),
                unique.size(),
                saved.size(),
                missing.size(),
                batchSize,
                requests,
                target.toString(),
                data.manifest().sampleFingerprint(),
                data.manifest().configFingerprint());
        System.out.println(JSON.writeValueAsString(plan));
        if (mode == Mode.DRY_RUN) return plan;
        // 所有授权与整次运行预算检查均先于客户端创建及任何请求。
        if (budget == null || !budget.authorized()) throw new IllegalArgumentException("ONLINE_AUTHORIZATION_REQUIRED");
        if (!"text-embedding-v4".equals(budget.model())) throw new IllegalArgumentException("MODEL_MISMATCH");
        if (budget.apiKey() == null || budget.apiKey().isBlank())
            throw new IllegalArgumentException("API_KEY_REQUIRED");
        if (budget.maxInputs() < missing.size() || budget.maxRequests() < requests || budget.maxTokens() <= 0)
            throw new IllegalArgumentException("BUDGET_EXCEEDED");
        long tokens = 0;
        for (String hash : missing) {
            Long count = budget.verifiedTokenCounts() == null
                    ? null
                    : budget.verifiedTokenCounts().get(hash);
            if (count == null || count <= 0) throw new IllegalArgumentException("VERIFIED_TOKEN_BUDGET_REQUIRED");
            tokens = Math.addExact(tokens, count);
        }
        if (tokens > budget.maxTokens()) throw new IllegalArgumentException("TOKEN_BUDGET_EXCEEDED");
        EmbeddingModel model = factory.get();
        validateClient(model);
        Files.createDirectories(target);
        atomic(manifest, JSON.writeValueAsString(data.manifest()));
        for (int start = 0; start < missing.size(); start += batchSize) {
            List<String> hashes = missing.subList(start, Math.min(start + batchSize, missing.size()));
            List<String> texts = hashes.stream().map(unique::get).toList();
            try {
                EmbeddingResponse response = model.call(new EmbeddingRequest(texts, null));
                ModelResponseChecks.embeddings(response, texts.size(), 1024);
                Map<String, float[]> batch = new LinkedHashMap<>();
                for (Embedding result : response.getResults())
                    batch.put(hashes.get(result.getIndex()), new QueryVector(result.getOutput()).values());
                Map<String, float[]> next = new LinkedHashMap<>(saved);
                next.putAll(batch);
                StringBuilder lines = new StringBuilder();
                for (EmbeddingSnapshot.Input input : data.inputs().keySet())
                    if (next.containsKey(input.inputSha256()))
                        lines.append(JSON.writeValueAsString(
                                        new EmbeddingSnapshot.Entry(input, next.get(input.inputSha256()))))
                                .append('\n');
                atomic(vectors, lines.toString());
                saved.putAll(batch);
            } catch (Exception failure) {
                atomic(
                        target.resolve("failure.json"),
                        "{\"status\":\"STOPPED_UNCERTAIN\",\"attemptedRequests\":" + (start / batchSize + 1) + "}");
                throw new IllegalStateException("BATCH_STOPPED_RECHECK_BUDGET");
            }
        }
        EmbeddingSnapshot.load(manifest, vectors, data.manifest(), data.inputs());
        Files.deleteIfExists(target.resolve("failure.json"));
        return plan;
    }

    /** 供人工审查后的专用实验入口调用；不加载 Spring 上下文。Token 计数须由调用方提供已核验结果。 */
    static Plan executeOnline(
            Dataset data,
            Path target,
            int batchSize,
            Budget budget,
            com.lnjecit.knowledge.assistant.infrastructure.config.ModelProperties properties)
            throws Exception {
        if (!EmbeddingBaselineOnlineIT.ENDPOINT.equals(properties.getBaseUrl())
                || !budget.apiKey().equals(properties.getApiKey())
                || !"text-embedding-v4".equals(properties.getEmbeddingModel())
                || !Integer.valueOf(1024).equals(properties.getDimensions()))
            throw new IllegalArgumentException("ONLINE_PROPERTIES_MISMATCH");
        return run(
                data,
                target,
                batchSize,
                Mode.EXECUTE,
                budget,
                () -> new com.lnjecit.knowledge.assistant.infrastructure.ai.ModelConfiguration()
                        .embeddingModel(properties));
    }

    static void validateClient(EmbeddingModel model) {
        if (!(model instanceof OpenAiEmbeddingModel client)) throw new IllegalArgumentException("CLIENT_TYPE");
        OpenAiEmbeddingOptions options = client.getOptions();
        if (!"text-embedding-v4".equals(options.getModel())
                || !Integer.valueOf(1024).equals(options.getDimensions())
                || !Integer.valueOf(0).equals(options.getMaxRetries())
                || options.getEncodingFormat() != OpenAiEmbeddingOptions.EncodingFormat.FLOAT)
            throw new IllegalArgumentException("CLIENT_CONFIGURATION_MISMATCH");
    }

    static void atomic(Path path, String text) throws Exception {
        Path temporary = Files.createTempFile(path.getParent(), ".embedding-", ".tmp");
        try {
            Files.writeString(temporary, text);
            Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    public static void main(String[] args) throws Exception {
        Mode mode = Mode.valueOf(System.getProperty("snapshot.mode", "DRY_RUN"));
        Path target = Path.of(System.getProperty("snapshot.target", "target/m4-02c1/embedding"));
        Dataset data = corpus();
        // 当前无已验证的 v4 本地 Token 计数器，命令行 EXECUTE 必须拒绝，不能虚构预算。
        if (mode == Mode.EXECUTE)
            throw new IllegalStateException("VERIFIED_TOKEN_COUNTER_REQUIRED; use reviewed programmatic Budget");
        run(data, target, Integer.getInteger("snapshot.batchSize", 10), mode, null, () -> {
            throw new IllegalStateException("DRY_RUN_CLIENT_FORBIDDEN");
        });
    }
}
