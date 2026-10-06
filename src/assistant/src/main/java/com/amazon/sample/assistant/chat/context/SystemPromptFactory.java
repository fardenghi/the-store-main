package com.amazon.sample.assistant.chat.context;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.core.io.Resource;

/**
 * System prompt del modelo principal (D8): la persona A.G.E.N.T. y las reglas
 * de {@code prompts/system.st}, con el contexto de productos del turno. Vive en
 * el {@code assistant} y no es configurable por ConfigMap: cambiar la persona
 * es un cambio de código.
 *
 * <p>Con {@code add-assistant-tools} (D10) incluye la sección de tools con la
 * lista de tags del catálogo, para que el modelo traduzca una categoría a tags
 * existentes.
 */
public class SystemPromptFactory {

  private final String template;
  private final Supplier<List<String>> tagNames;

  public SystemPromptFactory(Resource template) {
    this(template, List::of);
  }

  /**
   * @param tagNames tags del catálogo (los de {@code CatalogTagsCache}); vacío si
   *     no se pudieron leer
   */
  public SystemPromptFactory(Resource template, Supplier<List<String>> tagNames) {
    try {
      this.template = template.getContentAsString(StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException("No se pudo leer el prompt " + template, e);
    }
    this.tagNames = tagNames;
  }

  public String render(Retrieval retrieval) {
    List<String> tags = tagNames.get();
    return PromptTemplate.builder()
        .template(template)
        .variables(Map.of(
            "context", ContextFormatter.format(retrieval),
            "tags", tags.isEmpty() ? "(not available right now)" : String.join(", ", tags)))
        .build()
        .render();
  }
}
