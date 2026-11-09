package com.lnjecit.knowledge.assistant;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.lnjecit.knowledge.assistant.infrastructure.persistence.Document;
import com.lnjecit.knowledge.assistant.infrastructure.persistence.ImportTask;
import com.lnjecit.knowledge.assistant.infrastructure.persistence.ImportTaskStatus;
import com.lnjecit.knowledge.assistant.infrastructure.persistence.IndexVersion;
import com.lnjecit.knowledge.assistant.infrastructure.persistence.IndexVersionStatus;
import com.lnjecit.knowledge.assistant.infrastructure.storage.OriginalFileStore;
import com.lnjecit.knowledge.assistant.ingestion.chunk.ChunkRepository;
import com.lnjecit.knowledge.assistant.ingestion.chunk.ChunkService;
import com.lnjecit.knowledge.assistant.ingestion.embedding.EmbeddingService;
import com.lnjecit.knowledge.assistant.ingestion.indexing.EsIndexService;
import com.lnjecit.knowledge.assistant.ingestion.publish.IndexPublishService;
import com.lnjecit.knowledge.assistant.ingestion.task.ClaimedTask;
import com.lnjecit.knowledge.assistant.ingestion.task.IngestionTaskProcessor;
import com.lnjecit.knowledge.assistant.ingestion.task.TaskExecutionContext;
import com.lnjecit.knowledge.assistant.ingestion.task.TaskExecutionException;
import com.lnjecit.knowledge.assistant.ingestion.task.TaskProcessingRepository;
import com.lnjecit.knowledge.assistant.ingestion.task.TaskService;
import com.lnjecit.knowledge.assistant.ingestion.task.TaskWorker;
import com.lnjecit.knowledge.assistant.ingestion.verification.IndexVerificationService;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class IngestionTaskProcessorTests {
    TaskProcessingRepository tasks = mock(TaskProcessingRepository.class);
    ChunkRepository records = mock(ChunkRepository.class);
    OriginalFileStore files = mock(OriginalFileStore.class);
    ChunkService chunks = mock(ChunkService.class);
    EmbeddingService embeddings = mock(EmbeddingService.class);
    EsIndexService es = mock(EsIndexService.class);
    IndexVerificationService verification = mock(IndexVerificationService.class);
    IndexPublishService publisher = mock(IndexPublishService.class);
    IngestionTaskProcessor processor;
    ClaimedTask claim = new ClaimedTask(1L, 2L, 3L, "owner", 1);
    Document document = new Document();
    IndexVersion version = new IndexVersion();
    AtomicLong clock = new AtomicLong();
    TaskExecutionContext context = new TaskExecutionContext(Duration.ofNanos(100), clock::get);

    @BeforeEach
    void setup() {
        processor = new IngestionTaskProcessor(tasks, records, files, chunks, embeddings, es, verification, publisher);
        ImportTask task = new ImportTask();
        task.setDocumentId(2L);
        task.setIndexVersionId(3L);
        task.setClaimEpoch(1L);
        task.setStatus(ImportTaskStatus.RUNNING);
        task.setLeaseOwner("owner");
        when(tasks.task(1L)).thenReturn(task);
        document.setId(2L);
        document.setFilename("test.txt");
        document.setObjectKey("originals/abc");
        document.setContentHash("2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824");
        version.setId(3L);
        version.setDocumentId(2L);
        version.setStatus(IndexVersionStatus.BUILDING);
        when(records.document(2L)).thenReturn(document);
        when(records.version(3L)).thenReturn(version);
        when(files.read("originals/abc")).thenReturn("hello".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        when(verification.verify(2L, 3L))
                .thenReturn(new IndexVerificationService.Result(2L, 3L, 1, 1, 1, null, "VERIFIED"));
    }

    @Test
    void buildingOrder() {
        processor.process(claim, context);
        InOrder order = inOrder(files, chunks, embeddings, es, verification, publisher);
        order.verify(files).read("originals/abc");
        order.verify(chunks).initialize(eq(2L), eq(3L), any());
        order.verify(embeddings).embed(eq(2L), eq(3L), any(Runnable.class));
        order.verify(es).index(2L, 3L);
        order.verify(verification).verify(2L, 3L);
        order.verify(publisher).publish(claim);
    }

    @Test
    void verifiedSkipsBuild() {
        version.setStatus(IndexVersionStatus.VERIFIED);
        processor.process(claim, context);
        verifyNoInteractions(files, chunks, embeddings, es);
        verify(verification).verify(2L, 3L);
        verify(publisher).publish(claim);
    }

    @Test
    void publishedOnlyConfirms() {
        version.setStatus(IndexVersionStatus.PUBLISHED);
        document.setActiveVersionId(3L);
        processor.process(claim, context);
        verify(publisher).publish(claim);
        verifyNoInteractions(files, chunks, embeddings, es, verification);
    }

    @Test
    void hashMismatchStops() {
        document.setContentHash("wrong");
        assertThatThrownBy(() -> processor.process(claim, context)).isInstanceOf(TaskExecutionException.class);
        verifyNoInteractions(chunks, embeddings, es, verification, publisher);
    }

    @Test
    void parseFailureStops() {
        document.setFilename("test.pdf");
        assertThatThrownBy(() -> processor.process(claim, context)).isInstanceOf(TaskExecutionException.class);
        verifyNoInteractions(chunks, embeddings, es, verification, publisher);
    }

    @Test
    void embeddingFailureStops() {
        doThrow(new IllegalStateException()).when(embeddings).embed(eq(2L), eq(3L), any(Runnable.class));
        assertThatThrownBy(() -> processor.process(claim, context)).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(es, verification, publisher);
    }

    @Test
    void esFailureStops() {
        doThrow(new IllegalStateException("ES_CONNECTION_OUTCOME_UNKNOWN"))
                .when(es)
                .index(2L, 3L);
        assertThatThrownBy(() -> processor.process(claim, context))
                .isInstanceOfSatisfying(
                        TaskExecutionException.class, e -> org.assertj.core.api.Assertions.assertThat(e.code())
                                .isEqualTo(TaskExecutionException.Code.SEARCH_UNAVAILABLE));
        verifyNoInteractions(verification, publisher);
    }

    @Test
    void verificationFailureStops() {
        when(verification.verify(2L, 3L)).thenReturn(null);
        assertThatThrownBy(() -> processor.process(claim, context)).isInstanceOf(TaskExecutionException.class);
        verifyNoInteractions(publisher);
    }

    @Test
    void deadlineBeforePublish() {
        when(verification.verify(2L, 3L)).thenAnswer(invocation -> {
            clock.set(100);
            return new IndexVerificationService.Result(2L, 3L, 1, 1, 1, null, "VERIFIED");
        });
        assertThatThrownBy(() -> processor.process(claim, context)).isInstanceOf(TaskExecutionException.class);
        verifyNoInteractions(publisher);
    }

    @Test
    void noPostPublishTimeout() {
        doAnswer(invocation -> {
                    clock.set(100);
                    return null;
                })
                .when(publisher)
                .publish(claim);
        processor.process(claim, context);
    }

    @Test
    void rejectsOuterTransaction() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            assertThatThrownBy(() -> processor.process(claim, context)).isInstanceOf(IllegalStateException.class);
            verifyNoInteractions(files);
        } finally {
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
    }

    @Test
    void workerDoesNotFailAfterReturn() {
        TaskService service = mock(TaskService.class);
        new TaskWorker(service, (task, ctx) -> {}).execute(claim);
        verifyNoInteractions(service);
    }

    @Test
    void classifiesKnownModelFailures() {
        for (com.lnjecit.knowledge.assistant.infrastructure.ai.ModelFailures.Kind kind :
                com.lnjecit.knowledge.assistant.infrastructure.ai.ModelFailures.Kind.values()) {
            doThrow(com.lnjecit.knowledge.assistant.infrastructure.ai.ModelFailures.rerankFailure(kind))
                    .when(embeddings)
                    .embed(eq(2L), eq(3L), any(Runnable.class));
            TaskExecutionException.Code expected =
                    switch (kind) {
                        case TIMEOUT -> TaskExecutionException.Code.MODEL_TIMEOUT;
                        case CONNECTION, RATE_LIMIT, SERVER -> TaskExecutionException.Code.MODEL_UNAVAILABLE;
                        default -> TaskExecutionException.Code.EXECUTION_FAILED;
                    };
            assertThatThrownBy(() -> processor.process(claim, context))
                    .isInstanceOfSatisfying(
                            TaskExecutionException.class, e -> org.assertj.core.api.Assertions.assertThat(e.code())
                                    .isEqualTo(expected));
        }
        verifyNoInteractions(es, verification, publisher);
    }

    @Test
    void ownershipMismatchStopsBeforeNetwork() {
        version.setDocumentId(99L);
        assertThatThrownBy(() -> processor.process(claim, context)).isInstanceOf(TaskExecutionException.class);
        verifyNoInteractions(files, chunks, embeddings, es, verification, publisher);
    }

    @Test
    void ambiguousPublishReusesIdempotentProtocol() {
        doThrow(new org.springframework.transaction.TransactionSystemException("response lost"))
                .doNothing()
                .when(publisher)
                .publish(claim);
        processor.process(claim, context);
        verify(publisher, org.mockito.Mockito.times(2)).publish(claim);
    }

    @Test
    void workerAllowsDeadlineToExpireAfterSuccessfulProcessorReturn() {
        TaskService service = mock(TaskService.class);
        com.lnjecit.knowledge.assistant.infrastructure.config.WorkerProperties properties =
                new com.lnjecit.knowledge.assistant.infrastructure.config.WorkerProperties();
        properties.setMaxTaskDuration(Duration.ofMillis(100));
        new TaskWorker(
                        service,
                        (task, ctx) -> {
                            try {
                                Thread.sleep(150);
                            } catch (InterruptedException failure) {
                                throw new IllegalStateException(failure);
                            }
                        },
                        properties)
                .execute(claim);
        verifyNoInteractions(service);
    }

    @Test
    void explicitPublishConflictIsNotRetried() {
        doThrow(new IllegalStateException("PUBLISH_STATE_CONFLICT"))
                .when(publisher)
                .publish(claim);
        assertThatThrownBy(() -> processor.process(claim, context)).hasMessage("PUBLISH_STATE_CONFLICT");
        verify(publisher).publish(claim);
    }
}
