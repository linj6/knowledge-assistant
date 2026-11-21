package com.lnjecit.knowledge.assistant.knowledge.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lnjecit.knowledge.assistant.knowledge.entity.KnowledgeBase;
import com.lnjecit.knowledge.assistant.knowledge.mapper.KnowledgeBaseMapper;
import java.time.Instant;
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
        entity.setCreatedAt(Instant.now());
        mapper.insert(entity);
        return entity;
    }

    public KnowledgeBase find(Long id) {
        return mapper.selectById(id);
    }

    public KnowledgeBase findByIdAndOwner(Long id, long ownerUserId) {
        return mapper.selectOne(new LambdaQueryWrapper<KnowledgeBase>()
                .eq(KnowledgeBase::getId, id)
                .eq(KnowledgeBase::getOwnerUserId, ownerUserId));
    }

    public List<KnowledgeBase> findAllByOwner(long ownerUserId) {
        return mapper.selectList(new LambdaQueryWrapper<KnowledgeBase>()
                .eq(KnowledgeBase::getOwnerUserId, ownerUserId)
                .orderByAsc(KnowledgeBase::getCreatedAt)
                .orderByAsc(KnowledgeBase::getId));
    }
}
