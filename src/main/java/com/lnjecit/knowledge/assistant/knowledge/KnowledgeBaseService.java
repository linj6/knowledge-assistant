package com.lnjecit.knowledge.assistant.knowledge;

import com.lnjecit.knowledge.assistant.common.error.BusinessException;
import com.lnjecit.knowledge.assistant.common.error.CommonErrorCode;
import com.lnjecit.knowledge.assistant.infrastructure.persistence.KnowledgeBase;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class KnowledgeBaseService {
    private final KnowledgeBaseRepository repository;

    public KnowledgeBaseService(KnowledgeBaseRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public KnowledgeBase create(String name, String description) {
        // 插入和回读全部成功后才提交，数据库异常交由统一处理器处理。
        return repository.create(name.strip(), description == null ? null : description.strip());
    }

    public List<KnowledgeBase> list() {
        return repository.findAll();
    }

    public KnowledgeBase get(Long id) {
        KnowledgeBase entity = repository.find(id);
        if (entity == null) throw new BusinessException(CommonErrorCode.KNOWLEDGE_BASE_NOT_FOUND);
        return entity;
    }
}
