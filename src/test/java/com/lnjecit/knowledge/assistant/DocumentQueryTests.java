package com.lnjecit.knowledge.assistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.lnjecit.knowledge.assistant.common.error.BusinessException;
import com.lnjecit.knowledge.assistant.document.entity.Document;
import com.lnjecit.knowledge.assistant.document.entity.DocumentChunk;
import com.lnjecit.knowledge.assistant.document.entity.IndexVersion;
import com.lnjecit.knowledge.assistant.document.entity.IndexVersionStatus;
import com.lnjecit.knowledge.assistant.document.repository.DocumentQueryRepository;
import com.lnjecit.knowledge.assistant.document.service.DocumentQueryService;
import com.lnjecit.knowledge.assistant.knowledge.service.KnowledgeBaseService;
import java.util.List;
import org.junit.jupiter.api.Test;

class DocumentQueryTests {
    private final DocumentQueryRepository repository = mock(DocumentQueryRepository.class);
    private final DocumentQueryService service = new DocumentQueryService(repository, mock(KnowledgeBaseService.class));

    @Test
    void invalidPagination() {
        assertThatThrownBy(() -> service.list(1L, 0, 20)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.list(1L, 1, 101)).isInstanceOf(BusinessException.class);
    }

    @Test
    void emptyPage() {
        when(repository.page(1L, 0, 20)).thenReturn(List.of());
        assertThat(service.list(1L, 1, 20).items()).isEmpty();
    }

    @Test
    void metadataDoesNotControlVisibility() {
        Document d = new Document();
        d.setId(10L);
        d.setActiveVersionId(20L);
        IndexVersion v = new IndexVersion();
        v.setId(20L);
        v.setDocumentId(10L);
        v.setStatus(IndexVersionStatus.VERIFIED);
        when(repository.page(1L, 0, 20)).thenReturn(List.of(d));
        when(repository.versions(List.of(20L))).thenReturn(List.of(v));
        when(repository.tasks(List.of(10L))).thenReturn(List.of());
        assertThat(service.list(1L, 1, 20).items().getFirst().publicationStatus())
                .isEqualTo("PUBLISHED");
        assertThat(service.list(1L, 1, 20).items().getFirst().publishedAt()).isNull();
    }

    @Test
    void brokenPointerRejected() {
        Document d = new Document();
        d.setId(10L);
        d.setActiveVersionId(20L);
        when(repository.page(1L, 0, 20)).thenReturn(List.of(d));
        when(repository.versions(List.of(20L))).thenReturn(List.of());
        when(repository.tasks(List.of(10L))).thenReturn(List.of());
        assertThatThrownBy(() -> service.list(1L, 1, 20)).isInstanceOf(BusinessException.class);
    }

    @Test
    void evidenceChecksOwnershipAndMetadata() {
        Document d = new Document();
        d.setId(10L);
        d.setKnowledgeBaseId(1L);
        d.setFilename("notes.md");
        d.setActiveVersionId(20L);
        IndexVersion v = new IndexVersion();
        v.setId(20L);
        v.setDocumentId(10L);
        v.setStatus(IndexVersionStatus.VERIFIED);
        DocumentChunk c = new DocumentChunk();
        c.setId(30L);
        c.setKnowledgeBaseId(1L);
        c.setDocumentId(10L);
        c.setIndexVersionId(20L);
        c.setContent("正文");
        c.setHeadingPath("标题");
        c.setSourceStart(0);
        c.setSourceEnd(2);
        when(repository.document(10L)).thenReturn(d);
        when(repository.versions(List.of(20L))).thenReturn(List.of(v));
        when(repository.chunk(30L)).thenReturn(c);
        assertThat(service.evidence(1L, 10L, 20L, 30L).content()).isEqualTo("正文");
        assertThat(service.evidence(1L, 10L, 20L, 30L).filename()).isEqualTo("notes.md");
        assertThat(service.evidence(1L, 10L, 20L, 30L).headingPath()).isEqualTo("标题");
        assertThatThrownBy(() -> service.evidence(1L, 10L, 21L, 30L)).isInstanceOf(BusinessException.class);
        c.setKnowledgeBaseId(2L);
        assertThatThrownBy(() -> service.evidence(1L, 10L, 20L, 30L)).isInstanceOf(BusinessException.class);
        c.setKnowledgeBaseId(1L);
        c.setDocumentId(11L);
        assertThatThrownBy(() -> service.evidence(1L, 10L, 20L, 30L)).isInstanceOf(BusinessException.class);
        c.setDocumentId(10L);
        c.setIndexVersionId(21L);
        assertThatThrownBy(() -> service.evidence(1L, 10L, 20L, 30L)).isInstanceOf(BusinessException.class);
        c.setIndexVersionId(20L);
        c.setSourceEnd(0);
        assertThatThrownBy(() -> service.evidence(1L, 10L, 20L, 30L)).isInstanceOf(BusinessException.class);
    }
}
