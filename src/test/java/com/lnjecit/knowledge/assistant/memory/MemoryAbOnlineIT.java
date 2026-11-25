package com.lnjecit.knowledge.assistant.memory;

import com.lnjecit.knowledge.assistant.rag.generation.AnswerGenerator;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.slf4j.LoggerFactory;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.FileSystemResource;

class MemoryAbOnlineIT {
    @Test
    @EnabledIfSystemProperty(named = "mem05b.online", matches = "true")
    void runsEachFrozenPairOnce() throws Exception {
        MemoryAbPreflightTests preflight = new MemoryAbPreflightTests();
        preflight.verifiesSixFrozenPairsWithLocalChatStub();
        String key = System.getenv("DASHSCOPE_API_KEY");
        if (key == null || key.isBlank()) {
            Object configured = new YamlPropertySourceLoader()
                    .load("local", new FileSystemResource("config/application-local.yml"))
                    .getFirst()
                    .getProperty("assistant.model.api-key");
            key = configured instanceof String value ? value : "";
        }
        if (!key.matches("sk-[a-zA-Z0-9_-]+")) throw new IllegalStateException("DASHSCOPE_CREDENTIAL_UNAVAILABLE");

        // 供应商异常可能包含请求细节，在线记录只保存稳定失败分类。
        ch.qos.logback.classic.Logger logger =
                (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(AnswerGenerator.class);
        ch.qos.logback.classic.Level previous = logger.getLevel();
        logger.setLevel(ch.qos.logback.classic.Level.OFF);
        try {
            preflight.verifyPairs(new OnlineRun(Path.of("evaluation/mem-05b/online-requests.jsonl"), key));
        } finally {
            logger.setLevel(previous);
        }
    }
}
