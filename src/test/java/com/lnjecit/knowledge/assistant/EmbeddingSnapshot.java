package com.lnjecit.knowledge.assistant;

import com.lnjecit.knowledge.assistant.retrieval.embedding.QueryVector;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.json.JsonMapper;

/** 实验专用离线协议；不持有模型客户端，不允许在线补齐。 */
final class EmbeddingSnapshot {
    static com.lnjecit.knowledge.assistant.retrieval.embedding.QueryEmbeddingService queries(
            Map<Input, QueryVector> loaded, Map<Input, String> inputs) {
        Map<String, QueryVector> queries = new HashMap<>();
        loaded.forEach((input, vector) -> {
            if ("QUERY".equals(input.inputType())) {
                QueryVector previous = queries.putIfAbsent(inputs.get(input), vector);
                if (previous != null && !java.util.Arrays.equals(previous.values(), vector.values()))
                    throw new IllegalArgumentException("SNAPSHOT_QUERY_CONFLICT");
            }
        });
        return new com.lnjecit.knowledge.assistant.retrieval.embedding.QueryEmbeddingService(null) {
            @Override
            public QueryVector embed(String query) {
                QueryVector vector = queries.get(query);
                if (vector == null) throw new IllegalArgumentException("OFFLINE_QUERY_MISSING");
                return vector;
            }
        };
    }

    record Manifest(
            int schemaVersion,
            String provider,
            String modelId,
            int dimensions,
            String encoding,
            String configFingerprint,
            String sampleFingerprint,
            String chunkingFingerprint) {}

    record Input(
            String inputType,
            String questionId,
            String documentSha256,
            String strategy,
            Integer chunkOrder,
            Integer sourceStart,
            Integer sourceEnd,
            String inputSha256) {}

    record Entry(Input input, float[] vector) {}

    static String sha(String text) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException failure) {
            throw new IllegalStateException(failure);
        }
    }

    static Map<Input, QueryVector> load(Path manifest, Path entries, Manifest expected, Map<Input, String> inputs)
            throws java.io.IOException {
        JsonMapper json = JsonMapper.builder().build();
        Manifest actual = json.readValue(Files.readString(manifest), Manifest.class);
        if (!actual.equals(expected)
                || actual.schemaVersion() != 1
                || actual.dimensions() != 1024
                || !"text-embedding-v4".equals(actual.modelId())
                || !"FLOAT".equals(actual.encoding())
                || List.of(
                                actual.provider(),
                                actual.configFingerprint(),
                                actual.sampleFingerprint(),
                                actual.chunkingFingerprint())
                        .stream()
                        .anyMatch(String::isBlank)) throw new IllegalArgumentException("SNAPSHOT_MANIFEST_MISMATCH");
        Map<Input, QueryVector> result = new HashMap<>();
        for (String line : Files.readAllLines(entries)) {
            Entry entry = json.readValue(line, Entry.class);
            Input input = entry.input();
            String text = inputs.get(input);
            if (text == null || !sha(text).equals(input.inputSha256()))
                throw new IllegalArgumentException("SNAPSHOT_INPUT_MISMATCH");
            if ("QUERY".equals(input.inputType())) {
                if (input.questionId() == null || input.questionId().isBlank())
                    throw new IllegalArgumentException("SNAPSHOT_QUERY_MAPPING");
            } else if (!"CHUNK".equals(input.inputType())
                    || input.documentSha256() == null
                    || input.strategy() == null
                    || input.chunkOrder() == null
                    || input.chunkOrder() < 0
                    || input.sourceStart() == null
                    || input.sourceEnd() == null
                    || input.sourceStart() < 0
                    || input.sourceEnd() <= input.sourceStart()) {
                throw new IllegalArgumentException("SNAPSHOT_CHUNK_MAPPING");
            }
            QueryVector vector = new QueryVector(entry.vector());
            QueryVector previous = result.putIfAbsent(input, vector);
            if (previous != null && !java.util.Arrays.equals(previous.values(), vector.values()))
                throw new IllegalArgumentException("SNAPSHOT_DUPLICATE_CONFLICT");
        }
        if (!result.keySet().equals(inputs.keySet())) throw new IllegalArgumentException("SNAPSHOT_MISSING");
        return Map.copyOf(result);
    }
}
