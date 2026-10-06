# Spec Delta

## MODIFIED Requirements

### Requirement: Consumo acotado del proveedor de chat
Cada turno SHALL hacer como máximo una solicitud al modelo de reescritura y una al modelo principal (sin contar las que agreguen las tools), y cada solicitud de chat MUST incluir un límite explícito de tokens de salida, mayor en los turnos con razonamiento activado. Los modelos de reescritura y principal, y los parámetros con que se activa o desactiva su razonamiento, SHALL poder cambiarse por configuración sin reconstruir la imagen, para poder pasar a otro modelo verificado. En un modelo que no permite apagar el razonamiento pero sí regular su esfuerzo por solicitud, el razonamiento desactivado SHALL entenderse como el esfuerzo mínimo que acepta el modelo. Solo un modelo verificado con la cuenta del grupo SHALL documentarse como plan B.

#### Scenario: Turno simple
- **WHEN** se completa un turno de búsqueda sin tools
- **THEN** el log del turno registra exactamente dos solicitudes al proveedor de chat: una de reescritura y una del modelo principal

#### Scenario: Cambio al plan B
- **WHEN** el grupo elige como plan B de un rol un modelo que respondió con la cuenta del grupo en la evaluación de modelos, lo configura con sus parámetros de razonamiento y reinicia el `assistant`
- **THEN** los turnos siguientes usan ese modelo y se completan con los mismos eventos SSE
