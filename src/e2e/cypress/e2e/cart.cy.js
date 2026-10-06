import Cart from "../pages/Cart";
import Product from "../pages/Product";

const cart = new Cart();
const product = new Product("bff46bca-ec50-582f-9cde-6d843c1de176");

describe("testing cart", () => {
  beforeEach(() => {
    product.visit();
    product.addToCart().click();
  });

  it("should visit cart", () => {
    cart.visit();
  });

  it("should display items", () => {
    cart.items().its("length").should("eq", 1);

    cart
      .items()
      .first()
      .find(".item-name")
      .should("contain.text", "Aiden Mid-Century Velvet Armchair");

    cart.subtotal().should("contain.text", "$139");
  });

  it("should open checkout", () => {
    cart.checkout().click();

    cy.url().should("contain", "/checkout");
  });
});
