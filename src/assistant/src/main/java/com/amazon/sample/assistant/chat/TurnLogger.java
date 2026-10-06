package com.amazon.sample.assistant.chat;

import com.amazon.sample.assistant.chat.session.ShownProduct;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Una línea de log por turno en formato {@code clave=valor}, con el logger
 * {@code assistant.turn} (D9). Incluye el mensaje crudo y la consulta
 * reescrita con los ids de ambos top-k, para hacer observable la mejora de la
 * reescritura.
 */
class TurnLogger {

  static final String LOGGER_NAME = "assistant.turn";

  private static final Logger log = LoggerFactory.getLogger(LOGGER_NAME);

  void log(TurnStats stats, int providerRequests) {
    log.info(format(stats, providerRequests));
  }

  static String format(TurnStats stats, int providerRequests) {
    var rewrite = stats.rewrite;
    var retrieval = stats.retrieval;
    List<String> topK = retrieval == null ? null
        : retrieval.found().stream().map(ShownProduct::id).toList();
    List<String> rawTopK = stats.rawTopK;
    StringBuilder line = new StringBuilder();
    append(line, "session", truncate(stats.sessionId, 8));
    append(line, "outcome", stats.outcome);
    append(line, "intent", rewrite == null ? "-" : rewrite.intent().value());
    append(line, "rewrite", rewrite == null ? "-" : rewrite.rateLimited() ? "rate-limited"
        : rewrite.fallback() ? "fallback" : "ok");
    append(line, "raw", quote(stats.message));
    append(line, "query", rewrite == null ? "-" : quote(rewrite.query()));
    append(line, "minPrice", rewrite == null ? "-" : Objects.toString(rewrite.minPrice(), "-"));
    append(line, "maxPrice", rewrite == null ? "-" : Objects.toString(rewrite.maxPrice(), "-"));
    append(line, "excludeTags", rewrite == null ? "-" : list(rewrite.excludeTags()));
    append(line, "searched", retrieval == null ? "-" : Boolean.toString(retrieval.searched()));
    append(line, "catalog", retrieval == null ? "-"
        : retrieval.catalogUnavailable() ? "unavailable" : "ok");
    append(line, "reasoning", stats.reasoning ? "on" : "off");
    append(line, "reasoningChars", Long.toString(stats.reasoningChars));
    append(line, "topk", topK == null ? "-" : list(topK));
    append(line, "rawTopk", rawTopK == null ? "-" : list(rawTopK));
    append(line, "overlap", topK == null || rawTopK == null ? "-"
        : Long.toString(topK.stream().filter(rawTopK::contains).count()));
    append(line, "nvidiaRequests", Integer.toString(providerRequests));
    append(line, "modelCalls", Integer.toString(stats.modelCalls));
    append(line, "tools", stats.tools.isEmpty() ? "-" : String.join(",", stats.tools));
    append(line, "limiterWaitMs", Long.toString(stats.limiterWaitMillis));
    append(line, "retries429", Integer.toString(stats.retries429));
    append(line, "rewriteMs", rewrite == null ? "-" : Long.toString(rewrite.latencyMillis()));
    append(line, "retrievalMs", retrieval == null ? "-" : Long.toString(retrieval.latencyMillis()));
    append(line, "firstFragmentMs", stats.firstFragmentMillis < 0 ? "-"
        : Long.toString(stats.firstFragmentMillis));
    append(line, "totalMs", stats.totalMillis < 0 ? "-" : Long.toString(stats.totalMillis));
    return line.toString();
  }

  private static void append(StringBuilder line, String key, String value) {
    if (!line.isEmpty()) {
      line.append(' ');
    }
    line.append(key).append('=').append(value);
  }

  private static String list(List<String> values) {
    return "[" + String.join(",", values) + "]";
  }

  private static String quote(String value) {
    if (value == null) {
      return "\"\"";
    }
    return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
  }

  private static String truncate(String value, int max) {
    return value.length() <= max ? value : value.substring(0, max);
  }
}
