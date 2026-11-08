package com.lnjecit.knowledge.assistant.knowledge;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lnjecit.knowledge.assistant.infrastructure.persistence.KnowledgeBase;
import com.lnjecit.knowledge.assistant.infrastructure.persistence.KnowledgeBaseMapper;
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
        mapper.insert(entity);
        // 回读数据库生成的创建时间，与插入处于同一业务事务。
        return mapper.selectById(entity.getId());
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
