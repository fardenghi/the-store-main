# The Store — demo del asistente

Video de arquitectura de **80 segundos**, 1920 × 1080, 30 fps, en español.
Diseño oscuro con acentos lima, transiciones suaves y una escena clara para la respuesta.
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

| Tiempo      | Escena               | Lo que explica                                                                                                                             |
| ----------- | -------------------- | ------------------------------------------------------------------------------------------------------------------------------------------ |
| 0–6 s       | Apertura             | Una pregunta se convierte en una recomendación.                                                                                            |
| 5,5–19,5 s  | Arranque             | Catálogo → Assistant → Gemini → Qdrant; 80 productos, embeddings de 768 dimensiones y sincronización incremental.                          |
| 19–33 s     | Primer llamado       | Consulta en español → intención, query en inglés y filtros; utiliza historial, productos anteriores y tags.                                |
| 32,5–48,5 s | Recuperación         | Gemini embebe `desk lamp`; Qdrant selecciona los 5 vecinos más cercanos por similitud coseno.                                              |
| 48–60 s     | Segundo llamado      | Solo los resultados recuperados forman el contexto de productos del turno. Se envían datos de producto; los vectores permanecen en Qdrant. |
| 59,5–67,5 s | Respuesta            | Recomendación ilustrativa con un producto del catálogo; entrega en streaming por SSE.                                                      |
| 67–80 s     | Seguimiento y cierre | “Más barato que la de nogal” resuelve el producto de US$149 y genera `maxPrice: 148`. Se repite el ciclo.                                  |

## Fidelidad al proyecto

El flujo se verificó contra:

- `../src/assistant/src/main/java/com/amazon/sample/assistant/products/index/`
- `../src/assistant/src/main/java/com/amazon/sample/assistant/chat/ChatTurnService.java`
- `../src/assistant/src/main/java/com/amazon/sample/assistant/chat/context/`
- `../src/assistant/src/main/resources/prompts/rewrite.st`
- `../src/assistant/src/main/resources/application.yml`
- `../src/catalog/repository/products.json`

La nube, los valores del vector, el ranking y las respuestas son ilustrativos;
no representan una ejecución grabada ni resultados medidos de la consulta.
Nombres, precios y fotos provienen del catálogo local. El segundo llamado recibe
además las instrucciones, la conversación y el mensaje original. En turnos
posteriores puede recibir también productos mostrados previamente.

La demo muestra una búsqueda simple con dos llamadas al LLM: reescritura y
respuesta. Gemini es un proveedor de embeddings separado. Los turnos con tools
pueden hacer más llamadas al modelo principal, como se aclara en el video.
No requiere levantar el cluster, usar claves ni consultar proveedores externos.

## Editar y verificar

- `src/Demo.tsx`: duración y transiciones de cada escena.
- `src/scenes/`: textos, diagramas y animaciones.
- `src/design.tsx`: colores, componentes compartidos y productos del ejemplo.
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
