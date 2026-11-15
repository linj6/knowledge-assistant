package com.lnjecit.knowledge.assistant.evaluation.controller;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.lnjecit.knowledge.assistant.document.mapper.DocumentMapper;
import com.lnjecit.knowledge.assistant.evaluation.service.AnswerReviewService;
import com.lnjecit.knowledge.assistant.evaluation.service.EvaluationService;
import com.lnjecit.knowledge.assistant.evaluation.store.AnswerReviewStore;
import com.lnjecit.knowledge.assistant.evaluation.store.EvaluationRunStore;
import com.lnjecit.knowledge.assistant.rag.RagService;
import com.lnjecit.knowledge.assistant.retrieval.snapshot.PublishedVersionSnapshotService;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

class EvaluationAssemblyTests {
    @Test
    void evaluationBeansRegisterByDefaultWithoutExecutingTasks() {
        RagService rag = mock(RagService.class);
        DocumentMapper documents = mock(DocumentMapper.class);
        PublishedVersionSnapshotService snapshots = mock(PublishedVersionSnapshotService.class);
        // 只替换外部依赖，扫描真实评估 Bean，不设置任何评估开关。
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.registerBean(RagService.class, () -> rag);
            context.registerBean(DocumentMapper.class, () -> documents);
            context.registerBean(PublishedVersionSnapshotService.class, () -> snapshots);
            context.scan("com.lnjecit.knowledge.assistant.evaluation");
            context.refresh();
            for (Class<?> type : new Class<?>[] {
                EvaluationController.class,
                EvaluationRunController.class,
                AnswerReviewController.class,
                EvaluationService.class,
                AnswerReviewService.class,
                EvaluationRunStore.class,
                AnswerReviewStore.class
            }) assertNotNull(context.getBean(type));
            verifyNoInteractions(rag, documents, snapshots);
        }
    }
}
