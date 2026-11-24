package com.lnjecit.knowledge.assistant.memory.controller;

import com.lnjecit.knowledge.assistant.auth.service.AuthPrincipal;
import com.lnjecit.knowledge.assistant.common.api.Result;
import com.lnjecit.knowledge.assistant.memory.dto.MemoryRequest;
import com.lnjecit.knowledge.assistant.memory.dto.MemoryResponse;
import com.lnjecit.knowledge.assistant.memory.dto.MemorySettings;
import com.lnjecit.knowledge.assistant.memory.service.MemoryService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1")
public class MemoryController {
    private final MemoryService service;

    public MemoryController(MemoryService service) {
        this.service = service;
    }

    @GetMapping("/memories")
    public Result<List<MemoryResponse>> list(Authentication authentication) {
        return Result.success(service.list(AuthPrincipal.requireUserId(authentication)).stream()
                .map(MemoryResponse::from)
                .toList());
    }

    @PostMapping("/memories")
    public Result<MemoryResponse> create(Authentication authentication, @Valid @RequestBody MemoryRequest request) {
        return Result.success(MemoryResponse.from(service.create(
                AuthPrincipal.requireUserId(authentication), request.type, request.content, request.keywords)));
    }

    @PutMapping("/memories/{id}")
    public Result<MemoryResponse> update(
            Authentication authentication, @PathVariable long id, @Valid @RequestBody MemoryRequest.Update request) {
        return Result.success(MemoryResponse.from(service.update(
                AuthPrincipal.requireUserId(authentication),
                id,
                request.type,
                request.content,
                request.keywords,
                request.enabled)));
    }

    @DeleteMapping("/memories/{id}")
    public Result<Void> delete(Authentication authentication, @PathVariable long id) {
        service.delete(AuthPrincipal.requireUserId(authentication), id);
        return Result.success(null);
    }

    @GetMapping("/memory/settings")
    public Result<MemorySettings> getSettings(Authentication authentication) {
        return Result.success(new MemorySettings(service.getSettings(AuthPrincipal.requireUserId(authentication))));
    }

    @PutMapping("/memory/settings")
    public Result<MemorySettings> updateSettings(
            Authentication authentication, @Valid @RequestBody MemorySettings request) {
        return Result.success(new MemorySettings(
                service.updateSettings(AuthPrincipal.requireUserId(authentication), request.memoryEnabled())));
    }
}
