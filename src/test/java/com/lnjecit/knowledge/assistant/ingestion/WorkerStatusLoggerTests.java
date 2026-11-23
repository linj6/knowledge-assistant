package com.lnjecit.knowledge.assistant.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import com.lnjecit.knowledge.assistant.ingestion.task.TaskProcessor;
import com.lnjecit.knowledge.assistant.ingestion.task.WorkerProperties;
import com.lnjecit.knowledge.assistant.ingestion.task.WorkerStatusLogger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

@ExtendWith(OutputCaptureExtension.class)
class WorkerStatusLoggerTests {
    @Test
    void reportsDisabledAndMissingProcessorWithoutStartingScheduler(CapturedOutput output) {
        WorkerProperties properties = new WorkerProperties();
        DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
        WorkerStatusLogger logger = new WorkerStatusLogger(properties, beans.getBeanProvider(TaskProcessor.class));
        logger.report();
        assertThat(output.getOut())
                .contains("enabled=false processorAvailable=false", "scanIntervalMs=5000 batchSize=1");
        properties.setEnabled(true);
        logger.report();
        assertThat(output.getOut()).contains("enabled=true processorAvailable=false");
        beans.registerSingleton("processor", (TaskProcessor) (task, context) -> {
            throw new AssertionError("must not execute");
        });
        logger.report();
        assertThat(output.getOut()).contains("enabled=true processorAvailable=true");
    }
}
