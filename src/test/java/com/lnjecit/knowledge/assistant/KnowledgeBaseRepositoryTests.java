package com.lnjecit.knowledge.assistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.lnjecit.knowledge.assistant.infrastructure.persistence.KnowledgeBase;
import com.lnjecit.knowledge.assistant.infrastructure.persistence.KnowledgeBaseMapper;
import com.lnjecit.knowledge.assistant.knowledge.KnowledgeBaseRepository;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

class KnowledgeBaseRepositoryTests {
    @Test
    void createOnlyInsertsAndReturnsSameEntity() {
        KnowledgeBaseMapper mapper = mock(KnowledgeBaseMapper.class);
        when(mapper.insert(any(KnowledgeBase.class))).thenAnswer(invocation -> {
            KnowledgeBase entity = invocation.getArgument(0);
            assertThat(entity.getId()).isNull();
            assertThat(entity.getCreatedAt()).isNotNull();
            // 模拟 Mapper 回填，真实 ASSIGN_ID 由 PostgreSQL 集成测试验证。
            entity.setId(123L);
            return 1;
        });
        KnowledgeBase entity = new KnowledgeBaseRepository(mapper).create("Java", "说明");
        assertThat(entity.getId()).isEqualTo(123L);
        assertThat(entity.getName()).isEqualTo("Java");
        assertThat(entity.getDescription()).isEqualTo("说明");
        verify(mapper).insert(entity);
        verifyNoMoreInteractions(mapper);
    }

    @Test
    void insertFailurePropagatesWithoutQuery() {
        KnowledgeBaseMapper mapper = mock(KnowledgeBaseMapper.class);
        DataAccessResourceFailureException failure = new DataAccessResourceFailureException("测试失败");
        when(mapper.insert(any(KnowledgeBase.class))).thenThrow(failure);
        assertThatThrownBy(() -> new KnowledgeBaseRepository(mapper).create("Java", null))
                .isSameAs(failure);
        verify(mapper).insert(any(KnowledgeBase.class));
        verifyNoMoreInteractions(mapper);
    }
}
