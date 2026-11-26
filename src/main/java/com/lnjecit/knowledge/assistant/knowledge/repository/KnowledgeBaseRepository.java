package com.lnjecit.knowledge.assistant.knowledge.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.lnjecit.knowledge.assistant.knowledge.entity.KnowledgeBase;
import com.lnjecit.knowledge.assistant.knowledge.mapper.KnowledgeBaseMapper;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.springframework.stereotype.Repository;

@Repository
public class KnowledgeBaseRepository {
    private final KnowledgeBaseMapper mapper;

    public KnowledgeBaseRepository(KnowledgeBaseMapper mapper) {
        this.mapper = mapper;
    }

    public KnowledgeBase create(long ownerUserId, String name, String description) {
        KnowledgeBase entity = new KnowledgeBase();
        entity.setOwnerUserId(ownerUserId);
        entity.setName(name);
        entity.setDescription(description);
        // 应用生成创建时间，ASSIGN_ID 在插入时生成并回填主键，无需回查。
        entity.setCreatedAt(Instant.now().truncatedTo(ChronoUnit.MICROS));
        entity.setUpdatedAt(entity.getCreatedAt());
        mapper.insert(entity);
        return entity;
    }

    public KnowledgeBase find(Long id) {
        return mapper.selectOne(new LambdaQueryWrapper<KnowledgeBase>()
                .eq(KnowledgeBase::getId, id)
                .isNull(KnowledgeBase::getDeletedAt));
    }

    public KnowledgeBase findByIdAndOwner(Long id, long ownerUserId) {
        if (id == null) return null;
        return mapper.selectOwnedWithCount(ownerUserId, id, null).stream()
                .findFirst()
                .orElse(null);
    }

    public List<KnowledgeBase> findAllByOwner(long ownerUserId) {
        return findAllByOwner(ownerUserId, null);
    }

    public List<KnowledgeBase> findAllByOwner(long ownerUserId, String keyword) {
        String pattern = keyword == null || keyword.isBlank()
                ? null
                : "%" + keyword.strip().replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
        return mapper.selectOwnedWithCount(ownerUserId, null, pattern);
    }

    public boolean update(Long id, long ownerUserId, String name, String description) {
        return mapper.update(activeOwnedUpdate(id, ownerUserId)
                        .set(KnowledgeBase::getName, name)
                        .set(KnowledgeBase::getDescription, description)
                        .setSql("updated_at = GREATEST(updated_at, clock_timestamp())"))
                == 1;
    }

    public boolean lockOwnedActive(Long id, long ownerUserId) {
        return mapper.lockOwnedActive(id, ownerUserId) != null;
    }

    public boolean hasActiveWork(Long id) {
        return mapper.hasActiveWork(id);
    }

    public boolean delete(Long id, long ownerUserId) {
        return mapper.update(activeOwnedUpdate(id, ownerUserId).setSql("deleted_at = clock_timestamp()")) == 1;
    }

    private LambdaUpdateWrapper<KnowledgeBase> activeOwnedUpdate(Long id, long ownerUserId) {
        return new LambdaUpdateWrapper<KnowledgeBase>()
                .eq(KnowledgeBase::getId, id)
                .eq(KnowledgeBase::getOwnerUserId, ownerUserId)
                .isNull(KnowledgeBase::getDeletedAt);
    }
}
