package com.lnjecit.knowledge.assistant.ingestion.task;

public record ClaimedTask(Long taskId, Long documentId, Long indexVersionId, String leaseOwner, long claimEpoch) {}
