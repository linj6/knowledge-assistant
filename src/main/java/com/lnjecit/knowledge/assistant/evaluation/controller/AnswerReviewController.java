package com.lnjecit.knowledge.assistant.evaluation.controller;

import com.lnjecit.knowledge.assistant.evaluation.metrics.AnswerMetrics;
import com.lnjecit.knowledge.assistant.evaluation.model.AnswerReview;
import com.lnjecit.knowledge.assistant.evaluation.service.AnswerReviewService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/evaluation/runs/{runId}")
public class AnswerReviewController {
    private final AnswerReviewService service;

    public AnswerReviewController(AnswerReviewService service) {
        this.service = service;
    }

    @GetMapping("/review")
    public AnswerReview.Material read(@PathVariable String runId) throws Exception {
        return service.read(runId);
    }

    @PutMapping("/review")
    public AnswerReview.Saved save(@PathVariable String runId, @RequestBody AnswerReview.Input input) throws Exception {
        return service.save(runId, input);
    }

    @GetMapping("/score")
    public AnswerMetrics.Result score(@PathVariable String runId) throws Exception {
        return service.score(runId);
    }
}
