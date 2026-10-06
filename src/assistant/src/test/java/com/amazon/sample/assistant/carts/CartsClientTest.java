package com.amazon.sample.assistant.carts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/** Cliente de {@code carts} de la tool {@code addToCart} (D5 de {@code add-assistant-tools}). */
class CartsClientTest {

  private final RestClient.Builder builder = RestClient.builder().baseUrl("http://carts");
  private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
  private final CartsClient client = new CartsClient(builder.build());

  @Test
  void addItemPostsTheItemToTheCustomerCart() {
    server.expect(requestTo("http://carts/carts/session-1/items"))
        .andExpect(method(HttpMethod.POST))
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
        .andExpect(content().json("""
            {"itemId":"p1","quantity":2,"unitPrice":139}""", true))
        .andRespond(withStatus(HttpStatus.CREATED));

    client.addItem("session-1", "p1", 2, 139);

    server.verify();
  }

  @Test
  void serverErrorOnPostIsNotRetried() {
    server.expect(ExpectedCount.once(), requestTo("http://carts/carts/session-1/items"))
        .andRespond(withServerError());

    assertThatThrownBy(() -> client.addItem("session-1", "p1", 1, 139))
        .isInstanceOf(CartsUnavailableException.class);
    server.verify();
  }

  @Test
  void getCartSumsTheQuantitiesOfAllLines() {
    server.expect(requestTo("http://carts/carts/session-1"))
        .andExpect(method(HttpMethod.GET))
        .andRespond(withSuccess("""
            {"customerId":"session-1","items":[
              {"itemId":"p1","quantity":2,"unitPrice":139},
              {"itemId":"p1","quantity":1,"unitPrice":139},
              {"itemId":"p2","quantity":3,"unitPrice":49}]}""", MediaType.APPLICATION_JSON));

    CartsClient.Cart cart = client.getCart("session-1");

    assertThat(cart.items()).hasSize(3);
    assertThat(cart.itemCount()).isEqualTo(6);
    server.verify();
  }

  @Test
  void getCartErrorIsUnavailable() {
    server.expect(requestTo("http://carts/carts/session-1")).andRespond(withServerError());

    assertThatThrownBy(() -> client.getCart("session-1"))
        .isInstanceOf(CartsUnavailableException.class);
  }
}
