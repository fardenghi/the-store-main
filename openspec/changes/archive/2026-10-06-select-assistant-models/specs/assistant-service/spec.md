# Spec Delta

## MODIFIED Requirements

### Requirement: Configuración de proveedores sin reconstruir la imagen
La URL base del proveedor de chat, los identificadores de los modelos (principal y de reescritura), los parámetros con que cada modelo activa o desactiva su razonamiento, el límite de tokens de salida (del modelo principal y de la reescritura) y el modelo y las dimensiones de los embeddings SHALL ser configurables desde el ConfigMap del `assistant`. Las solicitudes de chat MUST incluir siempre un límite explícito de tokens de salida. Los valores por defecto SHALL ser los modelos que quedaron elegidos en la evaluación de modelos, y solo un modelo verificado con la cuenta del grupo SHALL documentarse como plan B de un rol.

#### Scenario: Cambio de modelo principal al plan B
- **WHEN** se cambia en el ConfigMap el modelo principal por el plan B, `nvidia/nemotron-3-super-120b-a12b`, con sus parámetros de razonamiento (`{"chat_template_kwargs": {"enable_thinking": true|false}}`) y su límite de tokens de salida, y se reinicia el `assistant`
- **THEN** las solicitudes de chat siguientes usan ese modelo y esos parámetros, sin haber reconstruido la imagen

#### Scenario: Valores por defecto
- **WHEN** el `assistant` se despliega con el ConfigMap versionado
- **THEN** el chat apunta a `https://integrate.api.nvidia.com` con `meta/muse-glimmer-30b` como modelo principal y como modelo de reescritura, con sus parámetros de razonamiento (`{"reasoning_effort": "low"}` sin razonamiento y `{"reasoning_effort": "high"}` en las comparaciones) y 1024 tokens de salida en la reescritura, y los embeddings usan `gemini-embedding-001` con 768 dimensiones
