package com.lnjecit.knowledge.assistant.evaluation;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class RetrievalMetricsFixtureTests {
    @Test
    void validatesFrozenOfflineFixture() throws IOException {
        JsonMapper mapper = JsonMapper.builder().build();
        try (InputStream input = getClass().getResourceAsStream("/retrieval-metrics-v1.json")) {
            JsonNode fixture = mapper.readTree(input);
            assertThat(fixture.path("protocol").asString()).isEqualTo("retrieval-metrics-v1");
            assertThat(fixture.path("gold"))
                    .isEqualTo(
                            mapper.readTree(Files.readString(Path.of("docs/engineering/m4-02c1-gold-protocol.json"))));
            Set<String> questions = new HashSet<>();
            for (JsonNode question : fixture.path("gold").path("questions")) {
                assertThat(questions.add(question.path("questionId").asString()))
                        .isTrue();
                assertThat(question.path("reviewStatus").asString()).isEqualTo("CONFIRMED");
            }
            Set<String> pool = new HashSet<>();
            for (JsonNode judgment : fixture.path("pool").path("judgments")) {
                assertThat(pool.add(key(judgment, judgment.path("chunkId").asString())))
                        .isTrue();
                assertThat(judgment.path("relevance").asString()).isEqualTo("UNJUDGED");
                assertThat(judgment.path("reviewer").isNull()).isTrue();
            }
            Set<String> runs = new HashSet<>();
            // 固定实验必须覆盖全部组合；排名和评审池引用不可重复或断裂。
            for (JsonNode run : fixture.path("runs")) {
                assertThat(questions).contains(run.path("questionId").asString());
                assertThat(runs.add(key(run, run.path("strategy").asString()))).isTrue();
                assertThat(run.path("status").asString()).isEqualTo("SUCCESS");
                assertThat(run.path("chunks").size()).isEqualTo(5);
                Set<String> chunks = new HashSet<>();
                int rank = 1;
                for (JsonNode chunk : run.path("chunks")) {
                    assertThat(chunk.path("finalRank").asInt()).isEqualTo(rank++);
                    assertThat(chunks.add(chunk.path("chunkId").asString())).isTrue();
                    assertThat(pool).contains(key(run, chunk.path("chunkId").asString()));
                    assertThat(chunk.path("sourceStart").asInt()).isGreaterThanOrEqualTo(0);
                    assertThat(chunk.path("sourceEnd").asInt())
                            .isGreaterThan(chunk.path("sourceStart").asInt());
                    assertThat(chunk.path("content").asString()).isNotEmpty();
                }
                for (String cutoff : new String[] {"3", "5"}) {
                    assertThat(run.path("semantic").path(cutoff).asString()).isIn("COMPLETE", "PARTIAL", "MISSING");
                }
            }
            assertThat(questions).hasSize(12);
            assertThat(runs).hasSize(72);
            assertThat(pool).hasSize(186);
        }
    }

    private static String key(JsonNode node, String suffix) {
        return node.path("questionId").asString() + ":" + node.path("chunking").asString() + ":" + suffix;
    }
}
