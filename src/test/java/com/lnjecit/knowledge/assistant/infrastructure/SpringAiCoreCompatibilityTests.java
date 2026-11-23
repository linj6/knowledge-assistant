package com.lnjecit.knowledge.assistant.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

class SpringAiCoreCompatibilityTests {

    @Test
    void chatClientCallsMockModel() {
        ChatModel model = respondingModel();

        // 仅验证客户端到模型接口的同步调用，模型不发出网络请求。
        String answer = ChatClient.create(model).prompt().user("测试问题").call().content();

        assertThat(answer).isEqualTo("模拟回答");
        ArgumentCaptor<Prompt> prompt = ArgumentCaptor.forClass(Prompt.class);
        verify(model).call(prompt.capture());
        assertThat(prompt.getValue().getUserMessage().getText()).isEqualTo("测试问题");
    }

    @Test
    void embeddingDefaultMethodCallsMockModel() {
        EmbeddingModel model = mock(EmbeddingModel.class, org.mockito.Mockito.CALLS_REAL_METHODS);
        when(model.call(any(EmbeddingRequest.class)))
                .thenReturn(new EmbeddingResponse(List.of(new Embedding(new float[] {0.25f, 0.75f}, 0))));

        // 使用真实默认方法完成文本到请求的转换，仅替换底层模型响应。
        assertThat(model.embed("离线文本")).containsExactly(0.25f, 0.75f);
        ArgumentCaptor<EmbeddingRequest> request = ArgumentCaptor.forClass(EmbeddingRequest.class);
        verify(model).call(request.capture());
        assertThat(request.getValue().getInstructions()).containsExactly("离线文本");
    }

    @Test
    void chatMemorySavesReadsAndClearsMessages() {
        ChatMemory memory = MessageWindowChatMemory.builder().maxMessages(10).build();
        memory.add("memory-test", new UserMessage("离线消息"));

        assertThat(memory.get("memory-test"))
                .extracting(message -> message.getText())
                .containsExactly("离线消息");
        assertThat(memory.get("other-conversation")).isEmpty();
        memory.clear("memory-test");
        assertThat(memory.get("memory-test")).isEmpty();
    }

    @Test
    void memoryAdvisorPassesHistoryToModelAndSavesResponse() {
        ChatMemory memory = MessageWindowChatMemory.builder().build();
        memory.add("advisor-test", new UserMessage("已有消息"));
        ChatModel model = respondingModel();
        ChatClient client = ChatClient.builder(model)
                .defaultAdvisors(MessageChatMemoryAdvisor.builder(memory).build())
                .build();

        // 显式指定会话，验证真实 Advisor 链读取历史并保存本轮消息。
        assertThat(client.prompt()
                        .user("当前消息")
                        .advisors(spec -> spec.param(ChatMemory.CONVERSATION_ID, "advisor-test"))
                        .call()
                        .content())
                .isEqualTo("模拟回答");

        ArgumentCaptor<Prompt> prompt = ArgumentCaptor.forClass(Prompt.class);
        verify(model).call(prompt.capture());
        assertThat(prompt.getValue().getInstructions())
                .extracting(message -> message.getText())
                .containsExactly("已有消息", "当前消息");
        assertThat(memory.get("advisor-test"))
                .extracting(message -> message.getText())
                .containsExactly("已有消息", "当前消息", "模拟回答");
    }

    @Test
    void vectorStoreAcceptsSearchRequestAndDocumentTypes() {
        VectorStore store = mock(VectorStore.class);
        SearchRequest request = SearchRequest.builder().query("离线查询").topK(2).build();
        Document document = new Document("模拟检索片段");
        when(store.similaritySearch(request)).thenReturn(List.of(document));

        // 仅验证接口类型与模拟返回值，不代表已执行向量化或相似度检索。
        assertThat(store.similaritySearch(request)).containsExactly(document);
        verify(store).similaritySearch(request);
    }

    @Test
    void coreBeansCanBeManuallyAssembled() {
        // 手动注册测试 Bean，验证 Spring 容器装配；不宣称供应商自动配置通过。
        try (var context = new AnnotationConfigApplicationContext()) {
            context.registerBean(ChatModel.class, SpringAiCoreCompatibilityTests::respondingModel);
            context.registerBean(
                    ChatMemory.class, () -> MessageWindowChatMemory.builder().build());
            context.registerBean(MessageChatMemoryAdvisor.class, () -> MessageChatMemoryAdvisor.builder(
                            context.getBean(ChatMemory.class))
                    .build());
            context.registerBean(ChatClient.class, () -> ChatClient.builder(context.getBean(ChatModel.class))
                    .defaultAdvisors(context.getBean(MessageChatMemoryAdvisor.class))
                    .build());
            context.refresh();

            assertThat(context.getBean(ChatClient.class)
                            .prompt()
                            .user("装配验证")
                            .advisors(spec -> spec.param(ChatMemory.CONVERSATION_ID, "bean-test"))
                            .call()
                            .content())
                    .isEqualTo("模拟回答");
        }
    }

    private static ChatModel respondingModel() {
        ChatModel model = mock(ChatModel.class);
        // 2.0 客户端会读取并复制模型选项，测试替身也必须遵守此契约。
        when(model.getOptions()).thenReturn(ChatOptions.builder().build());
        when(model.call(any(Prompt.class)))
                .thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage("模拟回答")))));
        return model;
    }
}
