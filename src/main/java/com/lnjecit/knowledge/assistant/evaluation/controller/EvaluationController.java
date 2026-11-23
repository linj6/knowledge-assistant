package com.lnjecit.knowledge.assistant.evaluation.controller;

import com.lnjecit.knowledge.assistant.evaluation.model.EvaluationRunResponse;
import com.lnjecit.knowledge.assistant.evaluation.store.EvaluationRunStore;
import java.nio.file.Path;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/evaluation/runs")
public class EvaluationController {
    private final EvaluationRunStore store;

    @Autowired
    public EvaluationController(EvaluationRunStore store) {
        this.store = store;
    }

    EvaluationController(Path root) {
        this(new EvaluationRunStore(root));
    }

    @GetMapping
    public List<Object> list() throws Exception {
        return store.list();
    }

    @GetMapping("/{runId}")
    public EvaluationRunResponse read(@PathVariable String runId) {
        return store.read(runId);
    }
}
