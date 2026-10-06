package com.amazon.sample.assistant.tools;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Una línea de log por tool ejecutada, en formato {@code clave=valor}, con el
 * logger {@code assistant.tool} (D12 de {@code add-assistant-tools}): la sesión
 * truncada a 8 caracteres, la tool, sus argumentos (que nunca incluyen la
 * sesión), {@code ok} o el tipo de error, la cantidad de productos, las
 * llamadas a {@code catalog} y {@code carts} y la latencia.
 */
class ToolLogger {

  static final String LOGGER_NAME = "assistant.tool";

  private static final Logger log = LoggerFactory.getLogger(LOGGER_NAME);

  void log(String sessionId, String tool, Map<String, Object> args, String outcome, int products,
      StoreTools.Calls calls, long latencyMillis) {
    log.info(format(sessionId, tool, args, outcome, products, calls.catalog, calls.carts,
        latencyMillis));
  }

  static String format(String sessionId, String tool, Map<String, Object> args, String outcome,
      int products, int catalogCalls, int cartsCalls, long latencyMillis) {
    return "session=" + truncate(sessionId, 8)
        + " tool=" + tool
        + " args={" + args.entrySet().stream()
            .filter(entry -> entry.getValue() != null)
            .map(entry -> entry.getKey() + "=" + value(entry.getValue()))
            .collect(Collectors.joining(", ")) + "}"
        + " outcome=" + outcome
        + " products=" + products
        + " catalogCalls=" + catalogCalls
        + " cartsCalls=" + cartsCalls
        + " latencyMs=" + latencyMillis;
  }

  private static String value(Object value) {
    if (value instanceof String text) {
      return "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
    }
    if (value instanceof List<?> list) {
      return list.stream().map(String::valueOf).collect(Collectors.joining(",", "[", "]"));
    }
    return String.valueOf(value);
  }

  private static String truncate(String value, int max) {
    return value.length() <= max ? value : value.substring(0, max);
  }
}
