package com.amazon.sample.assistant.carts;

import java.util.List;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Cliente del servicio {@code carts} para la tool {@code addToCart} (D5 de
 * {@code add-assistant-tools}).
 *
 * <p>El {@code POST} que agrega un ítem <b>no se reintenta</b>: no es
 * idempotente, y un reintento después de un timeout podría duplicar la línea.
 * El {@code customerId} va en el path tal como llega: el chat ya validó que
 * solo tiene letras, dígitos, {@code -} y {@code _}.
 */
public class CartsClient {

  /** Cuerpo de {@code POST /carts/{customerId}/items} y línea del carrito. */
  public record Item(String itemId, int quantity, int unitPrice) {
  }

  /** Carrito tal como lo devuelve {@code GET /carts/{customerId}}. */
  public record Cart(String customerId, List<Item> items) {

    public Cart {
      items = items == null ? List.of() : List.copyOf(items);
    }

    /** Unidades del carrito: la suma de las cantidades de todas las líneas. */
    public int itemCount() {
      return items.stream().mapToInt(Item::quantity).sum();
    }
  }

  private final RestClient restClient;

  public CartsClient(RestClient restClient) {
    this.restClient = restClient;
  }

  /**
   * Agrega un ítem al carrito del cliente, sin reintentos.
   *
   * @throws CartsUnavailableException si {@code carts} no responde o responde un error
   */
  public void addItem(String customerId, String itemId, int quantity, int unitPrice) {
    try {
      restClient.post()
          .uri("/carts/{customerId}/items", customerId)
          .body(new Item(itemId, quantity, unitPrice))
          .retrieve()
          .toBodilessEntity();
    } catch (RestClientException e) {
      throw new CartsUnavailableException(
          "POST /carts/{customerId}/items falló: " + e.getMessage(), e);
    }
  }

  /**
   * El carrito del cliente.
   *
   * @throws CartsUnavailableException si {@code carts} no responde o responde un error
   */
  public Cart getCart(String customerId) {
    try {
      Cart cart = restClient.get()
          .uri("/carts/{customerId}", customerId)
          .retrieve()
          .body(Cart.class);
      return cart == null ? new Cart(customerId, List.of()) : cart;
    } catch (RestClientException e) {
      throw new CartsUnavailableException("GET /carts/{customerId} falló: " + e.getMessage(), e);
    }
  }
}
