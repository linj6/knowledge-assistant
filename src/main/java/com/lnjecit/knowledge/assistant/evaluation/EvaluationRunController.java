package com.lnjecit.knowledge.assistant.evaluation;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/v1/evaluation")
@ConditionalOnProperty(name = "app.evaluation.enabled", havingValue = "true")
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
