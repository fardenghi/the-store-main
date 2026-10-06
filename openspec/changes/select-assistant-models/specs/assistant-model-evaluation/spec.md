# Spec Delta

## Purpose

Define cómo se eligen los modelos de chat del `assistant` (principal y de reescritura): una evaluación secuencial y reproducible, de a un candidato por vez, con métricas medidas contra NVIDIA, un criterio de aceptación por rol, un consumo de cuota acotado y el registro de la decisión con su evidencia y un plan B verificado.

## ADDED Requirements

### Requirement: Evaluación secuencial de a un candidato
La elección de modelos SHALL hacerse evaluando un candidato por vez y deteniéndose en el primero que cumpla el criterio de aceptación de un rol. Cada rol (principal y reescritura) SHALL decidirse por separado: un candidato SHALL poder adoptarse en un rol y no en el otro. Mientras haya un candidato en evaluación, MUST NOT empezar la evaluación de otro, ni correrse una comparación de varios candidatos entre sí. Si el candidato no cumple el criterio en un rol, ese rol SHALL conservar su modelo actual, y el resultado SHALL reportarse para que el grupo elija el siguiente candidato fuera de esta evaluación.

#### Scenario: Candidato que pasa en un rol
- **WHEN** el candidato cumple el criterio de aceptación del rol de reescritura
- **THEN** se adopta como modelo de reescritura, el modelo reemplazado queda como plan B de ese rol, y no se evalúa ningún otro candidato para la reescritura

#### Scenario: Candidato que no pasa en un rol
- **WHEN** el candidato no cumple el criterio de aceptación del rol principal
- **THEN** el modelo principal sigue siendo el actual, el resultado queda registrado con las métricas que fallaron, y no se evalúa automáticamente el siguiente candidato de la lista

#### Scenario: Candidato descartado antes de medir
- **WHEN** el candidato no responde dentro del tiempo de descarte o la cuenta no tiene acceso al modelo
- **THEN** se lo registra como descartado en ese rol, sin correr el resto de las mediciones de ese rol

### Requirement: Benchmark reproducible por modelo
Los modelos de cada rol y sus parámetros de razonamiento SHALL poder elegirse al correr las pruebas smoke del `assistant` (spike de modelos, evaluación de la reescritura, escenarios de chat, escenarios de las tools y sesiones largas de carrito), sin modificar el código ni los valores por defecto versionados. Las corridas SHALL reutilizar una colección de productos ya indexada, sin pedir embeddings de indexación. Un comando SHALL poder repetir una prueba N veces con la misma configuración y producir un reporte que agregue, por corrida y en total, las métricas de cada rol a partir del log por turno del `assistant` y del resultado de cada escenario.

#### Scenario: Corrida con un candidato
- **WHEN** se corre el benchmark indicando el candidato como modelo principal y como modelo de reescritura, con sus parámetros de razonamiento
- **THEN** todas las solicitudes de chat de la corrida usan esos modelos, y los valores por defecto versionados no cambian

#### Scenario: Colección reutilizada
- **WHEN** se corre cualquier prueba del benchmark apuntando a la colección ya indexada
- **THEN** la corrida no pide ningún embedding de indexación al proveedor de embeddings

#### Scenario: Reporte agregado
- **WHEN** termina una serie de N corridas de una prueba
- **THEN** el reporte muestra, por corrida y en total, los escenarios aprobados, las métricas del rol y las solicitudes hechas a cada proveedor

### Requirement: Métricas por rol
Para el modelo principal, la evaluación SHALL medir: soporte de streaming, de tool calling en streaming y de razonamiento activable por solicitud; escenarios aprobados; agregados correctos al carrito; productos equivocados agregados; confirmaciones de agregado mostradas sin un agregado real; afirmaciones descartadas por la salvaguarda; vueltas correctivas; pedidos ambiguos resueltos con una pregunta; latencia al primer fragmento (p50 y p95) y solicitudes a NVIDIA por turno. Para el modelo de reescritura, SHALL medir: salidas JSON válidas, latencia (p50 y p95), turnos que caen al fallback con el tiempo límite vigente y productos esperados en el top-5 del conjunto de evaluación.

#### Scenario: Métricas del principal
- **WHEN** termina la evaluación del candidato en el rol principal
- **THEN** el registro incluye cada métrica del rol principal, con su valor y el de la línea base

#### Scenario: Métricas de la reescritura
- **WHEN** termina la evaluación del candidato en el rol de reescritura
- **THEN** el registro incluye cada métrica del rol de reescritura, con su valor y el de la línea base

### Requirement: Criterio de aceptación del modelo principal
Un candidato SHALL adoptarse como modelo principal solo si cumple todas estas condiciones en la evaluación:
- soporta streaming, tool calling en streaming, la vuelta final sin tools y el razonamiento activable y desactivable por solicitud, sin que el razonamiento llegue al usuario;
- no muestra ninguna confirmación de agregado al carrito sin un agregado real, ni agrega un producto distinto del pedido;
- en las sesiones largas de carrito, el último pedido de agregado termina con el producto pedido en el carrito en una proporción al menos igual a la de la línea base;
- ante el pedido ambiguo, agrega sin preguntar como máximo tantas veces como la línea base;
- todos los escenarios que hoy pasan de forma estable siguen pasando;
- la latencia al primer fragmento queda dentro de los límites para la demo.

