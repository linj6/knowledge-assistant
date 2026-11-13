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

    public KnowledgeBase create(String name, String description) {
        KnowledgeBase entity = new KnowledgeBase();
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

    public List<KnowledgeBase> findAll() {
        return mapper.selectList(new LambdaQueryWrapper<KnowledgeBase>()
                .orderByAsc(KnowledgeBase::getCreatedAt)
                .orderByAsc(KnowledgeBase::getId));
    }
}
