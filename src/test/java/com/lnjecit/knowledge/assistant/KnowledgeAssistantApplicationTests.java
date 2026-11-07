package com.lnjecit.knowledge.assistant;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@org.springframework.test.context.ActiveProfiles("offline")
@SpringBootTest(properties = {"assistant.model.enabled=false", "assistant.rerank.enabled=false"})
class KnowledgeAssistantApplicationTests {

    @Test
    void contextLoads() {}
}
