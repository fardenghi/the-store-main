package com.amazon.sample.assistant.chat.context;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.core.io.Resource;

/**
 * System prompt del modelo principal (D8): la persona A.G.E.N.T. y las reglas
 * de {@code prompts/system.st}, con el contexto de productos del turno. Vive en
 * el {@code assistant} y no es configurable por ConfigMap: cambiar la persona
 * es un cambio de código.
 */
public class SystemPromptFactory {

  private final String template;

  public SystemPromptFactory(Resource template) {
    try {
      this.template = template.getContentAsString(StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException("No se pudo leer el prompt " + template, e);
    }
  }

  public String render(Retrieval retrieval) {
    return PromptTemplate.builder()
        .template(template)
        .variables(Map.of("context", ContextFormatter.format(retrieval)))
        .build()
        .render();
  }
}
