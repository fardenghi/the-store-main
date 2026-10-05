# Proposal

## Why

Todas las capacidades del `assistant` tienen que llegar al usuario a través de la `ui`, que sigue siendo solo presentación. Hoy el chat de la UI está deshabilitado en el despliegue, habla directo con un modelo y no sabe nada de la sesión. La ficha de producto tampoco muestra productos relacionados, y el carrito no se entera de cambios hechos fuera de la página.

## What Changes

- Nuevo provider de chat `assistant` en la `ui`, junto a los existentes (`mock`, `openai`, `bedrock`), que reenvía el chat por HTTP + SSE al servicio `assistant`.
- `ChatController` propaga el `X-Session-ID` de la sesión, tomado de la cookie `SESSIONID`, para que la memoria del chat y el carrito usen el mismo identificador.
- La persona deja de vivir en la configuración de la `ui`: el system prompt lo arma el `assistant`.
- La ficha de producto muestra los k productos más similares, obtenidos del endpoint de similares del `assistant`. Si el `assistant` no responde, la ficha se muestra igual, sin la sección.
- Cuando una tool modifica el carrito, el contador o la vista del carrito se actualizan sin recargar la página a mano.
- Despliegue: se habilita el chat (`retail.ui.chat.enabled`), se configura el provider `assistant` y se agrega su endpoint en el `ConfigMap` de la `ui`.

## Capabilities

### New Capabilities
- `ui-assistant-integration`: cómo la UI expone el asistente: chat vía provider `assistant` con sesión propagada, productos similares en la ficha y reflejo en la UI de los cambios de carrito hechos desde el chat.

### Modified Capabilities
<!-- No hay specs existentes en openspec/specs/. -->

## Impact

- `src/ui`: configuración de chat (`config/chat/*`), `ChatController`, plantilla y controlador de la ficha (`detail.html`, `CatalogController`), JS del chat y del carrito.
- `dist/kubernetes.yaml`: `ConfigMap` de la `ui`.
- Casos de uso de la pre-entrega que cubre: productos similares (lado UI) y agregar al carrito desde el chat (la UI refleja el cambio). Además es el punto de entrada de todos los demás casos en la demo.
- Depende de `add-product-indexing`, `add-assistant-chat` y `add-assistant-tools`.
