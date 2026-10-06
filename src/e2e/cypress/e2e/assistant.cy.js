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
});
