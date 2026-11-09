package com.lnjecit.knowledge.assistant.ingestion.task;

import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TaskService {
    private final TaskRepository repository;

    public TaskService(TaskRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public Optional<ClaimedTask> claimNext(String owner) {
        validate(owner);
        return repository.claimNext(owner);
    }

    @Transactional
    public Optional<ClaimedTask> claimById(Long id, String owner) {
        validate(owner);
        return repository.claimById(id, owner);
    }

    @Transactional
    public void fail(ClaimedTask task) {
        repository.fail(task);
    }

    private static void validate(String owner) {
        if (owner == null || owner.isBlank() || owner.length() > 128)
            throw new IllegalArgumentException("Invalid worker identity");
    }
}
