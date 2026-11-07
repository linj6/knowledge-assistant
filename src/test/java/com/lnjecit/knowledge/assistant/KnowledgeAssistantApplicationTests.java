package com.lnjecit.knowledge.assistant;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@org.springframework.test.context.ActiveProfiles("test")
@SpringBootTest(
        properties = {
            "assistant.model.enabled=false",
            "assistant.rerank.enabled=false",
            "spring.autoconfigure.exclude=org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration"
        })
class KnowledgeAssistantApplicationTests {

    @Test
    void contextLoads() {}
}
