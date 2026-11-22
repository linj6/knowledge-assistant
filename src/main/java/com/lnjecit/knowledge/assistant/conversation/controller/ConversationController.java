package com.lnjecit.knowledge.assistant.conversation.controller;

import com.lnjecit.knowledge.assistant.auth.service.AuthPrincipal;
import com.lnjecit.knowledge.assistant.common.api.Result;
import com.lnjecit.knowledge.assistant.conversation.dto.ConversationResponse;
import com.lnjecit.knowledge.assistant.conversation.dto.CreateConversationRequest;
import com.lnjecit.knowledge.assistant.conversation.dto.MessagePageResponse;
import com.lnjecit.knowledge.assistant.conversation.dto.SubmitTurnRequest;
import com.lnjecit.knowledge.assistant.conversation.dto.TurnResponse;
import com.lnjecit.knowledge.assistant.conversation.entity.Message;
import com.lnjecit.knowledge.assistant.conversation.service.ConversationService;
import com.lnjecit.knowledge.assistant.conversation.service.TurnService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Positive;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

@RestController
@RequestMapping("/v1/conversations")
public class ConversationController {
    private final ConversationService service;

    private final TurnService turns;

    public ConversationController(ConversationService service, TurnService turns) {
        this.service = service;
        this.turns = turns;
    }

    @PostMapping("/{id}/turns")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Result<TurnResponse.Accepted> submitTurn(
            @PathVariable @Positive Long id,
            @Valid @RequestBody SubmitTurnRequest request,
            Authentication authentication) {
        return Result.success(
                turns.submit(AuthPrincipal.requireUserId(authentication), id, request.turnId(), request.question()));
    }

    @GetMapping("/{id}/turns/{turnId}")
    public Result<TurnResponse> getTurn(
            @PathVariable @Positive Long id, @PathVariable UUID turnId, Authentication authentication) {
        return Result.success(turns.get(AuthPrincipal.requireUserId(authentication), id, turnId));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Result<ConversationResponse> create(
            @Valid @RequestBody CreateConversationRequest request,
            Authentication authentication,
            HttpServletRequest httpRequest,
            HttpServletResponse httpResponse) {
        ConversationResponse response = ConversationResponse.from(
                service.create(AuthPrincipal.requireUserId(authentication), request.knowledgeBaseId()));
        httpResponse.setHeader(
                "Location",
                ServletUriComponentsBuilder.fromRequestUri(httpRequest)
                        .path("/{id}")
                        .buildAndExpand(response.id())
                        .toUri()
                        .getRawPath());
        return Result.success(response);
    }

    @GetMapping
    public Result<List<ConversationResponse>> list(
            @RequestParam @Positive Long knowledgeBaseId, Authentication authentication) {
        return Result.success(service.list(AuthPrincipal.requireUserId(authentication), knowledgeBaseId).stream()
                .map(ConversationResponse::from)
                .toList());
    }

    @GetMapping("/{id}")
    public Result<ConversationResponse> get(@PathVariable @Positive Long id, Authentication authentication) {
        return Result.success(
                ConversationResponse.from(service.requireOwned(AuthPrincipal.requireUserId(authentication), id)));
    }

    @GetMapping("/{id}/messages")
    public Result<MessagePageResponse> messages(
            @PathVariable @Positive Long id,
            @RequestParam(defaultValue = "1") @Positive int page,
            @RequestParam(defaultValue = "20") @Positive @Max(100) int pageSize,
            Authentication authentication) {
        List<Message> messages = service.listMessages(AuthPrincipal.requireUserId(authentication), id, page, pageSize);
        return Result.success(new MessagePageResponse(
                page,
                pageSize,
                messages.size() > pageSize,
                messages.stream()
                        .limit(pageSize)
                        .map(MessagePageResponse.Item::from)
                        .toList()));
    }
}
