package com.lnjecit.knowledge.assistant.knowledge.service;

import com.lnjecit.knowledge.assistant.common.error.BusinessException;
import com.lnjecit.knowledge.assistant.common.error.CommonErrorCode;
import com.lnjecit.knowledge.assistant.knowledge.entity.KnowledgeBase;
import com.lnjecit.knowledge.assistant.knowledge.repository.KnowledgeBaseRepository;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class KnowledgeBaseService {
    private final KnowledgeBaseRepository repository;

    public KnowledgeBaseService(KnowledgeBaseRepository repository) {
        this.repository = repository;
    }

    public KnowledgeBase create(long ownerUserId, String name, String description) {
        // 单次 INSERT 由数据库保证原子性，异常交由统一处理器处理。
        return repository.create(ownerUserId, name.strip(), description == null ? null : description.strip());
    }

    public List<KnowledgeBase> list(long ownerUserId) {
        return repository.findAllByOwner(ownerUserId);
    }

    public List<KnowledgeBase> list(long ownerUserId, String keyword) {
        return repository.findAllByOwner(ownerUserId, keyword);
    }

    @Transactional
    public KnowledgeBase update(Long id, long ownerUserId, String name, String description) {
        if (!repository.update(id, ownerUserId, name.strip(), description == null ? null : description.strip())) {
            throw new BusinessException(CommonErrorCode.KNOWLEDGE_BASE_NOT_FOUND);
        }
        return requireOwned(id, ownerUserId);
    }

    @Transactional
    public void delete(Long id, long ownerUserId) {
        // 先锁知识库，再检查已提交的导入和问答状态。创建活动记录的入口持有同一行锁。
        lockOwnedActive(id, ownerUserId);
        if (repository.hasActiveWork(id)) {
            throw new BusinessException(CommonErrorCode.KNOWLEDGE_BASE_BUSY);
        }
        if (!repository.delete(id, ownerUserId)) {
            throw new BusinessException(CommonErrorCode.KNOWLEDGE_BASE_NOT_FOUND);
        }
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void lockOwnedActive(Long id, long ownerUserId) {
        if (!repository.lockOwnedActive(id, ownerUserId)) {
            throw new BusinessException(CommonErrorCode.KNOWLEDGE_BASE_NOT_FOUND);
        }
    }

    /** 用户访问知识库时仅查询归属当前账号且未删除的记录。 */
    public KnowledgeBase requireOwned(Long id, long ownerUserId) {
        KnowledgeBase entity = repository.findByIdAndOwner(id, ownerUserId);
        if (entity == null) throw new BusinessException(CommonErrorCode.KNOWLEDGE_BASE_NOT_FOUND);
        return entity;
    }

    /** 供内部业务按 ID 检查知识库是否存在。 */
    public KnowledgeBase get(Long id) {
        KnowledgeBase entity = repository.find(id);
        if (entity == null) throw new BusinessException(CommonErrorCode.KNOWLEDGE_BASE_NOT_FOUND);
        return entity;
    }
}
