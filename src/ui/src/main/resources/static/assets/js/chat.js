/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: MIT-0
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this
 * software and associated documentation files (the "Software"), to deal in the Software
 * without restriction, including without limitation the rights to use, copy, modify,
 * merge, publish, distribute, sublicense, and/or sell copies of the Software, and to
 * permit persons to whom the Software is furnished to do so.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED,
 * INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A
 * PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT
 * HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION
 * OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE
 * SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

const ChatUI = {
  elements: null,
  converter: null,
  contextPath: null,
  busy: false,
  // Productos de los eventos products, tool y cart-updated de la
  // conversación visible: id -> {id, name, price}. Alimentan las tarjetas.
  knownProducts: new Map(),
  maxCardsPerMessage: 6,
  productIdPattern:
    /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i,

  init(contextPath) {
    this.elements = {
      chatMessages: document.getElementById("chat-messages"),
      userInput: document.getElementById("user-input"),
      sendButton: document.getElementById("send-button"),
      clearButton: document.getElementById("clear-button"),
      chatTrigger: document.getElementById("chat-trigger"),
      chatModal: document.getElementById("chat-modal"),
      closeChat: document.getElementById("close-chat"),
    };
    this.contextPath = contextPath;
    this.converter = new showdown.Converter();
    this.bindEvents();
    this.appendMessage("bot", "Greetings operative! How can I help you?");

    const chatTriggerContainer = document.getElementById(
      "chat-trigger-container",
    );
    chatTriggerContainer.classList.remove("hidden");
  },

  bindEvents() {
    this.elements.chatTrigger.addEventListener("click", () => this.openChat());
    this.elements.closeChat.addEventListener("click", () => this.closeChat());
    this.elements.chatModal.addEventListener("click", (e) =>
      this.handleModalClick(e),
    );

    this.elements.userInput.addEventListener("keypress", (e) => {
      if (e.key === "Enter" && !e.shiftKey) {
        e.preventDefault();
        this.handleSendMessage();
      }
    });

    this.elements.sendButton.addEventListener("click", () =>
      this.handleSendMessage(),
    );
    this.elements.clearButton.addEventListener("click", () =>
      this.clearMessages(),
    );
  },

  handleSendMessage() {
    const message = this.elements.userInput.value.trim();
    this.elements.userInput.value = "";
    if (message) {
      this.sendMessage(message);
    }
  },

  clearMessages() {
    this.elements.chatMessages.innerHTML = "";
    this.knownProducts.clear();

    this.elements.userInput.value = "";

    this.scrollToBottom();
  },

  openChat() {
    this.elements.chatModal.classList.remove("hidden");
    this.elements.userInput.focus();
  },

  closeChat() {
    this.elements.chatModal.classList.add("hidden");
  },

  handleModalClick(e) {
    if (e.target === this.elements.chatModal) {
      this.closeChat();
    }
  },

  createMessageElement(sender, text) {
    const messageDiv = document.createElement("div");
    messageDiv.className = `flex items-start space-x-3 chat-message chat-message-${sender}`;

    // Create avatar container
    const avatarContainer = document.createElement("div");
    avatarContainer.className = "flex-shrink-0";

    if (sender === "bot") {
      // Bot avatar - using image
      const avatar = document.createElement("img");
      avatar.src = `${this.contextPath}assets/img/chat-avatar-mini.jpg`;
      avatar.alt = "Chat Avatar";
      avatar.className = "w-10 h-10 rounded-full object-cover";
      avatarContainer.appendChild(avatar);
    } else {
      // User avatar - using Font Awesome icon
      const iconContainer = document.createElement("div");
      iconContainer.className =
        "w-10 h-10 rounded-full bg-gray-600 text-white flex items-center justify-center text-xl";

      const icon = document.createElement("i");
      icon.className = "fas fa-user";
      iconContainer.appendChild(icon);
      avatarContainer.appendChild(iconContainer);
    }

    // Create message bubble
    const bubbleDiv = document.createElement("div");
    bubbleDiv.className = `flex-1 rounded-lg p-3 ${
      sender === "bot"
        ? "bg-primary-500 text-white rounded-tl-none"
        : "bg-gray-100 text-gray-800 rounded-tl-none"
    }`;

    // Create message content wrapper
    const contentWrapper = document.createElement("div");
    contentWrapper.className = "flex flex-col";

    // Create message text
    const textP = document.createElement("p");
    textP.className = "message-text";
    textP.textContent = text;
    contentWrapper.appendChild(textP);

    // Create timestamp
    const timestamp = document.createElement("span");
    timestamp.className = `text-xs mt-1 ${
      sender === "bot" ? "text-gray-100" : "text-gray-500"
    }`;
    const time = new Date().toLocaleTimeString([], {
      hour: "2-digit",
      minute: "2-digit",
    });
    timestamp.textContent = time;
    contentWrapper.appendChild(timestamp);

    bubbleDiv.appendChild(contentWrapper);

    // Assemble the message
    messageDiv.appendChild(avatarContainer);
    messageDiv.appendChild(bubbleDiv);

    return messageDiv;
  },
  createLoadingIndicator() {
    const loadingDiv = document.createElement("div");
    loadingDiv.className = "flex justify-center items-center pt-2";

    const spinner = document.createElement("div");
    spinner.className =
      "animate-spin rounded-full h-8 w-8 border-4 border-gray-200 border-t-primary-500";

    loadingDiv.appendChild(spinner);
    return loadingDiv;
  },

  appendMessage(sender, text) {
    const messageDiv = this.createMessageElement(sender, text);
    this.elements.chatMessages.appendChild(messageDiv);
    this.scrollToBottom();
  },

  scrollToBottom() {
    this.elements.chatMessages.scrollTop =
      this.elements.chatMessages.scrollHeight;
  },

  renderMarkdown(element, text) {
    // Sin DOMPurify no se asigna HTML: se muestra el texto plano. Las
    // imágenes también se descartan: el texto lo genera un LLM y una URL de
    // imagen haría que el navegador cargue recursos de terceros.
    if (window.DOMPurify) {
      element.innerHTML = window.DOMPurify.sanitize(
        this.converter.makeHtml(text),
        { FORBID_TAGS: ["img", "style", "form", "input"] },
      );
    } else {
      element.textContent = text;
    }
  },

  updateBotMessage(turn) {
    const textDiv = turn.botMessageDiv.querySelector(".message-text");
    this.renderMarkdown(textDiv, turn.runningText);
    this.attachProductCards(textDiv, turn);
    this.scrollToBottom();
  },

  // Agrega debajo de cada bloque de texto las tarjetas de los productos
  // conocidos que se nombran por primera vez en él. Trabaja sobre el DOM ya
  // sanitizado, así que el markdown no está en el texto.
  attachProductCards(textDiv, turn) {
    if (this.knownProducts.size === 0) return;

    // De mayor a menor longitud, para que un nombre contenido en otro no
    // genere una segunda tarjeta.
    const candidates = [...this.knownProducts.values()]
      .map((product) => ({ product, key: this.normalizeText(product.name) }))
      .filter((candidate) => candidate.key !== "")
      .sort((a, b) => b.key.length - a.key.length);

    const placed = new Set();
    const placements = [];
    const blocks = textDiv.querySelectorAll(
      "p, li, h1, h2, h3, h4, h5, h6, blockquote",
    );

    for (const block of blocks) {
      let text = this.normalizeText(this.ownText(block));
      const found = [];

      for (const { product, key } of candidates) {
        let index = text.indexOf(key);
        if (index < 0) continue;

        const first = index;
        while (index >= 0) {
          text =
            text.slice(0, index) +
            " ".repeat(key.length) +
            text.slice(index + key.length);
          index = text.indexOf(key, index + key.length);
        }
        if (!placed.has(product.id)) {
          found.push({ product, first });
        }
      }

      found.sort((a, b) => a.first - b.first);
      const products = [];
      for (const { product } of found) {
        if (placed.size >= this.maxCardsPerMessage) break;
        placed.add(product.id);
        products.push(product);
      }
      if (products.length > 0) {
        placements.push({ block, products });
      }
    }

    // Se inserta al final, para no alterar el texto de los bloques que
    // todavía no se recorrieron.
    for (const { block, products } of placements) {
      const cards = products.map((product) => this.productCard(turn, product));
      this.insertCards(block, cards);
    }
  },

  // El texto de un ítem de lista sin sus listas anidadas.
  ownText(block) {
    if (block.tagName !== "LI") return block.textContent;
    const copy = block.cloneNode(true);
    copy.querySelectorAll("ul, ol").forEach((list) => list.remove());
    return copy.textContent;
  },

  normalizeText(text) {
    return text
      .toLowerCase()
      .replace(/[‐-―−]/g, "-")
      .replace(/[‘’‚‛′]/g, "'")
      .replace(/[“-‟″]/g, '"')
      .replace(/\s+/g, " ")
      .trim();
  },

  // En un ítem de lista, las tarjetas van dentro del propio li (antes de una
  // lista anidada), para no dejar un elemento suelto dentro de ul/ol.
  insertCards(block, cards) {
    if (block.tagName === "LI") {
      const nested = [...block.children].find(
        (child) => child.tagName === "UL" || child.tagName === "OL",
      );
      for (const card of cards) {
        block.insertBefore(card, nested || null);
      }
      return;
    }

    let anchor = block;
    for (const card of cards) {
      anchor.insertAdjacentElement("afterend", card);
      anchor = card;
    }
  },

  // Cada turno reutiliza el nodo de cada tarjeta entre re-renders: mover un
  // img ya cargado no lo vuelve a pedir ni lo hace parpadear.
  productCard(turn, product) {
    let card = turn.cards.get(product.id);
    if (!card) {
      card = this.createProductCard(product);
      turn.cards.set(product.id, card);
    }
    card.querySelector(".chat-product-card-name").textContent = product.name;
    card.querySelector(".chat-product-card-price").textContent =
      this.formatPrice(product.price);
    return card;
  },

  // Solo createElement y textContent: ningún dato del texto del modelo llega
  // a un src o un href, y las rutas salen del id ya validado.
  createProductCard(product) {
    const card = document.createElement("a");
    card.className = "chat-product-card";
    card.href = `${this.contextPath}catalog/${product.id}`;

    const image = document.createElement("img");
    image.className = "chat-product-card-image";
    image.alt = product.name;
    image.loading = "lazy";
    image.addEventListener("error", () => image.remove(), { once: true });
    image.src = `${this.contextPath}assets/img/products/${product.id}.jpg`;
    card.appendChild(image);

    const info = document.createElement("span");
    info.className = "chat-product-card-info";

    const name = document.createElement("span");
    name.className = "chat-product-card-name";
    info.appendChild(name);

    const price = document.createElement("span");
    price.className = "chat-product-card-price";
    info.appendChild(price);

    card.appendChild(info);
    return card;
  },

  formatPrice(price) {
    return Number.isInteger(price) ? `$${price}` : `$${price.toFixed(2)}`;
  },

  setBusy(busy) {
    this.busy = busy;
    this.elements.userInput.disabled = busy;
    this.elements.sendButton.disabled = busy;
    if (!busy) {
      this.elements.userInput.focus();
    }
  },

  async sendMessage(message) {
    if (!message || this.busy) return;

    this.setBusy(true);
    this.appendMessage("user", message);

    const turn = {
      loadingDiv: this.createLoadingIndicator(),
      botMessageDiv: null,
      runningText: "",
      finished: false,
      cards: new Map(),
    };
    this.elements.chatMessages.appendChild(turn.loadingDiv);
    this.scrollToBottom();

    try {
      await this.processResponse(message, turn);
    } catch (error) {
      console.error("Error:", error);
    } finally {
      if (!turn.finished) {
        this.showError(turn, { type: "assistant-unavailable" });
      }
      turn.loadingDiv.remove();
      this.setBusy(false);
    }
  },

  async processResponse(message, turn) {
    const response = await this.fetchBotResponse(message);

    if (response.status !== 200 || !response.body) {
      throw new Error(`Unexpected chat response status ${response.status}`);
    }

    const reader = response.body.getReader();
    const decoder = new TextDecoder();
    let buffer = "";

    while (!turn.finished) {
      const { value, done } = await reader.read();
      if (done) break;

      buffer += decoder.decode(value, { stream: true }).replace(/\r\n?/g, "\n");

      let separator;
      while (!turn.finished && (separator = buffer.indexOf("\n\n")) >= 0) {
        const block = buffer.slice(0, separator);
        buffer = buffer.slice(separator + 2);

        const event = this.parseEvent(block);
        if (event) {
          this.dispatchEvent(event, turn);
        }
      }
    }

    if (turn.finished) {
      reader.cancel().catch(() => {});
    }
  },

  // Un evento SSE: varias líneas "data:" se unen con "\n", "event:" da el
  // nombre (sin nombre = "message") y las líneas que empiezan con ":" son
  // comentarios (keepalive).
  parseEvent(block) {
    let name = "message";
    const data = [];

    for (const line of block.split("\n")) {
      if (line === "" || line.startsWith(":")) continue;

      const colon = line.indexOf(":");
      const field = colon >= 0 ? line.slice(0, colon) : line;
      let value = colon >= 0 ? line.slice(colon + 1) : "";
      if (value.startsWith(" ")) value = value.slice(1);

      if (field === "event") {
        name = value || "message";
      } else if (field === "data") {
        data.push(value);
      }
    }

    if (data.length === 0) return null;

    return { name, data: data.join("\n") };
  },

  parseData(event) {
    try {
      return JSON.parse(event.data);
    } catch (e) {
      console.error("Error parsing SSE data:", e);
      return null;
    }
  },

  dispatchEvent(event, turn) {
    switch (event.name) {
      case "message": {
        const data = this.parseData(event);
        if (data && typeof data.text === "string") {
          this.ensureBotMessage(turn);
          turn.runningText += data.text;
          this.updateBotMessage(turn);
        }
        break;
      }
      case "products":
        this.rememberProducts(this.parseData(event));
        break;
      case "tool": {
        const data = this.parseData(event);
        if (data) {
          this.rememberProducts(data.products);
        }
        break;
      }
      case "cart-updated": {
        const data = this.parseData(event) || {};
        this.rememberProduct(data.itemId, data.name, data.unitPrice);
        CartUI.handleCartUpdated(data);
        break;
      }
      case "done":
        turn.finished = true;
        break;
      case "error":
        turn.finished = true;
        this.showError(turn, this.parseData(event) || {});
        break;
      default:
        // Los eventos desconocidos no se muestran.
        break;
    }
  },

  rememberProducts(products) {
    if (!Array.isArray(products)) return;
    for (const product of products) {
      if (product && typeof product === "object") {
        this.rememberProduct(product.id, product.name, product.price);
      }
    }
  },

  // Solo entran ids UUID, con nombre y precio válidos: así las rutas de la
  // imagen y de la ficha no pueden apuntar fuera de la tienda. Un id repetido
  // reemplaza al anterior (el precio de las tools es el vivo).
  rememberProduct(id, name, price) {
    if (typeof id !== "string" || !this.productIdPattern.test(id)) return;
    if (typeof name !== "string" || name.trim() === "") return;
    if (typeof price !== "number" || !Number.isFinite(price) || price < 0) {
      return;
    }
    const key = id.toLowerCase();
    this.knownProducts.set(key, { id: key, name: name.trim(), price });
  },

  ensureBotMessage(turn) {
    if (!turn.botMessageDiv) {
      turn.loadingDiv.remove();
      turn.botMessageDiv = this.createMessageElement("bot", "");
      this.elements.chatMessages.appendChild(turn.botMessageDiv);
    }
  },

  errorMessage(error) {
    switch (error.type) {
      case "llm-quota-exceeded":
        return Number.isFinite(error.retryAfterSeconds)
          ? `Our operatives are overloaded, try again in ~${error.retryAfterSeconds} seconds.`
          : "Our operatives are overloaded, try again in a few moments.";
      case "session-busy":
        return "I'm still working on your previous request. Wait for the current answer and try again.";
      case "invalid-parameter":
        return "I can't process that message. Keep it under 2000 characters and try again.";
      case "assistant-unavailable":
        return "The assistant is unavailable right now. Please try again later.";
      default:
        return "Sorry, there was an error processing your message. Please try again.";
    }
  },

  // El aviso se agrega debajo del texto ya recibido, sin borrarlo.
  showError(turn, error) {
    turn.finished = true;
    this.ensureBotMessage(turn);

    const errorP = document.createElement("p");
    errorP.className = "chat-error italic";
    if (turn.runningText) {
      errorP.className += " mt-2";
    }
    errorP.textContent = this.errorMessage(error);

    const textDiv = turn.botMessageDiv.querySelector(".message-text");
    textDiv.insertAdjacentElement("afterend", errorP);
    this.scrollToBottom();
  },

  async fetchBotResponse(message) {
    return await fetch(`${this.contextPath}chat/submit`, {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        Accept: "text/event-stream",
      },
      body: JSON.stringify({ message }),
    });
  },
};
// Refleja en la página los cambios de carrito hechos desde el chat.
const CartUI = {
  refreshTimer: null,
  refreshDelayMs: 300,

  handleCartUpdated(data) {
    const count = data.cartItemCount;
    if (Number.isFinite(count)) {
      this.setCount(count);
    }
    if (!Number.isFinite(count) || document.getElementById("basket")) {
      this.scheduleRefresh();
    }
  },

  setCount(count) {
    const counter = document.getElementById("cart-count");
    if (counter) {
      counter.textContent = count;
    }
  },

  scheduleRefresh() {
    clearTimeout(this.refreshTimer);
    this.refreshTimer = setTimeout(() => this.refresh(), this.refreshDelayMs);
  },

  async refresh() {
    try {
      const response = await fetch(`${ChatUI.contextPath}cart`, {
        headers: { Accept: "text/html" },
      });
      if (!response.ok) return;

      const html = await response.text();
      const page = new DOMParser().parseFromString(html, "text/html");

      const basket = document.getElementById("basket");
      const newBasket = page.getElementById("basket");
      if (basket && newBasket) {
        basket.replaceWith(document.importNode(newBasket, true));
      }

      const newCount = page.getElementById("cart-count");
      if (newCount) {
        this.setCount(newCount.textContent.trim());
      }
    } catch (error) {
      console.error("Error refreshing cart:", error);
    }
  },
};

document.addEventListener("DOMContentLoaded", () => {
  ChatUI.init(retailContextPath);
});
