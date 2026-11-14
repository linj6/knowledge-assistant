package com.lnjecit.knowledge.assistant.evaluation;

import com.lnjecit.knowledge.assistant.rag.RagService.RagExecutionResult;
import com.lnjecit.knowledge.assistant.retrieval.snapshot.PublishedVersionSnapshot;
import java.util.Map;

/** C1 文件协议；哈希映射以产物名称为动态键。 */
public final class AnswerEvaluationRun {
    private AnswerEvaluationRun() {}

    public record Manifest(
            String runnerVersion,
            int schemaVersion,
            String runId,
            String createdAt,
            String dataset,
            String datasetSha256,
            String configuredChatModel,
            String configuredEmbeddingModel,
            String stage,
            PublishedVersionSnapshot snapshot,
            EvaluationService.Request request,
            RunStatus status,
            String errorCode,
            Map<String, String> artifactSha256) {
        public Manifest withArtifacts(Map<String, String> hashes) {
            return new Manifest(
                    runnerVersion,
                    schemaVersion,
                    runId,
                    createdAt,
                    dataset,
                    datasetSha256,
                    configuredChatModel,
                    configuredEmbeddingModel,
                    stage,
                    snapshot,
                    request,
                    status,
                    errorCode,
                    hashes);
        }
    }

    public record Results(
            @com.fasterxml.jackson.annotation.JsonProperty("case") EvaluationService.GoldCase goldCase,
            RagExecutionResult execution) {}

    public record Response(String runId, Manifest manifest, Results results) implements EvaluationRunResponse {}
}
