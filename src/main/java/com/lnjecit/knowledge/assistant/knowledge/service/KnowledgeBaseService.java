package com.lnjecit.knowledge.assistant.knowledge.service;

import com.lnjecit.knowledge.assistant.common.error.BusinessException;
import com.lnjecit.knowledge.assistant.common.error.CommonErrorCode;
import com.lnjecit.knowledge.assistant.knowledge.entity.KnowledgeBase;
import com.lnjecit.knowledge.assistant.knowledge.repository.KnowledgeBaseRepository;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class KnowledgeBaseService {
    private final KnowledgeBaseRepository repository;

    public KnowledgeBaseService(KnowledgeBaseRepository repository) {
        this.repository = repository;
    }

    public KnowledgeBase create(String name, String description) {
        // 单次 INSERT 由数据库保证原子性，异常交由统一处理器处理。
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
