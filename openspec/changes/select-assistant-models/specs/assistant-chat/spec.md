# Spec Delta

<!--
Escrita asumiendo el resultado esperado de la evaluación: meta/muse-glimmer-30b
adoptado en los dos roles y los Nemotron actuales como plan B. Si el resultado
real es otro, la task 6.1 ajusta este archivo antes de cerrar el change.
-->

## MODIFIED Requirements

### Requirement: Consumo acotado del proveedor de chat
Cada turno SHALL hacer como máximo una solicitud al modelo de reescritura y una al modelo principal (sin contar las que agreguen las tools), y cada solicitud de chat MUST incluir un límite explícito de tokens de salida, mayor en los turnos con razonamiento activado. Los modelos de reescritura y principal, y los parámetros con que se activa o desactiva su razonamiento, SHALL poder cambiarse por configuración sin reconstruir la imagen, para poder pasar a los modelos del plan B. El plan B de cada rol SHALL ser el modelo verificado que el modelo elegido reemplazó.

#### Scenario: Turno simple
- **WHEN** se completa un turno de búsqueda sin tools
- **THEN** el log del turno registra exactamente dos solicitudes al proveedor de chat: una de reescritura y una del modelo principal

#### Scenario: Cambio al plan B
- **WHEN** se configura `nvidia/nemotron-3-super-120b-a12b` como modelo principal y `nvidia/nemotron-3.5-lightning-30b-a3b` como modelo de reescritura, con sus parámetros de razonamiento (`{"chat_template_kwargs": {"enable_thinking": true|false}}`), y se reinicia el `assistant`
- **THEN** los turnos siguientes usan esos modelos y se completan con los mismos eventos SSE
