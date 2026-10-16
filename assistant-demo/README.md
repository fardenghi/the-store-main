# The Store — demo del asistente

Video de arquitectura de **56 segundos**, 1920 × 1080, 30 fps, en español.
Diseño oscuro con acentos lima, fotos en movimiento y diagramas animados.
El relato se apoya en transformaciones, selección y recorridos visuales; los textos
se limitan a títulos cortos, etiquetas y el pedido del usuario.
Sin audio: preparado para acompañar la explicación oral del trabajo práctico.

## Vista previa

Requisitos: Node.js 22 y npm. Si usás nvm, ejecutá `nvm use` dentro de
`assistant-demo/` antes de instalar. No hace falta instalar Remotion globalmente
ni configurar claves de API.

Desde la raíz del repositorio actualizado:

```sh
cd assistant-demo
npm ci
npm run dev -- --no-open --port=3100
```

Abrir <http://localhost:3100/TheStore-Assistant> y presionar Play.
Cada escena tiene su propia composición en la carpeta `Escenas` de Studio.

## Trabajar en equipo

Después de clonar el repositorio (o hacer `git pull`), seguí los pasos de arriba.
Editá los archivos de `src/` con Studio abierto: la vista previa se actualiza
automáticamente. Las escenas se pueden abrir por separado desde `Escenas`.

Subí los cambios de código y los assets nuevos en `public/`. Si cambiás
dependencias, incluí también `package-lock.json`. `node_modules/`, `dist/` y
`out/` están excluidos de Git; cada compañero instala las dependencias con
`npm ci` y puede generar su propio MP4. Los bundles de `build/` también se generan
localmente y están excluidos.

## Guion

Los tiempos incluyen solapamientos de medio segundo entre escenas.

| Tiempo      | Escena           | Animación principal                                                                                      |
| ----------- | ---------------- | -------------------------------------------------------------------------------------------------------- |
| 0–4 s       | Apertura         | Los productos orbitan y convergen en una recomendación.                                                  |
| 3,5–14,5 s  | Arranque         | Fotos del catálogo atraviesan Gemini, se transforman en barras de embeddings y llegan a Qdrant.          |
| 14–23 s     | Primer llamado   | Las palabras del pedido entran al LLM 01; salen como `desk lamp`. La memoria orbita el modelo.           |
| 22,5–33,5 s | Recuperación     | La consulta recorre el espacio vectorial; el radar selecciona 5 puntos y los convierte en tarjetas.      |
| 33–42 s     | Segundo llamado  | Solo las 5 tarjetas cruzan al LLM 02. El resto permanece en Qdrant.                                      |
| 41,5–47,5 s | Respuesta        | Foto del producto, respuesta breve en streaming y precio.                                                |
| 47–56 s     | Memoria y cierre | La referencia al producto de US$149 produce el filtro `≤ $148`; se activa el recorrido 01 → Qdrant → 02. |

## Fidelidad al proyecto

El flujo se verificó contra:

- `../src/assistant/src/main/java/com/amazon/sample/assistant/products/index/`
- `../src/assistant/src/main/java/com/amazon/sample/assistant/chat/ChatTurnService.java`
- `../src/assistant/src/main/java/com/amazon/sample/assistant/chat/context/`
- `../src/assistant/src/main/resources/prompts/rewrite.st`
- `../src/assistant/src/main/resources/application.yml`
- `../src/catalog/repository/products.json`

La nube, el ranking, los tiempos y las respuestas son ilustrativos;
no representan una ejecución grabada ni resultados medidos de la consulta.
Las tarjetas representan los datos de los productos: el modelo recibe texto, no fotos.
Nombres, precios y fotos provienen del catálogo local. El segundo llamado recibe
además las instrucciones, la conversación y el mensaje original. En turnos
posteriores puede recibir también productos mostrados previamente.

La demo muestra una búsqueda simple con dos llamadas al LLM: reescritura y
respuesta. Gemini es un proveedor de embeddings separado. Los turnos con tools
pueden hacer más llamadas al modelo principal; esos turnos no se muestran.
No requiere levantar el cluster, usar claves ni consultar proveedores externos.

## Editar y verificar

- `src/Demo.tsx`: duración y transiciones de cada escena.
- `src/scenes/`: textos, diagramas y animaciones.
- `src/design.tsx`: colores, componentes compartidos y productos del ejemplo.
- `src/motion.tsx`: tarjetas, nodos, pulsos, partículas y barras de embeddings.
- `src/Root.tsx`: video completo y composiciones individuales.

```sh
npm run lint
```

Para exportar cuando se necesite un MP4:

```sh
npm run render
```

El archivo queda en `out/the-store-assistant.mp4`. La primera exportación puede
descargar automáticamente Chrome Headless Shell para renderizar los fotogramas.

## Imágenes

Fotos tomadas de los assets del proyecto, derivados de Amazon Berkeley Objects
(Amazon.com, CC BY 4.0). Ver [atribución del catálogo](../docs/how-to.md#atribución)
y [detalles del dataset](https://amazon-berkeley-objects.s3.amazonaws.com/index.html).
