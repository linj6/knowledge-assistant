package com.lnjecit.knowledge.assistant.memory.service;

import com.lnjecit.knowledge.assistant.auth.entity.User;
import com.lnjecit.knowledge.assistant.auth.repository.UserRepository;
import com.lnjecit.knowledge.assistant.common.RequestIdContext;
import com.lnjecit.knowledge.assistant.common.error.BusinessException;
import com.lnjecit.knowledge.assistant.common.error.CommonErrorCode;
import com.lnjecit.knowledge.assistant.infrastructure.ai.TokenEstimator;
import com.lnjecit.knowledge.assistant.memory.dto.RecalledMemory;
import com.lnjecit.knowledge.assistant.memory.entity.UserMemory;
import com.lnjecit.knowledge.assistant.memory.repository.UserMemoryRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.dao.RecoverableDataAccessException;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

@Service
public class MemoryRecallService {
    private static final Logger LOG = LoggerFactory.getLogger(MemoryRecallService.class);

    private final UserMemoryRepository memories;
    private final UserRepository users;
    private final MemoryRecallProperties properties;
    private final TokenEstimator estimator = new TokenEstimator(0);
    private final JsonMapper json = JsonMapper.builder().build();

    public MemoryRecallService(UserMemoryRepository memories, UserRepository users, MemoryRecallProperties properties) {
        this.memories = memories;
        this.users = users;
        this.properties = properties;
    }

    public List<RecalledMemory> recall(long userId, String question) {
        long start = System.nanoTime();
        Boolean memoryEnabled = null;
        int enabledCount = 0;
        int candidateCount = 0;
        long estimatedTokens = 0;
        String error = null;
        List<RecalledMemory> selected = new ArrayList<>();
        try {
            User user = users.find(userId);
            if (user == null) {
                throw new BusinessException(CommonErrorCode.NOT_FOUND);
            }
            memoryEnabled = user.getMemoryEnabled();
            if (!memoryEnabled) {
                return List.of();
            }

            List<UserMemory> enabled;
            try {
                enabled = memories.listEnabledOwned(userId);
            } catch (DataAccessResourceFailureException
                    | QueryTimeoutException
                    | RecoverableDataAccessException
                    | TransientDataAccessResourceException failure) {
                // 仅标记记忆查询的存储故障，身份查询及其他错误仍按原路径失败。
                throw new StorageUnavailableException(failure);
            }
            enabledCount = enabled.size();
            List<UserMemory> candidates = candidates(enabled, question.toLowerCase(Locale.ROOT));
            candidateCount = candidates.size();

            // 按完整 JSON 数组做本地估算，计入字段名、分隔符和转义开销。
            for (UserMemory memory : candidates) {
                selected.add(new RecalledMemory(memory.getId(), memory.getType(), memory.getContent()));
                long proposedTokens =
                        estimator.estimate(json.writeValueAsString(selected)).estimatedTokens();
                if (proposedTokens > properties.getEstimatedTokenBudget()) {
                    selected.removeLast();
                    continue;
                }
                estimatedTokens = proposedTokens;
                if (selected.size() >= properties.getMaxMemories()) {
                    break;
                }
            }
            return List.copyOf(selected);
        } catch (RuntimeException exception) {
            // 仅记录异常类型，异常消息可能带有原始数据。
            error = exception.getClass().getSimpleName();
            throw exception;
        } finally {
            LOG.info(
                    "requestId={} stage=MEMORY_RECALL userId={} memoryEnabled={} enabledCount={} candidateCount={} selectedCount={} estimatedTokens={} elapsedMillis={} error={}",
                    RequestIdContext.current(),
                    userId,
                    memoryEnabled,
                    enabledCount,
                    candidateCount,
                    selected.size(),
                    estimatedTokens,
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start),
                    error);
        }
    }

    public static final class StorageUnavailableException extends RuntimeException {
        private StorageUnavailableException(RuntimeException cause) {
            super(cause);
        }
    }

    private static List<UserMemory> candidates(List<UserMemory> enabled, String question) {
        List<UserMemory> topical = new ArrayList<>();
        List<UserMemory> global = new ArrayList<>();
        // 先放命中的主题记忆，组内沿用查询的更新时间和 ID 倒序。
        for (UserMemory memory : enabled) {
            if (memory.getType().isGlobal()) {
                global.add(memory);
            } else if (matches(memory.getKeywords(), question)) {
                topical.add(memory);
            }
        }
        topical.addAll(global);
        return topical;
    }

    private static boolean matches(String keywords, String question) {
        if (keywords == null) {
            return false;
        }
        for (String keyword : keywords.split(",")) {
            String normalized = keyword.strip().toLowerCase(Locale.ROOT);
            if (!normalized.isEmpty() && question.contains(normalized)) {
                return true;
            }
        }
        return false;
    }
}
