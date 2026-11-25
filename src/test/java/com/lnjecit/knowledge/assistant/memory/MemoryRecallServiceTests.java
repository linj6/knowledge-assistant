package com.lnjecit.knowledge.assistant.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.lnjecit.knowledge.assistant.auth.entity.User;
import com.lnjecit.knowledge.assistant.auth.repository.UserRepository;
import com.lnjecit.knowledge.assistant.common.error.BusinessException;
import com.lnjecit.knowledge.assistant.infrastructure.ai.TokenEstimator;
import com.lnjecit.knowledge.assistant.memory.dto.RecalledMemory;
import com.lnjecit.knowledge.assistant.memory.entity.MemoryType;
import com.lnjecit.knowledge.assistant.memory.entity.UserMemory;
import com.lnjecit.knowledge.assistant.memory.repository.UserMemoryRepository;
import com.lnjecit.knowledge.assistant.memory.service.MemoryRecallProperties;
import com.lnjecit.knowledge.assistant.memory.service.MemoryRecallService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import tools.jackson.databind.json.JsonMapper;

class MemoryRecallServiceTests {
    private final UserRepository users = mock(UserRepository.class);
    private final UserMemoryRepository memories = mock(UserMemoryRepository.class);
    private final MemoryRecallProperties properties = new MemoryRecallProperties();
    private final MemoryRecallService service = new MemoryRecallService(memories, users, properties);

    @Test
    void disabledOrMissingUserDoesNotQueryMemories() {
        User user = new User();
        user.setMemoryEnabled(false);
        when(users.find(1L)).thenReturn(user);

        assertThat(service.recall(1L, "Java")).isEmpty();
        assertThatThrownBy(() -> service.recall(2L, "Java")).isInstanceOf(BusinessException.class);
        verifyNoInteractions(memories);
    }

    @Test
    void emptyStoreReturnsEmptyAndReadFailurePropagates() {
        enableUser();
        DataAccessResourceFailureException failure = new DataAccessResourceFailureException("unavailable");
        when(memories.listEnabledOwned(1L))
                .thenReturn(List.of())
                .thenThrow(failure)
                .thenThrow(new IllegalStateException("defect"));

        assertThat(service.recall(1L, "Java")).isEmpty();
        assertThatThrownBy(() -> service.recall(1L, "Java"))
                .isInstanceOf(MemoryRecallService.StorageUnavailableException.class)
                .hasCause(failure);
        assertThatThrownBy(() -> service.recall(1L, "Java")).isInstanceOf(IllegalStateException.class);
        when(users.find(1L)).thenThrow(failure);
        assertThatThrownBy(() -> service.recall(1L, "Java")).isSameAs(failure);
    }

    @Test
    void prioritizesMatchingTopicsAndPreservesRepositoryOrderWithinEachGroup() {
        enableUser();
        when(memories.listEnabledOwned(1L))
                .thenReturn(List.of(
                        memory(9, MemoryType.BACKGROUND, "background", null),
                        memory(8, MemoryType.LEARNING_GOAL, "goal", " ,  JAVA , spring, "),
                        memory(7, MemoryType.TECH_PREFERENCE, "tech", "Spring"),
                        memory(6, MemoryType.LEARNING_STATUS, "status", " , "),
                        memory(5, MemoryType.OTHER, "other", "SQL"),
                        memory(4, MemoryType.ANSWER_PREFERENCE, "answer", "unrelated"),
                        memory(3, MemoryType.LEARNING_STATUS, "status without keywords", null)));

        assertThat(service.recall(1L, "Java + SPRING?"))
                .extracting(RecalledMemory::id)
                .containsExactly(8L, 7L, 9L, 4L);

        properties.setMaxMemories(2);
        assertThat(service.recall(1L, "Java + SPRING?"))
                .extracting(RecalledMemory::id)
                .containsExactly(8L, 7L);
    }

    @Test
    void estimatesSerializedArrayAndSkipsOversizedMemoryAtExactBoundary() {
        enableUser();
        String content = "a\"\n";
        when(memories.listEnabledOwned(1L))
                .thenReturn(List.of(
                        memory(5, MemoryType.OTHER, "x".repeat(500), "java"),
                        memory(4, MemoryType.BACKGROUND, content, null)));
        RecalledMemory expected = new RecalledMemory(4, MemoryType.BACKGROUND, content);
        String modelJson = "[{\"type\":\"BACKGROUND\",\"content\":\"a\\\"\\n\"}]";
        assertThat(JsonMapper.builder().build().writeValueAsString(List.of(expected)))
                .isEqualTo(modelJson);
        int boundary = Math.toIntExact(new TokenEstimator(0).estimate(modelJson).estimatedTokens());

        properties.setEstimatedTokenBudget(boundary);
        assertThat(service.recall(1L, "Java")).containsExactly(expected);

        properties.setEstimatedTokenBudget(boundary - 1);
        assertThat(service.recall(1L, "Java")).isEmpty();
    }

    @Test
    void validatesConfiguredLimits() {
        assertThat(properties.getMaxMemories()).isEqualTo(10);
        assertThat(properties.getEstimatedTokenBudget()).isEqualTo(1000);
        assertThatThrownBy(() -> properties.setMaxMemories(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> properties.setEstimatedTokenBudget(0)).isInstanceOf(IllegalArgumentException.class);
    }

    private void enableUser() {
        User user = new User();
        user.setMemoryEnabled(true);
        when(users.find(1L)).thenReturn(user);
    }

    private static UserMemory memory(long id, MemoryType type, String content, String keywords) {
        UserMemory memory = new UserMemory();
        memory.setId(id);
        memory.setType(type);
        memory.setContent(content);
        memory.setKeywords(keywords);
        return memory;
    }
}
