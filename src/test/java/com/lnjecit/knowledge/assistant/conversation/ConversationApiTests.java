package com.lnjecit.knowledge.assistant.conversation;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.lnjecit.knowledge.assistant.auth.service.AuthPrincipal;
import com.lnjecit.knowledge.assistant.common.error.BusinessException;
import com.lnjecit.knowledge.assistant.common.error.CommonErrorCode;
import com.lnjecit.knowledge.assistant.conversation.controller.ConversationController;
import com.lnjecit.knowledge.assistant.conversation.dto.TurnResponse;
import com.lnjecit.knowledge.assistant.conversation.entity.Conversation;
import com.lnjecit.knowledge.assistant.conversation.entity.Message;
import com.lnjecit.knowledge.assistant.conversation.entity.MessageStatus;
import com.lnjecit.knowledge.assistant.conversation.repository.ConversationRepository;
import com.lnjecit.knowledge.assistant.conversation.service.ConversationService;
import com.lnjecit.knowledge.assistant.conversation.service.TurnService;
import com.lnjecit.knowledge.assistant.infrastructure.web.ApiExceptionHandler;
import com.lnjecit.knowledge.assistant.infrastructure.web.ApiJacksonConfiguration;
import com.lnjecit.knowledge.assistant.knowledge.entity.KnowledgeBase;
import com.lnjecit.knowledge.assistant.knowledge.repository.KnowledgeBaseRepository;
import com.lnjecit.knowledge.assistant.knowledge.service.KnowledgeBaseService;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.json.JsonMapper;

class ConversationApiTests {
    private static final String PATH = "/api/v1/conversations";
    private static final Long ID = 9007199254740993L;

    private final ConversationRepository repository = mock(ConversationRepository.class);
    private final KnowledgeBaseRepository knowledgeBases = mock(KnowledgeBaseRepository.class);
    private final TurnService turns = mock(TurnService.class);
    private MockMvc mvc;

