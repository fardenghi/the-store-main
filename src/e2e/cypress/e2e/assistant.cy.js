import Cart from "../pages/Cart";
import Chat from "../pages/Chat";
import Product from "../pages/Product";

// Pasan con y sin claves de proveedores: sin claves, la ficha no tiene
// similares y el chat termina con un aviso de error del assistant.
const product = new Product("bff46bca-ec50-582f-9cde-6d843c1de176");

describe("testing assistant integration", () => {
  it("should show product page with similar products and add to cart", () => {
    product.visit();

    product.similarSection().then(($section) => {
      if ($section.length === 0) {
        return;
      }
      product.similarProducts().its("length").should("be.lte", 4);
      product
        .similarProducts()
        .find(`a[href$="/catalog/${product.id}"]`)
        .should("not.exist");
    });

    product.addToCart().click();

    cy.url().should("contain", "/cart");
    new Cart().items().its("length").should("be.gte", 1);
  });

  it("should answer in the chat without raw JSON", () => {
    const chat = new Chat();

    product.visit();
    chat.open();
    chat.botMessages().its("length").should("eq", 1);

    chat.send("I need a lamp for my desk");

    // El turno termina cuando el input se vuelve a habilitar (done o error).
    chat.botMessages({ timeout: 30000 }).should("have.length", 2);
    chat.input({ timeout: 30000 }).should("be.enabled");

    chat
      .lastBotMessage()
      .find(".message-text, .chat-error")
      .invoke("text")
      .then((text) => {
        const shown = text.trim();
        expect(shown).not.to.equal("");
        expect(shown.startsWith("{")).to.equal(false);
        expect(shown).not.to.contain('"text":');
      });
  });

  // Stream simulado: no depende del modelo ni de las claves.
  it("should show a card for each product the answer names", () => {
    const chat = new Chat();
    const armchair = {
      id: "bff46bca-ec50-582f-9cde-6d843c1de176",
      name: "Aiden Mid-Century Velvet Armchair",
      price: 139,
    };
    const lamp = {
      id: "7c931d3b-2776-521b-9bd1-a04c1f2b95c9",
      name: "Curved Brass and Walnut Desk Lamp",
      price: 149,
    };
    const invalid = { id: "../../admin", name: "Shadow Admin Console", price: 1 };

    const sse = (name, data) =>
      `${name ? `event: ${name}\n` : ""}data: ${JSON.stringify(data)}\n\n`;
    const body = [
      sse("products", [armchair, lamp, invalid]),
      sse(null, { text: "For your reading corner, the " }),
      sse(null, { text: "**aiden mid-century velvet armchair** is deep and soft." }),
      sse(null, {
        text: "\n\nSkip the Shadow Admin Console. ![lamp](https://example.com/lamp.jpg)",
      }),
      sse("done", {}),
    ].join("");

    cy.intercept("POST", "**/chat/submit", {
      statusCode: 200,
      headers: { "content-type": "text/event-stream" },
      body,
    }).as("chat");

    product.visit();
    chat.open();
    chat.send("somewhere cozy to curl up with a book");
    cy.wait("@chat");
    chat.input({ timeout: 10000 }).should("be.enabled");

    chat.productCards().should("have.length", 1);
    chat
      .productCards()
      .first()
      .should("have.attr", "href")
      .and("match", new RegExp(`/catalog/${armchair.id}$`));
    chat
      .productCards()
      .first()
      .find("img")
      .should("have.attr", "src")
      .and("match", new RegExp(`/assets/img/products/${armchair.id}\\.jpg$`));
    chat
      .productCards()
      .first()
      .should("contain.text", armchair.name)
      .and("contain.text", "$139")
      .prev("p")
      .should("contain.text", "reading corner");

    chat
      .lastBotMessage()
      .find(`.chat-product-card[href$="/catalog/${lamp.id}"]`)
      .should("not.exist");
    chat
      .lastBotMessage()
      .find('.chat-product-card[href*="admin"]')
      .should("not.exist");
    chat
      .lastBotMessage()
      .find(".message-text")
      .should("contain.text", "Skip the Shadow Admin Console.");
    // Las únicas imágenes del mensaje son las de las tarjetas: la del
    // markdown del modelo no se renderiza.
    chat
      .lastBotMessage()
      .find(".message-text img:not(.chat-product-card-image)")
      .should("not.exist");
  });
});
