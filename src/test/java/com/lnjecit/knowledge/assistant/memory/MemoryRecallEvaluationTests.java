package com.lnjecit.knowledge.assistant.memory;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.lnjecit.knowledge.assistant.auth.entity.User;
import com.lnjecit.knowledge.assistant.auth.repository.UserRepository;
import com.lnjecit.knowledge.assistant.infrastructure.ai.TokenEstimator;
import com.lnjecit.knowledge.assistant.memory.dto.RecalledMemory;
import com.lnjecit.knowledge.assistant.memory.entity.UserMemory;
import com.lnjecit.knowledge.assistant.memory.repository.UserMemoryRepository;
import com.lnjecit.knowledge.assistant.memory.service.MemoryRecallProperties;
import com.lnjecit.knowledge.assistant.memory.service.MemoryRecallService;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class MemoryRecallEvaluationTests {
    record Samples(List<UserMemory> memories, List<Question> questions) {}

    record Question(String questionId, String question, List<Long> gold, String reason) {}

    @Test
    void measuresFrozenGoldAgainstProductionRecall() {
        JsonMapper json = JsonMapper.builder().build();
        Samples samples =
                json.readValue(Path.of("evaluation/mem-05a/samples.json").toFile(), Samples.class);
        UserRepository users = mock(UserRepository.class);
        UserMemoryRepository memories = mock(UserMemoryRepository.class);
        User user = new User();
        user.setMemoryEnabled(true);
        when(users.find(1L)).thenReturn(user);
        // 模拟仓储返回同一虚构账户的启用记录，沿用生产查询的排序契约。
        when(memories.listEnabledOwned(1L))
                .thenReturn(samples.memories().stream()
                        .filter(UserMemory::getEnabled)
                        .sorted(Comparator.comparing(UserMemory::getUpdatedAt)
                                .thenComparing(UserMemory::getId)
                                .reversed())
                        .toList());
        MemoryRecallService service = new MemoryRecallService(memories, users, new MemoryRecallProperties());
        TokenEstimator estimator = new TokenEstimator(0);
        double precisionSum = 0;
        double recallSum = 0;
        int precisionCount = 0;
        int recallCount = 0;
        for (Question question : samples.questions()) {
            List<RecalledMemory> recalled = service.recall(1L, question.question());
            List<Long> actual = recalled.stream().map(RecalledMemory::id).toList();
            long tp = actual.stream().filter(question.gold()::contains).count();
            double precision = ratio(tp, actual.size());
            double recall = ratio(tp, question.gold().size());
            if (!Double.isNaN(precision)) {
                precisionSum += precision;
                precisionCount++;
            }
            if (!Double.isNaN(recall)) {
                recallSum += recall;
                recallCount++;
            }
            long tokens = recalled.isEmpty()
                    ? 0
                    : estimator.estimate(json.writeValueAsString(recalled)).estimatedTokens();
            System.out.printf(
                    "| %s | %s | %s | %d | %d | %d | %s | %s | %d |%n",
                    question.questionId(),
                    question.gold(),
                    actual,
                    tp,
                    actual.size() - tp,
                    question.gold().size() - tp,
                    format(precision),
                    format(recall),
                    tokens);
        }
        System.out.printf(
                "Macro Precision %s (%d/%d), Macro Recall %s (%d/%d)%n",
                format(ratio(precisionSum, precisionCount)),
                precisionCount,
                samples.questions().size(),
                format(ratio(recallSum, recallCount)),
                recallCount,
                samples.questions().size());
    }

    private static double ratio(double numerator, int denominator) {
        return denominator == 0 ? Double.NaN : numerator / denominator;
    }

    private static String format(double value) {
        return Double.isNaN(value) ? "N/A" : String.format(Locale.ROOT, "%.4f", value);
    }
}
