class Chat {
  open() {
    cy.get("#chat-trigger").should("be.visible").click();
    return cy.get("#chat-modal").should("be.visible");
  }

  input(options) {
    return cy.get("#user-input", options);
  }

  send(message) {
    this.input().should("be.enabled").type(message);
    return cy.get("#send-button").click();
  }

  botMessages(options) {
    return cy.get("#chat-messages .chat-message-bot", options);
  }

  lastBotMessage() {
    return this.botMessages().last();
  }
}

module.exports = Chat;