Los valores numéricos de cada condición SHALL fijarse antes de la primera corrida y MUST NOT cambiarse después de ver los resultados.

#### Scenario: Confirmación falsa
- **WHEN** en cualquier corrida de la evaluación el usuario recibe un texto que afirma un agregado al carrito sin que el turno haya hecho un agregado correcto
- **THEN** el candidato no se adopta como modelo principal, aunque cumpla el resto de las condiciones

#### Scenario: Regresión en un escenario estable
- **WHEN** un escenario que hoy pasa de forma estable falla con el candidato por una causa del modelo
- **THEN** el candidato no se adopta como modelo principal

#### Scenario: Candidato aceptado
- **WHEN** el candidato cumple todas las condiciones con los umbrales fijados
- **THEN** se adopta como modelo principal y el modelo reemplazado queda como plan B

### Requirement: Criterio de aceptación del modelo de reescritura
Un candidato SHALL adoptarse como modelo de reescritura solo si, con el razonamiento desactivado y el tiempo límite de reescritura vigente, cumple todas estas condiciones: devuelve una salida JSON válida en todas las llamadas salvo el margen fijado, su latencia (p50 y p95) es menor que la de la línea base, su tasa de fallback es menor que la de la línea base, y la cantidad de productos esperados en el top-5 del conjunto de evaluación no es menor que la de la línea base y sigue siendo mayor que la de las consultas crudas. Los valores numéricos SHALL fijarse antes de la primera corrida.

#### Scenario: Reescritura más rápida y sin pérdida de calidad
- **WHEN** el candidato cumple los umbrales de JSON, latencia, fallback y top-5
- **THEN** se adopta como modelo de reescritura y el modelo reemplazado queda como plan B

#### Scenario: Reescritura que empeora el top-5
- **WHEN** el candidato es más rápido que la línea base pero recupera menos productos esperados en el top-5
- **THEN** el candidato no se adopta como modelo de reescritura

### Requirement: Parámetros del candidato y tiempo de descarte
Antes de medir un candidato, la evaluación SHALL determinar con qué parámetros de la solicitud se desactiva su razonamiento (y, para el rol principal, con cuáles se activa), verificando que con el razonamiento desactivado la respuesta llega en el texto y no se consume en razonamiento. Cada solicitud de esta etapa SHALL tener un tiempo de descarte: un candidato que no responde dentro de ese tiempo o al que la cuenta no tiene acceso SHALL descartarse en ese rol. Los parámetros encontrados SHALL registrarse junto con la decisión y SHALL ser los que se configuren si el candidato se adopta.

#### Scenario: Razonamiento desactivado
- **WHEN** se prueba el candidato con los parámetros que desactivan el razonamiento y un límite bajo de tokens de salida
- **THEN** la respuesta trae texto, sin razonamiento, y los parámetros quedan registrados

#### Scenario: Sin forma de desactivar el razonamiento
- **WHEN** ninguna de las variantes de parámetros probadas desactiva el razonamiento del candidato
- **THEN** el resultado queda registrado y el candidato solo puede adoptarse en un rol si cumple su criterio con el razonamiento activado

### Requirement: Consumo de cuota acotado
La evaluación SHALL tener un presupuesto máximo de solicitudes a NVIDIA y de solicitudes de embeddings a Gemini, fijado antes de empezar, y MUST NOT superarlo. Las corridas SHALL ser secuenciales, respetar el limitador de solicitudes del `assistant` por debajo de la cuota de NVIDIA y no reindexar el catálogo. Si una corrida agotaría el presupuesto restante, la evaluación SHALL detenerse y reportar con lo medido hasta ese momento.

#### Scenario: Presupuesto respetado
- **WHEN** termina la evaluación
- **THEN** el registro informa las solicitudes consumidas a NVIDIA y a Gemini, y ambas están por debajo del presupuesto fijado

#### Scenario: Presupuesto insuficiente
- **WHEN** la siguiente corrida planificada superaría el presupuesto restante
- **THEN** no se corre, y la decisión se toma o se posterga con las mediciones ya hechas, dejando constancia

### Requirement: Registro de la decisión y plan B verificado
La decisión de cada rol SHALL quedar registrada en el README del `assistant` y en el diseño del change, en una tabla con el candidato, el rol, la fecha, los parámetros de razonamiento, cada métrica con su umbral y su valor, la línea base y el resultado (adoptado, no adoptado o descartado). El plan B de cada rol SHALL ser un modelo verificado con la cuenta del grupo, y su configuración (modelo y parámetros de razonamiento) SHALL quedar documentada para poder aplicarla sin reconstruir la imagen.

#### Scenario: Decisión registrada
- **WHEN** termina la evaluación del candidato
- **THEN** el README del `assistant` tiene la tabla de la decisión de cada rol, con las métricas, los umbrales y el resultado

#### Scenario: Plan B aplicable
- **WHEN** el grupo necesita volver al plan B de un rol durante la demo
- **THEN** el README indica los valores exactos del ConfigMap para ese rol, y esos valores corresponden a un modelo que respondió con la cuenta del grupo en la evaluación o en una medición registrada
