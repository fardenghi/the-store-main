# Spec Delta

## MODIFIED Requirements

### Requirement: Consumo acotado del proveedor de chat
Cada turno SHALL hacer como máximo una solicitud al modelo de reescritura y una al modelo principal (sin contar las que agreguen las tools), y cada solicitud de chat MUST incluir un límite explícito de tokens de salida, mayor en los turnos con razonamiento activado. Los modelos de reescritura y principal, y los parámetros con que se activa o desactiva su razonamiento, SHALL poder cambiarse por configuración sin reconstruir la imagen, para poder pasar a otro modelo verificado. En un modelo que no permite apagar el razonamiento pero sí regular su esfuerzo por solicitud, el razonamiento desactivado SHALL entenderse como el esfuerzo mínimo que acepta el modelo. Solo un modelo verificado con la cuenta del grupo SHALL documentarse como plan B.

#### Scenario: Turno simple
- **WHEN** se completa un turno de búsqueda sin tools
- **THEN** el log del turno registra exactamente dos solicitudes al proveedor de chat: una de reescritura y una del modelo principal

#### Scenario: Cambio al plan B
- **WHEN** se configuran los modelos del plan B, verificados con la cuenta del grupo (`nvidia/nemotron-3-super-120b-a12b` como principal y `nvidia/nemotron-3.5-lightning-30b-a3b` como reescritura), con sus parámetros de razonamiento, y se reinicia el `assistant`
- **THEN** los turnos siguientes usan esos modelos y se completan con los mismos eventos SSE

### Requirement: Errores del proveedor de chat
Si el proveedor de chat rechaza el turno por cuota (HTTP 429), por credenciales inválidas o ausentes, o no responde, el `assistant` SHALL terminar el stream con un evento `error` cuyo `data` incluye un `type` estable que distingue la causa (`llm-quota-exceeded`, `llm-provider-unauthorized`, `llm-provider-unavailable`) y un mensaje legible; en el caso de cuota, SHALL incluir los segundos de espera sugeridos cuando el proveedor los informe. Una falla del proveedor MUST NOT afectar la readiness del `assistant` ni otras sesiones. El proveedor SHALL considerarse sin respuesta solo si, dentro del tiempo límite al primer fragmento, no llega ningún fragmento con texto, con razonamiento o con un tool call: un modelo que razona siempre ya está respondiendo mientras razona.

#### Scenario: Sin clave de chat
- **WHEN** el `assistant` corre con `NVIDIA_API_KEY` sin configurar y se envía un mensaje
- **THEN** el stream termina con un evento `error` de tipo `llm-provider-unauthorized`, y `GET /actuator/health/readiness` sigue en `UP`

#### Scenario: Cuota de chat excedida
- **WHEN** el proveedor de chat responde 429 al modelo principal
- **THEN** el stream termina con un evento `error` de tipo `llm-quota-exceeded`, y el turno no queda guardado en la memoria de la sesión

#### Scenario: Razonamiento antes del texto
- **WHEN** el modelo principal manda su primer fragmento de razonamiento antes del tiempo límite al primer fragmento y el texto visible después de ese tiempo límite
- **THEN** el turno no termina con `llm-provider-unavailable`: el texto llega al usuario, y el turno sigue acotado por su tiempo límite total

#### Scenario: Sin ningún fragmento a tiempo
- **WHEN** dentro del tiempo límite al primer fragmento el modelo principal no manda texto, razonamiento ni tool calls
- **THEN** el stream termina con un evento `error` de tipo `llm-provider-unavailable`
