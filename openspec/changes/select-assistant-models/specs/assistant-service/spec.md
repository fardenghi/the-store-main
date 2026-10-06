# Spec Delta

<!--
Escrita asumiendo el resultado esperado de la evaluación: meta/muse-glimmer-30b
adoptado en los dos roles y los Nemotron actuales como plan B. Si el resultado
real es otro, la task 6.1 ajusta este archivo antes de cerrar el change.
-->

## MODIFIED Requirements

### Requirement: Configuración de proveedores sin reconstruir la imagen
La URL base del proveedor de chat, los identificadores de los modelos (principal y de reescritura), los parámetros con que cada modelo activa o desactiva su razonamiento, el límite de tokens de salida y el modelo y las dimensiones de los embeddings SHALL ser configurables desde el ConfigMap del `assistant`. Las solicitudes de chat MUST incluir siempre un límite explícito de tokens de salida. Los valores por defecto SHALL ser los modelos elegidos en la evaluación de modelos, y el plan B de cada rol SHALL ser un modelo verificado con la cuenta del grupo.

#### Scenario: Cambio de modelo principal al plan B
- **WHEN** se cambia en el ConfigMap el modelo principal a `nvidia/nemotron-3-super-120b-a12b`, con sus parámetros de razonamiento (`{"chat_template_kwargs": {"enable_thinking": true|false}}`), y se reinicia el `assistant`
- **THEN** las solicitudes de chat siguientes usan ese modelo, sin haber reconstruido la imagen

#### Scenario: Cambio de modelo de reescritura al plan B
- **WHEN** se cambia en el ConfigMap el modelo de reescritura a `nvidia/nemotron-3.5-lightning-30b-a3b`, con el razonamiento desactivado (`{"chat_template_kwargs": {"enable_thinking": false}}`), y se reinicia el `assistant`
- **THEN** las reescrituras siguientes usan ese modelo, sin haber reconstruido la imagen

#### Scenario: Valores por defecto
- **WHEN** el `assistant` se despliega con el ConfigMap versionado
- **THEN** el chat apunta a `https://integrate.api.nvidia.com` con `meta/muse-glimmer-30b` como modelo principal y como modelo de reescritura, con los parámetros de razonamiento registrados en la evaluación, y los embeddings usan `gemini-embedding-001` con 768 dimensiones
