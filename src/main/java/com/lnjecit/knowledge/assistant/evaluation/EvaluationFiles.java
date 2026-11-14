package com.lnjecit.knowledge.assistant.evaluation;

import java.nio.file.Path;

final class EvaluationFiles {
    static final Path ROOT = Path.of("data/evaluation");
    static final String MANIFEST = "manifest.json",
            RESULTS = "results.json",
            REPORT = "report.md",
            METRICS = "metrics.json",
            RETRIEVAL = "retrieval-results.jsonl";
    static final String ANSWER_PROTOCOL = "answer-evaluation-v1", RUN_PROTOCOL = "m4-d03-answer-v1";
    static final String FINGERPRINT = "57c202a788d20e86a2ed166776c77dab6e66bed8b4ae08a1a925806458a5dbca";

    private EvaluationFiles() {}
}
