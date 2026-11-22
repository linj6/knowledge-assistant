package com.lnjecit.knowledge.assistant.conversation.service;

import com.lnjecit.knowledge.assistant.conversation.dto.HistoryTurn;
import com.lnjecit.knowledge.assistant.conversation.repository.ConversationRepository;
import com.lnjecit.knowledge.assistant.infrastructure.ai.TokenEstimator;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class TurnHistoryService {
    private final ConversationRepository repository;
    private final TokenEstimator estimator = new TokenEstimator(0);
    private final long tokenBudget;

    public TurnHistoryService(
            ConversationRepository repository,
            @Value("${assistant.conversation.history.estimated-token-budget:2000}") long tokenBudget) {
        if (tokenBudget < 0) {
            throw new IllegalArgumentException("History token budget must be non-negative");
        }
        this.repository = repository;
        this.tokenBudget = tokenBudget;
    }

    public List<HistoryTurn> select(Long conversationId, UUID currentTurnId) {
        if (tokenBudget == 0) {
            return List.of();
        }
        List<HistoryTurn> selected = new ArrayList<>();
        long remaining = tokenBudget;
        // 优先分配预算给较新的整轮；超预算的轮次跳过，继续检查更早的轮次。
        for (HistoryTurn turn : repository.findRecentAnsweredTurns(conversationId, currentTurnId)) {
            long cost = estimator.estimate(turn.question()).estimatedTokens()
                    + estimator.estimate(turn.answer()).estimatedTokens();
            if (cost <= remaining) {
                selected.add(turn);
                remaining -= cost;
            }
        }
        return List.copyOf(selected.reversed());
    }
}
