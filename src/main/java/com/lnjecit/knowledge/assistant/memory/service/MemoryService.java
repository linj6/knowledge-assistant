package com.lnjecit.knowledge.assistant.memory.service;

import com.lnjecit.knowledge.assistant.auth.entity.User;
import com.lnjecit.knowledge.assistant.auth.repository.UserRepository;
import com.lnjecit.knowledge.assistant.common.error.BusinessException;
import com.lnjecit.knowledge.assistant.common.error.CommonErrorCode;
import com.lnjecit.knowledge.assistant.memory.entity.MemoryType;
import com.lnjecit.knowledge.assistant.memory.entity.UserMemory;
import com.lnjecit.knowledge.assistant.memory.repository.UserMemoryRepository;
import java.time.Instant;
import java.util.List;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MemoryService {
    private static final int MAX_MEMORIES = 50;

    private final UserMemoryRepository memories;
    private final UserRepository users;

    public MemoryService(UserMemoryRepository memories, UserRepository users) {
        this.memories = memories;
        this.users = users;
    }

    public List<UserMemory> list(long userId) {
        return memories.listOwned(userId);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public UserMemory create(long userId, MemoryType type, String content, String keywords) {
        String normalized = validate(type, content, keywords);

        // 同一用户的新增请求先锁定用户行，后获得锁的事务会看到前一个事务已提交的新增记录。
        if (!users.lockById(userId)) {
            throw new BusinessException(CommonErrorCode.NOT_FOUND);
        }
        if (memories.countOwned(userId) >= MAX_MEMORIES) {
            throw new BusinessException(CommonErrorCode.MEMORY_LIMIT_REACHED);
        }

        Instant now = Instant.now();
        UserMemory memory = new UserMemory();
        memory.setUserId(userId);
        memory.setType(type);
        memory.setContent(normalized);
        memory.setKeywords(keywords);
        memory.setSourceType("MANUAL");
        memory.setEnabled(true);
        memory.setCreatedAt(now);
        memory.setUpdatedAt(now);
        try {
            memories.insert(memory);
        } catch (DuplicateKeyException exception) {
            throw new BusinessException(CommonErrorCode.MEMORY_DUPLICATE);
        }
        return memory;
    }

    @Transactional
    public UserMemory update(long userId, long id, MemoryType type, String content, String keywords, boolean enabled) {
        String normalized = validate(type, content, keywords);

        try {
            requireMemory(memories.updateOwned(userId, id, type, normalized, keywords, enabled, Instant.now()));
        } catch (DuplicateKeyException exception) {
            throw new BusinessException(CommonErrorCode.MEMORY_DUPLICATE);
        }
        return memories.findOwned(userId, id);
    }

    public void delete(long userId, long id) {
        requireMemory(memories.deleteOwned(userId, id));
    }

    public boolean getSettings(long userId) {
        User user = users.find(userId);
        if (user == null) {
            throw new BusinessException(CommonErrorCode.NOT_FOUND);
        }
        return user.getMemoryEnabled();
    }

    public boolean updateSettings(long userId, boolean enabled) {
        if (!users.updateMemoryEnabled(userId, enabled)) {
            throw new BusinessException(CommonErrorCode.NOT_FOUND);
        }
        return enabled;
    }

    private static void requireMemory(int affectedRows) {
        if (affectedRows == 0) {
            throw new BusinessException(CommonErrorCode.MEMORY_NOT_FOUND);
        }
    }

    private static String validate(MemoryType type, String content, String keywords) {
        String normalized = content == null ? "" : content.strip();
        if (type == null
                || normalized.isEmpty()
                || normalized.codePointCount(0, normalized.length()) > 500
                || (keywords != null && keywords.codePointCount(0, keywords.length()) > 200)
                || normalized.indexOf(0) >= 0
                || (keywords != null && keywords.indexOf(0) >= 0)) {
            throw new BusinessException(CommonErrorCode.INVALID_ARGUMENT);
        }
        return normalized;
    }
}
