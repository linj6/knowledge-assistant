package com.lnjecit.knowledge.assistant.evaluation;

import com.lnjecit.knowledge.assistant.evaluation.EvaluationService.Dataset;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.json.JsonMapper;

/** JSON 仅在加载边界使用，业务层消费已验证的类型化数据。 */
final class EvaluationDatasetLoader {
    private final JsonMapper json = JsonMapper.builder().build();
    private final ClassPathResource resource = new ClassPathResource("evaluation/answer-evaluation-v1.json");

    record Loaded(Dataset dataset, String fingerprint, String error, String sha256) {}

    Loaded load() throws Exception {
        byte[] bytes = resource.getContentAsByteArray();
        tools.jackson.databind.JsonNode frozen = json.readTree(bytes);
        String fingerprint = FrozenFingerprint.answer(frozen);
        if (!EvaluationFiles.FINGERPRINT.equals(fingerprint)) {
            return new Loaded(null, null, "GOLD_FINGERPRINT_MISMATCH", null);
        }

        AnswerEvaluationDataset full = json.readValue(bytes, AnswerEvaluationDataset.class);
        if (!fingerprint.equals(full.freezeFingerprint()) || full.datasetVersion() != 1)
            return new Loaded(null, null, "GOLD_IDENTITY_MISMATCH", null);
        if (full.cases().stream().anyMatch(item -> !item.confirmed()))
            return new Loaded(null, null, "GOLD_UNCONFIRMED", null);
        Dataset dataset = json.readerFor(Dataset.class)
                .without(tools.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .readValue(frozen);
        if (!EvaluationFiles.ANSWER_PROTOCOL.equals(dataset.protocol())
                || dataset.schemaVersion() != 1
                || !"FROZEN".equals(dataset.freezeStatus())) return new Loaded(null, null, "GOLD_NOT_FROZEN", null);
        return new Loaded(dataset, fingerprint, null, Sha256.digest(bytes));
    }

    record Description(Dataset dataset, String sha256) {}

    Description describe() throws Exception {
        Loaded loaded = load();
        if (loaded.error() != null) throw new IllegalStateException(loaded.error());
        return new Description(loaded.dataset(), loaded.sha256());
    }
}
