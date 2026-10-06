import Page from "./Page";

class Product extends Page {
  constructor(id) {
    super();
    this.id = id;
    this.url = `/catalog/${id}`;
  }

  addToCart() {
    return cy.get("#add-to-cart");
  }

  // Sección de productos similares del assistant. Puede no estar (por ejemplo,
  // en CI sin claves el índice está vacío y el assistant responde 503).
  similarSection() {
    return cy.get("body").then(($body) => $body.find("#similar-products"));
  }

  similarProducts() {
    return cy.get("#similar-products .similar-product");
  }
}

module.exports = Product;
