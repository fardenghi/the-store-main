package com.amazon.sample.assistant.chat.rewrite;

import com.amazon.sample.assistant.chat.llm.ChatProviderErrors;
import com.amazon.sample.assistant.chat.session.SessionState;
import com.amazon.sample.assistant.chat.session.ShownProduct;
import com.amazon.sample.assistant.chat.session.Turn;
import com.amazon.sample.assistant.config.ChatProperties;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.core.io.Resource;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Reescribe el mensaje del usuario como una consulta de búsqueda autocontenida
 * con el modelo compacto (D4): recibe los últimos turnos, los productos del
 * turno anterior, los tags del catálogo y el mensaje, y devuelve la intención,
 * la consulta y los filtros de precio y de exclusión.
 *
 * <p>Si el modelo falla, tarda más que {@code rewrite.timeout} o devuelve algo
 * que no valida, el turno sigue con el mensaje crudo y sin filtros
 * ({@link Rewrite#fallback}). Nunca lanza excepciones.
 */
public class QueryRewriter {

  private static final Logger log = LoggerFactory.getLogger(QueryRewriter.class);

  static final int MAX_QUERY_LENGTH = 200;
  static final int MAX_HISTORY_ANSWER_LENGTH = 500;

  private final ChatClient rewriteChatClient;
  private final CatalogTagsCache tags;
  private final ChatProperties.Rewrite properties;
  private final String template;

  public QueryRewriter(ChatClient rewriteChatClient, CatalogTagsCache tags,
      ChatProperties.Rewrite properties, Resource template) {
    this.rewriteChatClient = rewriteChatClient;
    this.tags = tags;
    this.properties = properties;
    this.template = read(template);
  }

  /** Reescribe el mensaje con el contexto de la sesión. */
  public Rewrite rewrite(String message, SessionState session) {
    long start = System.nanoTime();
    List<String> tagNames = tags.tagNames();
    String instructions = renderPrompt(session, tagNames);
    Duration timeout = properties.timeout();
    RewriteResult result;
    try {
      // El mensaje va aparte, como mensaje de usuario: embebido en las
      // instrucciones, el modelo clasificaba los saludos como búsquedas.
      result = Mono.fromCallable(() -> rewriteChatClient.prompt()
              .system(instructions)
              .user(message)
              .call()
              .entity(new RewriteConverter()))
          .subscribeOn(Schedulers.boundedElastic())
          .timeout(timeout)
          .block();
    } catch (RuntimeException e) {
      long millis = elapsed(start);
      Throwable cause = reactor.core.Exceptions.unwrap(e);
      if (cause instanceof java.util.concurrent.TimeoutException) {
        log.warn("La reescritura excedió su tiempo límite ({} ms); se usa el mensaje crudo",
            timeout.toMillis());
      } else if (cause instanceof InvalidOutputException invalid) {
        log.warn("La reescritura devolvió un JSON inválido; se usa el mensaje crudo: {}",
            truncate(invalid.output(), 300));
      } else {
        log.warn("La reescritura falló ({}); se usa el mensaje crudo",
            ChatProviderErrors.describe(cause));
      }
      return Rewrite.fallback(message, 1, millis);
    }
    long millis = elapsed(start);
    Rewrite validated = validate(result, message, new HashSet<>(tagNames), millis);
    if (validated.fallback()) {
      log.warn("La reescritura devolvió una salida inválida ({}); se usa el mensaje crudo",
          result);
    }
    return validated;
  }

  /**
   * Instrucciones de la reescritura (system) con el historial, los productos
   * previos y los tags. El mensaje actual va como mensaje de usuario.
   */
  String renderPrompt(SessionState session, List<String> tagNames) {
    List<Turn> history = session.lastTurns(properties.historyTurns());
    String historyText = history.isEmpty() ? "(no previous turns)" : history.stream()
        .map(turn -> "User: " + turn.user() + "\nAssistant: "
            + truncate(turn.assistant(), MAX_HISTORY_ANSWER_LENGTH))
        .collect(Collectors.joining("\n"));
    List<ShownProduct> previous = session.lastProducts();
    String previousText = previous.isEmpty() ? "(none)" : previous.stream()
        .map(p -> "- " + p.name() + " | $" + p.price() + " | tags: " + String.join(", ", p.tags()))
        .collect(Collectors.joining("\n"));
    String tagsText = tagNames.isEmpty() ? "(not available)" : String.join(", ", tagNames);
    return PromptTemplate.builder()
        .template(template)
        .variables(Map.of(
            "history", historyText,
            "previousProducts", previousText,
            "tags", tagsText))
        .build()
        .render();
  }

  /**
   * Valida la salida del modelo. Cualquier inconsistencia (intención
   * desconocida, consulta vacía o demasiado larga, precios negativos o
   * invertidos, tags a excluir que no existen) descarta toda la reescritura.
   * Si la lista de tags no se pudo leer, los tags a excluir no se pueden
   * validar y se ignoran.
   */
  static Rewrite validate(RewriteResult result, String message, Set<String> knownTags,
      long millis) {
    if (result == null) {
      return Rewrite.fallback(message, 1, millis);
    }
    Intent intent = Intent.parse(result.intent());
    if (intent == null) {
      return Rewrite.fallback(message, 1, millis);
    }
    String query = result.query() == null ? "" : result.query().trim();
    if (intent != Intent.OTHER && (query.isEmpty() || query.length() > MAX_QUERY_LENGTH)) {
      return Rewrite.fallback(message, 1, millis);
    }
    Integer minPrice = result.minPrice();
    Integer maxPrice = result.maxPrice();
    if ((minPrice != null && minPrice < 0) || (maxPrice != null && maxPrice < 0)
        || (minPrice != null && maxPrice != null && minPrice > maxPrice)) {
      return Rewrite.fallback(message, 1, millis);
    }
    Set<String> exclude = new LinkedHashSet<>();
    if (result.excludeTags() != null) {
      for (String tag : result.excludeTags()) {
        if (tag != null && !tag.isBlank()) {
          exclude.add(tag.trim().toLowerCase(Locale.ROOT));
        }
      }
    }
    if (!knownTags.isEmpty() && !knownTags.containsAll(exclude)) {
      return Rewrite.fallback(message, 1, millis);
    }
    List<String> excludeTags = knownTags.isEmpty() ? List.of() : new ArrayList<>(exclude);
    return new Rewrite(intent, query, minPrice, maxPrice, excludeTags, false, 1, millis);
  }

  /** Salida del modelo que no se pudo convertir a {@link RewriteResult}. */
  static final class InvalidOutputException extends RuntimeException {

    private final String output;

    InvalidOutputException(String output, Throwable cause) {
      super("Salida de la reescritura inválida", cause);
      this.output = output;
    }

    String output() {
      return output;
    }
  }

  /**
   * {@link BeanOutputConverter} de {@link RewriteResult} que conserva la salida
   * cruda cuando no es un JSON válido, para poder loguearla.
   */
  static final class RewriteConverter extends BeanOutputConverter<RewriteResult> {

    RewriteConverter() {
      super(RewriteResult.class);
    }

    @Override
    public RewriteResult convert(String text) {
      try {
        return super.convert(text);
      } catch (RuntimeException e) {
        throw new InvalidOutputException(text, e);
      }
    }
  }

  static String truncate(String text, int max) {
    if (text == null || text.length() <= max) {
      return text;
    }
    return text.substring(0, max) + "…";
  }

  private static long elapsed(long start) {
    return (System.nanoTime() - start) / 1_000_000;
  }

  private static String read(Resource resource) {
    try {
      return resource.getContentAsString(StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException("No se pudo leer el prompt " + resource, e);
    }
  }
}
