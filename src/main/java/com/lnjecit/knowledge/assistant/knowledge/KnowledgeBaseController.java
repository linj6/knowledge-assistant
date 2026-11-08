package com.lnjecit.knowledge.assistant.knowledge;

import com.lnjecit.knowledge.assistant.common.error.BusinessException;
import com.lnjecit.knowledge.assistant.common.error.CommonErrorCode;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/knowledge-bases")
public class KnowledgeBaseController {
    private final KnowledgeBaseService service;

    public KnowledgeBaseController(KnowledgeBaseService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<KnowledgeBaseResponse> create(@Valid @RequestBody CreateKnowledgeBaseRequest request) {
        KnowledgeBaseResponse response =
                KnowledgeBaseResponse.from(service.create(request.name(), request.description()));
        return ResponseEntity.created(URI.create("/api/v1/knowledge-bases/" + response.id()))
                .body(response);
    }

    @GetMapping
    public List<KnowledgeBaseResponse> list() {
        return service.list().stream().map(KnowledgeBaseResponse::from).toList();
    }

    @GetMapping("/{id}")
    public KnowledgeBaseResponse get(@PathVariable String id) {
        Long value;
        // 仅接受正十进制 Long，拒绝符号、溢出及非数字字符。
        try {
            if (!id.matches("[0-9]+")) throw new NumberFormatException();
            value = Long.valueOf(id);
            if (value <= 0) throw new NumberFormatException();
        } catch (NumberFormatException ex) {
            throw new BusinessException(CommonErrorCode.INVALID_ARGUMENT);
        }
        return KnowledgeBaseResponse.from(service.get(value));
    }
}
