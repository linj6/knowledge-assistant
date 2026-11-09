package com.lnjecit.knowledge.assistant.ingestion.task;

import com.lnjecit.knowledge.assistant.infrastructure.persistence.ImportTask;
import com.lnjecit.knowledge.assistant.infrastructure.persistence.ImportTaskMapper;
import org.springframework.stereotype.Repository;

@Repository
public class TaskProcessingRepository {
    private final ImportTaskMapper tasks;

    public TaskProcessingRepository(ImportTaskMapper tasks) {
        this.tasks = tasks;
    }

    public ImportTask task(Long id) {
        return tasks.selectById(id);
    }
}
