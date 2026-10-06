package com.amazon.sample.assistant.tools;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;
import org.springframework.ai.tool.resolution.ToolCallbackResolver;

/**
 * Envuelve una tool para que un tool call que no se puede ni siquiera convertir
 * en argumentos (un JSON roto, un tipo imposible) vuelva al modelo como
 * {@code invalid-argument} en lugar de cortar el turno (D2 y D3 de
 * {@code add-assistant-tools}). Con {@link #unknownToolResolver()}, lo mismo
 * para un nombre de tool que no existe.
 */
public final class SafeToolCallback implements ToolCallback {

  private static final Logger log = LoggerFactory.getLogger(SafeToolCallback.class);

  private static final ObjectMapper JSON = new ObjectMapper();

  private final ToolCallback delegate;

  private SafeToolCallback(ToolCallback delegate) {
    this.delegate = delegate;
  }

  public static List<ToolCallback> wrap(ToolCallback... callbacks) {
    return Arrays.stream(callbacks).<ToolCallback>map(SafeToolCallback::new).toList();
  }

  /**
   * Resolver para el {@code ToolCallingManager}: cualquier nombre que no sea
   * una de las tools devuelve un error al modelo.
   */
  public static ToolCallbackResolver unknownToolResolver() {
    return name -> new UnknownTool(name);
  }

  @Override
  public ToolDefinition getToolDefinition() {
    return delegate.getToolDefinition();
  }

  @Override
  public ToolMetadata getToolMetadata() {
    return delegate.getToolMetadata();
  }

  @Override
  public String call(String toolInput) {
    return call(toolInput, null);
  }

  @Override
  public String call(String toolInput, ToolContext toolContext) {
    try {
      return delegate.call(toolInput, toolContext);
    } catch (RuntimeException e) {
      log.warn("Tool call de {} con argumentos inválidos: {}", getToolDefinition().name(),
          e.getMessage());
      return json(ToolError.of(ToolError.INVALID_ARGUMENT,
          "The arguments could not be read; send a JSON object that follows the tool schema"));
    }
  }

  private static String json(Map<String, Object> result) {
    try {
      return JSON.writeValueAsString(result);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException(e);
    }
  }

  /** Tool inexistente: el modelo inventó un nombre. */
  private record UnknownTool(String name) implements ToolCallback {

    @Override
    public ToolDefinition getToolDefinition() {
      return ToolDefinition.builder().name(name).description("Unknown tool")
          .inputSchema("{\"type\":\"object\"}").build();
    }

    @Override
    public String call(String toolInput) {
      log.warn("El modelo pidió una tool inexistente: {}", name);
      return json(ToolError.of(ToolError.INVALID_ARGUMENT, "There is no tool named " + name
          + "; use searchProducts, getProductDetails or addToCart"));
    }
  }
}
