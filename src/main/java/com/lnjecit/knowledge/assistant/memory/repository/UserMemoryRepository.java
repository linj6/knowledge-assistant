package com.lnjecit.knowledge.assistant.memory.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.lnjecit.knowledge.assistant.memory.entity.MemoryType;
import com.lnjecit.knowledge.assistant.memory.entity.UserMemory;
import com.lnjecit.knowledge.assistant.memory.mapper.UserMemoryMapper;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Repository;

@Repository
public class UserMemoryRepository {
    private final UserMemoryMapper mapper;

    public UserMemoryRepository(UserMemoryMapper mapper) {
        this.mapper = mapper;
    }

    public List<UserMemory> listOwned(long userId) {
        return mapper.selectList(owned(userId).orderByDesc(UserMemory::getUpdatedAt, UserMemory::getId));
    }

    public List<UserMemory> listEnabledOwned(long userId) {
        return mapper.selectList(owned(userId)
                .eq(UserMemory::getEnabled, true)
                .orderByDesc(UserMemory::getUpdatedAt, UserMemory::getId));
    }

    public long countOwned(long userId) {
        return mapper.selectCount(owned(userId));
    }

    public UserMemory findOwned(long userId, long id) {
        return mapper.selectOne(owned(userId).eq(UserMemory::getId, id));
    }

    public void insert(UserMemory memory) {
        mapper.insert(memory);
    }

    public int updateOwned(
            long userId,
            long id,
            MemoryType type,
            String content,
            String keywords,
            boolean enabled,
            Instant updatedAt) {
        // 显式设置关键词，允许完整更新清除原值。
        return mapper.update(Wrappers.<UserMemory>lambdaUpdate()
                .eq(UserMemory::getUserId, userId)
                .eq(UserMemory::getId, id)
                .set(UserMemory::getType, type.name())
                .set(UserMemory::getContent, content)
                .set(UserMemory::getKeywords, keywords)
                .set(UserMemory::getEnabled, enabled)
                .set(UserMemory::getUpdatedAt, updatedAt));
    }

    public int deleteOwned(long userId, long id) {
        return mapper.delete(owned(userId).eq(UserMemory::getId, id));
    }

    private static LambdaQueryWrapper<UserMemory> owned(long userId) {
        return Wrappers.<UserMemory>lambdaQuery().eq(UserMemory::getUserId, userId);
    }
}
