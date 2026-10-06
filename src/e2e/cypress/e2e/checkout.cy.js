import CheckoutAddress from "../pages/CheckoutAddress";
import CheckoutOrder from "../pages/CheckoutOrder";
import Cart from "../pages/Cart";
import Product from "../pages/Product";

const cart = new Cart();
const checkout = new CheckoutAddress();
const checkoutOrder = new CheckoutOrder();
const product1 = new Product("bff46bca-ec50-582f-9cde-6d843c1de176");
const product2 = new Product("2c5a89d4-9ce2-5d70-9058-aaef9514d5e7");

describe("testing checkout", () => {
  describe("single product", () => {
    beforeEach(() => {
      product1.visit();
      product1.addToCart().click();
      cart.checkout().click();
    });

    it("should visit checkout", () => {
      checkout.visit();
    });

    it("should process order", () => {
      checkout.visit();

      new CheckoutAddress().submit();

      new CheckoutAddress().submit();

      new CheckoutAddress().submit();

      checkoutOrder.shipping().should("contain.text", "$10");
      checkoutOrder.tax().should("contain.text", "$5");
      checkoutOrder.subtotal().should("contain.text", "$139");
      checkoutOrder.total().should("contain.text", "$154");
    });
  });

  describe("multiple products", () => {
    beforeEach(() => {
      product1.visit();
      product1.addToCart().click();
      product2.visit();
      product2.addToCart().click();
      cart.checkout().click();
    });

    it("should visit checkout", () => {
      checkout.visit();
    });

    it("should process order", () => {
      checkout.visit();

      new CheckoutAddress().submit();

      new CheckoutAddress().submit();

      new CheckoutAddress().submit();

      checkoutOrder.shipping().should("contain.text", "$10");
      checkoutOrder.tax().should("contain.text", "$5");
      checkoutOrder.subtotal().should("contain.text", "$758");
      checkoutOrder.total().should("contain.text", "$773");
    });
  });
});
