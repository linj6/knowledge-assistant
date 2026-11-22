package com.lnjecit.knowledge.assistant;

import static org.assertj.core.api.Assertions.assertThat;

import com.lnjecit.knowledge.assistant.evaluation.EvaluationCorpus;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class GoldPreparationTests {
    @Test
    void validateFrozenEvidence() throws Exception {
        JsonMapper json = JsonMapper.builder().build();
        JsonNode gold = json.readTree(Files.readString(Path.of("docs/engineering/m4-01-gold-evidence.json")));
        JsonNode protocol = json.readTree(Files.readString(Path.of("docs/engineering/m4-02c1-gold-protocol.json")));
        assertThat(gold.size()).isEqualTo(12);
        assertThat(protocol.path("questions").size()).isEqualTo(12);
        Set<String> ids = new HashSet<>();
        // 原文校验只能证明区间一致，语义支持仍需人工确认。
        for (int i = 0; i < gold.size(); i++) {
            JsonNode question = gold.get(i);
            JsonNode mapping = protocol.path("questions").get(i);
            String source = Files.readString(
                    EvaluationCorpus.resolve(question.path("documentPath").asString()));
            assertThat(EmbeddingSnapshot.sha(source))
                    .isEqualTo(mapping.path("sourceSha256").asString());
            assertThat(mapping.path("questionId").asString())
                    .isEqualTo(question.path("questionId").asString());
            assertThat(mapping.path("querySha256").asString())
                    .isEqualTo(EmbeddingSnapshot.sha(question.path("question").asString()));
            assertThat(mapping.path("relation").asString()).isEqualTo("ALL");
            assertThat(mapping.path("reviewStatus").asString()).isEqualTo("CONFIRMED");
            assertThat(mapping.path("evidence").size())
                    .isEqualTo(question.path("goldEvidence").size());
            for (int j = 0; j < question.path("goldEvidence").size(); j++) {
                JsonNode evidence = question.path("goldEvidence").get(j);
                JsonNode entry = mapping.path("evidence").get(j);
                assertThat(ids.add(entry.path("evidenceId").asString())).isTrue();
                assertThat(entry.path("sourceSpan")).isEqualTo(evidence.path("span"));
                int start = evidence.path("span").path("start").asInt();
                int end = evidence.path("span").path("end").asInt();
                assertThat(source.substring(source.offsetByCodePoints(0, start), source.offsetByCodePoints(0, end)))
                        .isEqualTo(evidence.path("text").asString());
            }
        }
    }
}
