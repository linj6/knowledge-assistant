package com.lnjecit.knowledge.assistant.evaluation.metrics;

/** 冻结评估协议中的状态及规则名称；保持原有 JSON 字面值。 */
final class EvaluationProtocol {
    private EvaluationProtocol() {}

    static final String SUCCESS = "SUCCESS";
    static final String FAILED = "FAILED";
    static final String MISSING = "MISSING";
    static final String NOT_RUN = "NOT_RUN";
    static final String COMPLETED = "COMPLETED";
    static final String CONFIRMED = "CONFIRMED";
    static final String PENDING = "PENDING";
    static final String ALL = "ALL";
    static final String EXCLUDED = "EXCLUDED";
    static final String EMPTY_RESULT = "EMPTY_RESULT";
    static final String MISSING_GOLD = "MISSING_GOLD";
    static final String UNANSWERABLE = "UNANSWERABLE";
    static final String PENDING_REVIEW = "PENDING_REVIEW";
    static final String REJECTED_GOLD = "REJECTED_GOLD";
    static final String NOT_READY = "NOT_READY";
    static final String EXECUTION_NOT_SUCCESS = "EXECUTION_NOT_SUCCESS";
    static final String SNAPSHOT_MISMATCH = "SNAPSHOT_MISMATCH";
    static final String IDENTITY_CONFLICT = "IDENTITY_CONFLICT";
    static final String INVALID_POOL = "INVALID_POOL";
    static final String OUTSIDE_POOL = "OUTSIDE_POOL";
    static final String INVALID_RANKING = "INVALID_RANKING";
    static final String COMPLETE = "COMPLETE";
    static final String UNDEFINED = "UNDEFINED";
    static final String SCORED = "SCORED";
    static final String FROZEN = "FROZEN";
    static final String ANSWERED = "ANSWERED";
    static final String INSUFFICIENT_EVIDENCE = "INSUFFICIENT_EVIDENCE";
    static final String CITATION_INVALID = "CITATION_INVALID";
    static final String DEFINED = "DEFINED";
    static final String PASS = "PASS";
    static final String FAIL = "FAIL";
    static final int ANSWER_GOLD_VERSION = 1;
    static final String ANSWER_PROTOCOL = "answer-evaluation-v1";
}
