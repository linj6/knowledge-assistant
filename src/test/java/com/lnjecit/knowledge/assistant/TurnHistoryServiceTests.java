package com.lnjecit.knowledge.assistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.lnjecit.knowledge.assistant.conversation.dto.HistoryTurn;
import com.lnjecit.knowledge.assistant.conversation.repository.ConversationRepository;
import com.lnjecit.knowledge.assistant.conversation.service.TurnHistoryService;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TurnHistoryServiceTests {
    private final ConversationRepository repository = mock(ConversationRepository.class);
    private final UUID currentTurn = UUID.randomUUID();

    @Test
    void keepsNewestCompleteTurnsWithinBudgetAndReturnsThemOldestFirst() {
        HistoryTurn newest = new HistoryTurn("😀", "新");
        HistoryTurn oversized = new HistoryTurn("问题", "超出剩余预算的答案");
        HistoryTurn oldest = new HistoryTurn("问", "答");
        when(repository.findRecentAnsweredTurns(1L, currentTurn)).thenReturn(List.of(newest, oversized, oldest));

        assertThat(new TurnHistoryService(repository, 8).select(1L, currentTurn))
                .containsExactly(oldest, newest);
        assertThat(new TurnHistoryService(repository, 4).select(1L, currentTurn))
                .containsExactly(newest);
        assertThat(new TurnHistoryService(repository, 3).select(1L, currentTurn))
                .isEmpty();
    }

    @Test
    void returnsEmptyHistoryWhenThereAreNoCandidates() {
        when(repository.findRecentAnsweredTurns(1L, currentTurn)).thenReturn(List.of());
        assertThat(new TurnHistoryService(repository, 8).select(1L, currentTurn))
                .isEmpty();
    }

    @Test
    void zeroBudgetReturnsEmptyHistoryWithoutQuerying() {
        assertThat(new TurnHistoryService(repository, 0).select(1L, currentTurn))
                .isEmpty();
        verifyNoInteractions(repository);
    }
}
