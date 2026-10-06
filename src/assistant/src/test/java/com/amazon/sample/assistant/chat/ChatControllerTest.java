package com.amazon.sample.assistant.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.amazon.sample.assistant.chat.context.ContextRetriever;
import com.amazon.sample.assistant.chat.context.Retrieval;
import com.amazon.sample.assistant.chat.rewrite.Intent;
import com.amazon.sample.assistant.chat.rewrite.QueryRewriter;
import com.amazon.sample.assistant.chat.rewrite.Rewrite;
import com.amazon.sample.assistant.chat.session.SessionStore;
import com.amazon.sample.assistant.chat.session.ShownProduct;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import reactor.core.publisher.Flux;

/** Endpoint {@code POST /assistant/chat} (D1): validación, sesión ocupada y formato SSE. */
@WebMvcTest(ChatController.class)
class ChatControllerTest {

  @TestConfiguration
  static class Config {

    @Bean
    SessionStore sessionStore() {
      return new SessionStore(10, Duration.ofMinutes(30), 100);
    }

    @Bean
    ChatTurnService chatTurnService(SessionStore sessions, QueryRewriter rewriter,
        ContextRetriever retriever, ChatModel chatModel) {
      return ChatTestSupport.service(sessions, rewriter, retriever, chatModel,
          ChatTurnServiceTest.properties(false));
    }
  }

  @Autowired
  private MockMvc mvc;

  @Autowired
  private SessionStore sessions;

  @MockitoBean
  private QueryRewriter rewriter;

  @MockitoBean
  private ContextRetriever retriever;

  @MockitoBean
  private ChatModel chatModel;

  private static MockHttpServletRequestBuilder chat(String sessionId, String body) {
    MockHttpServletRequestBuilder request = post("/assistant/chat")
        .contentType(MediaType.APPLICATION_JSON)
        .accept(MediaType.TEXT_EVENT_STREAM);
    if (sessionId != null) {
      request.header("X-Session-ID", sessionId);
    }
    return body == null ? request : request.content(body);
  }

  private void assertNoProviderWasCalled() {
    verify(rewriter, never()).rewrite(any(), any());
    verify(retriever, never()).retrieve(any(), any());
    verify(chatModel, never()).stream(any(Prompt.class));
    verify(chatModel, never()).call(any(Prompt.class));
  }

  @Test
  void missingSessionHeaderIs400() throws Exception {
    mvc.perform(chat(null, "{\"message\":\"hi\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.type").value("invalid-parameter"))
        .andExpect(jsonPath("$.parameter").value("X-Session-ID"))
        .andExpect(jsonPath("$.detail").value("El header X-Session-ID es obligatorio"));
    assertNoProviderWasCalled();
  }

  @ParameterizedTest
  @ValueSource(strings = {"has spaces", "semi;colon", "ñandú", "dot.ted"})
  void invalidSessionHeaderIs400(String sessionId) throws Exception {
    mvc.perform(chat(sessionId, "{\"message\":\"hi\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.parameter").value("X-Session-ID"));
    assertNoProviderWasCalled();
  }

  @Test
  void tooLongSessionHeaderIs400() throws Exception {
    mvc.perform(chat("a".repeat(129), "{\"message\":\"hi\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.parameter").value("X-Session-ID"));
    assertNoProviderWasCalled();
  }

  @Test
  void emptySessionHeaderIs400() throws Exception {
    mvc.perform(chat("", "{\"message\":\"hi\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.parameter").value("X-Session-ID"));
    assertNoProviderWasCalled();
  }

  @ParameterizedTest
  @ValueSource(strings = {"{\"message\":\"   \"}", "{\"message\":\"\"}", "{}",
      "{\"message\":null}"})
  void blankOrMissingMessageIs400(String body) throws Exception {
    mvc.perform(chat("s1", body))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.type").value("invalid-parameter"))
        .andExpect(jsonPath("$.parameter").value("message"))
        .andExpect(jsonPath("$.detail").value("El campo message es obligatorio"));
    assertNoProviderWasCalled();
  }

  @Test
  void missingOrInvalidBodyIs400() throws Exception {
    mvc.perform(chat("s1", null))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.parameter").value("message"));
    mvc.perform(chat("s1", "not json"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.parameter").value("message"));
    assertNoProviderWasCalled();
  }

  @Test
  void tooLongMessageIs400() throws Exception {
    mvc.perform(chat("s1", "{\"message\":\"" + "a".repeat(2001) + "\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.parameter").value("message"))
        .andExpect(jsonPath("$.detail").value("message no puede superar los 2000 caracteres"));
    assertNoProviderWasCalled();
  }

  @Test
  void messageOf2000CharactersIsAccepted() throws Exception {
    respondNormally();
    MvcResult result = mvc.perform(chat("long-ok", "{\"message\":\"" + "a".repeat(2000) + "\"}"))
        .andExpect(request().asyncStarted())
        .andReturn();
    result.getAsyncResult(5_000);

    assertThat(result.getResponse().getContentAsString()).contains("event:done");
  }

  @Test
  void busySessionIs409() throws Exception {
    sessions.get("busy").tryAcquire();
    try {
      mvc.perform(chat("busy", "{\"message\":\"hi\"}"))
          .andExpect(status().isConflict())
          .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
          .andExpect(jsonPath("$.type").value("session-busy"));
      assertNoProviderWasCalled();
    } finally {
      sessions.get("busy").release();
    }
  }

  @Test
  void successfulTurnIsServerSentEvents() throws Exception {
    respondNormally();

    MvcResult result = mvc.perform(chat("s-ok", "{\"message\":\"a velvet armchair\"}"))
        .andExpect(request().asyncStarted())
        .andReturn();
    result.getAsyncResult(5_000);

    assertThat(result.getResponse().getContentType()).startsWith("text/event-stream");
    String body = result.getResponse().getContentAsString();
    assertThat(body).isEqualTo("""
        event:products
        data:[{"id":"a1","name":"Aiden Mid-Century Velvet Armchair","price":139}]

        data:{"text":"Ah, Operative."}

        data:{"text":" The Aiden is $139."}

        event:done
        data:{}

        """);
  }

  private void respondNormally() {
    when(rewriter.rewrite(any(), any())).thenReturn(
        new Rewrite(Intent.SEARCH, "velvet armchair", null, null, List.of(), false, 1, 5));
    when(retriever.retrieve(any(), any())).thenReturn(new Retrieval(true, false,
        List.of(new ShownProduct("a1", "Aiden Mid-Century Velvet Armchair", "Plush.", 139,
            List.of("seating"))), List.of(), false, 3));
    when(chatModel.stream(any(Prompt.class))).thenReturn(Flux.just(
        ChatTurnServiceTest.fragment("Ah, Operative."),
        ChatTurnServiceTest.fragment(" The Aiden is $139.")));
  }
}
