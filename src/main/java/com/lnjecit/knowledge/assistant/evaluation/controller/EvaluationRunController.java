package com.lnjecit.knowledge.assistant.evaluation.controller;

import com.lnjecit.knowledge.assistant.evaluation.dataset.EvaluationDatasetLoader;
import com.lnjecit.knowledge.assistant.evaluation.service.EvaluationService;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/v1/evaluation")
public class EvaluationRunController {
    private final EvaluationService service;

    public EvaluationRunController(EvaluationService service) {
        this.service = service;
    }

    @GetMapping("/datasets")
    public EvaluationDatasetLoader.Description datasets() throws Exception {
        return service.datasets();
    }

    @PostMapping("/runs")
    public EvaluationService.Outcome run(@RequestBody EvaluationService.Request request) throws Exception {
        return service.run(request);
    }
}