    @BeforeEach
    void setup() {
        JsonMapper.Builder builder = JsonMapper.builder();
        new ApiJacksonConfiguration().longAsString().customize(builder);
        ConversationService service = new ConversationService(repository, new KnowledgeBaseService(knowledgeBases));
        mvc = MockMvcBuilders.standaloneSetup(new ConversationController(service, turns))
                .defaultRequest(get("/").servletPath("/api").principal(principal(42L)))
                .setMessageConverters(new JacksonJsonHttpMessageConverter(builder.build()))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    @Test
    void cancelsTurnUsingCurrentUserAndReturnsStoredStatus() throws Exception {
        UUID turnId = UUID.randomUUID();
        when(turns.cancel(42L, ID, turnId)).thenReturn(new TurnResponse.Accepted(turnId, MessageStatus.ANSWERED));
        mvc.perform(post(PATH + "/" + ID + "/turns/" + turnId + "/cancel").servletPath("/api"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.turnId").value(turnId.toString()))
                .andExpect(jsonPath("$.data.status").value("ANSWERED"));
    }

    @Test
    void submitsAndQueriesTurnUsingCurrentUser() throws Exception {
        UUID turnId = UUID.randomUUID();
        String path = PATH + "/" + ID + "/turns";
        String body = """
                {"turnId":"%s","question":"原始问题"}
                """.formatted(turnId);
        when(turns.submit(42L, ID, turnId, "原始问题"))
                .thenReturn(new TurnResponse.Accepted(turnId, MessageStatus.PROCESSING))
                .thenThrow(new BusinessException(CommonErrorCode.TURN_ID_CONFLICT));
        when(turns.get(42L, ID, turnId))
                .thenReturn(new TurnResponse(
                        turnId,
                        MessageStatus.ANSWERED,
                        "原始问题",
                        "回答",
                        "[{\"evidenceId\":\"E1\"}]",
                        null,
                        ID,
                        ID + 1,
                        Instant.EPOCH,
                        Instant.EPOCH));
        mvc.perform(post(path).contentType("application/json").content(body))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.turnId").value(turnId.toString()))
                .andExpect(jsonPath("$.data.status").value("PROCESSING"));
        mvc.perform(post(path).contentType("application/json").content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(40901));
        mvc.perform(get(path + "/" + turnId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.answer").value("回答"))
                .andExpect(jsonPath("$.data.citations[0].evidenceId").value("E1"))
                .andExpect(jsonPath("$.data.userMessageId").value(ID.toString()));
    }

    @Test
    void createsAndReturnsOwnedConversation() throws Exception {
        when(knowledgeBases.findByIdAndOwner(7L, 42L)).thenReturn(new KnowledgeBase());
        doAnswer(invocation -> {
                    Conversation conversation = invocation.getArgument(0);
                    conversation.setId(ID);
                    when(repository.findOwnedConversation(ID, conversation.getOwnerUserId()))
                            .thenReturn(conversation);
                    when(repository.listOwnedConversations(
                                    conversation.getOwnerUserId(), conversation.getKnowledgeBaseId()))
                            .thenReturn(List.of(conversation));
                    return null;
                })
                .when(repository)
                .insertConversation(any(Conversation.class));
        mvc.perform(post(PATH).contentType("application/json").content("{\"knowledgeBaseId\":\"7\"}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", PATH + "/" + ID))
                .andExpect(jsonPath("$.data.title").value("新会话"))
                .andExpect(jsonPath("$.data.id").value(ID.toString()))
                .andExpect(jsonPath("$.data.knowledgeBaseId").value("7"))
                .andExpect(jsonPath("$.data.createdAt").exists())
                .andExpect(jsonPath("$.data.ownerUserId").doesNotExist());
        mvc.perform(get(PATH + "/{id}", ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(ID.toString()));
        mvc.perform(get(PATH).param("knowledgeBaseId", "7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value(ID.toString()));

        when(knowledgeBases.findByIdAndOwner(8L, 42L)).thenReturn(new KnowledgeBase());
        mvc.perform(get(PATH).param("knowledgeBaseId", "8"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isEmpty());
    }

    @Test
    void hidesForeignAndMissingResources() throws Exception {
        when(repository.findOwnedConversation(ID, 99L)).thenReturn(new Conversation());
        when(knowledgeBases.findByIdAndOwner(7L, 99L)).thenReturn(new KnowledgeBase());
        for (Long id : List.of(ID, 1L)) {
            mvc.perform(get(PATH + "/{id}", id))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value(40402));
            mvc.perform(get(PATH + "/{id}/messages", id))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value(40402));
        }
        mvc.perform(post(PATH).contentType("application/json").content("{\"knowledgeBaseId\":7}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(40401));
        mvc.perform(get(PATH).param("knowledgeBaseId", "7"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(40401));
        org.mockito.Mockito.verify(repository, org.mockito.Mockito.never())
                .listMessages(
                        any(Long.class), org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void slicesMessagesAndReturnsPaginationMetadata() throws Exception {
        when(repository.findOwnedConversation(ID, 42L)).thenReturn(new Conversation());
        Message first = message(10L);
        Message second = message(11L);
        when(repository.listMessages(ID, 2, 0)).thenReturn(List.of(first, second));
        when(repository.listMessages(ID, 2, 1)).thenReturn(List.of(second));
        mvc.perform(get(PATH + "/{id}/messages", ID).param("pageSize", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.page").value(1))
                .andExpect(jsonPath("$.data.pageSize").value(1))
                .andExpect(jsonPath("$.data.hasNext").value(true))
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].id").value("10"))
                .andExpect(jsonPath("$.data.items[0].conversationId").value(ID.toString()))
                .andExpect(jsonPath("$.data.items[0].citations").isArray());
        mvc.perform(get(PATH + "/{id}/messages", ID).param("page", "2").param("pageSize", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.hasNext").value(false))
                .andExpect(jsonPath("$.data.items[0].id").value("11"));
        mvc.perform(get(PATH + "/{id}/messages", ID).param("page", "3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.pageSize").value(20))
                .andExpect(jsonPath("$.data.hasNext").value(false))
                .andExpect(jsonPath("$.data.items").isEmpty());
    }

    @Test
    void rejectsInvalidParametersBeforeRepositoryAccess() throws Exception {
        mvc.perform(post(PATH).contentType("application/json").content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40000));
        mvc.perform(get(PATH)).andExpect(status().isBadRequest());
        mvc.perform(get(PATH).param("knowledgeBaseId", "0")).andExpect(status().isBadRequest());
        mvc.perform(get(PATH + "/invalid")).andExpect(status().isBadRequest());
        mvc.perform(get(PATH + "/0")).andExpect(status().isBadRequest());
        mvc.perform(get(PATH + "/1/messages").param("page", "0")).andExpect(status().isBadRequest());
        mvc.perform(get(PATH + "/1/messages").param("pageSize", "101")).andExpect(status().isBadRequest());
        verifyNoInteractions(repository, knowledgeBases);
    }

    private static UsernamePasswordAuthenticationToken principal(long userId) {
        return UsernamePasswordAuthenticationToken.authenticated(
                new AuthPrincipal(userId, "alice"), null, AuthorityUtils.NO_AUTHORITIES);
    }

    private static Message message(long id) {
        Message message = new Message();
        message.setId(id);
        message.setConversationId(ID);
        message.setCitations("[]");
        return message;
    }
}
