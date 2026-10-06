package com.amazon.sample.assistant.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import reactor.core.publisher.Flux;

/**
 * Formato SSE de los eventos con nombre {@code tool} y {@code cart-updated}
 * (D7 de {@code add-assistant-tools}), con el servicio del turno mockeado.
 */
@WebMvcTest(ChatController.class)
class ChatControllerToolEventsTest {

  @Autowired
  private MockMvc mvc;

  @MockitoBean
  private ChatTurnService turns;

  @Test
  void toolAndCartUpdatedAreNamedEvents() throws Exception {
    Map<String, Object> tool = new LinkedHashMap<>();
    tool.put("tool", "addToCart");
    tool.put("ok", true);
    Map<String, Object> cart = new LinkedHashMap<>();
    cart.put("itemId", "a1");
    cart.put("name", "Aiden Mid-Century Velvet Armchair");
    cart.put("quantity", 1);
    cart.put("unitPrice", 139);
    cart.put("cartItemCount", 3);
    Map<String, Object> search = new LinkedHashMap<>();
    search.put("tool", "searchProducts");
    search.put("ok", true);
    search.put("products", List.of(Map.of("id", "a1")));
    when(turns.open(eq("s-tools"), eq("add it"))).thenReturn(Flux.just(
        ServerSentEvent.builder(List.of()).event("products").build(),
        ServerSentEvent.builder(search).event("tool").build(),
        ServerSentEvent.builder(tool).event("tool").build(),
        ServerSentEvent.builder(cart).event("cart-updated").build(),
        ServerSentEvent.builder(Map.of("text", "Done, Operative.")).build(),
        ServerSentEvent.builder(Map.of()).event("done").build()));

    MvcResult result = mvc.perform(post("/assistant/chat")
            .header("X-Session-ID", "s-tools")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"message\":\"add it\"}"))
        .andExpect(request().asyncStarted())
        .andReturn();
    result.getAsyncResult(5_000);

    assertThat(result.getResponse().getContentType()).startsWith("text/event-stream");
    assertThat(result.getResponse().getContentAsString()).isEqualTo("""
        event:products
        data:[]

        event:tool
        data:{"tool":"searchProducts","ok":true,"products":[{"id":"a1"}]}

        event:tool
        data:{"tool":"addToCart","ok":true}

        event:cart-updated
        data:{"itemId":"a1","name":"Aiden Mid-Century Velvet Armchair","quantity":1,"unitPrice":139,"cartItemCount":3}

        data:{"text":"Done, Operative."}

        event:done
        data:{}

        """);
  }
}
