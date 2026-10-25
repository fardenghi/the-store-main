# Discurso por slide

Lo que dice cada integrante en cada slide. **▸** marca dónde avanzar: la
animación sigue hasta la próxima parada. Los textos son una base para decir
con palabras propias, no para leer.

A unas 130 palabras por minuto, el texto da ≈ 4, 6 y 3 minutos por bloque.
Con las animaciones y las pausas son unos 18 minutos, más los 8 de la demo:
quedan unos minutos de margen para preguntas dentro de los 30.

---

## Bloque A — Problema y solución · Integrante 1

### 1. Portada

Hola, somos el grupo 3 y les vamos a presentar el tema 9: un asistente de
compras con inteligencia artificial generativa para The Store. Lo llamamos
A.G.E.N.T., como el personaje que ya tenía la tienda. La idea es simple: que
el usuario pida lo que necesita con sus palabras y el asistente encuentre el
producto. Vamos a ver qué problema resuelve, cómo funciona por dentro ante cada
mensaje y cómo está desplegado en Kubernetes, y al final lo mostramos andando.

### 2. El problema

The Store tenía dos limitaciones para encontrar productos.

La primera es la búsqueda. No se puede buscar por texto: el catálogo solo
filtra por tags exactos, como "lighting" o "velvet". Un pedido como "somewhere
cozy to curl up with a book" no tiene dónde entrar, aunque hay sillones, pufs y
lámparas que son exactamente eso.

▸

La segunda es el chat. La tienda ya tenía uno, pero era un system prompt con
un personaje y nada más: no conocía el catálogo, no recordaba lo que le habías
dicho y no podía hacer nada en la tienda, como agregar algo al carrito.

### 3. Qué hace el asistente

El asistente resuelve esas dos cosas. Lo organizamos en tres grupos de
capacidades.

Primero, **buscar**: búsqueda por significado y no por palabras, tolerancia a
errores de tipeo y productos similares en la ficha de cada producto.

▸

Segundo, **conversar**: antes de buscar, reescribe lo que le pediste para
entenderlo mejor; recuerda la conversación, así que le podés decir "más barato"
o "que no sea una lámpara"; y puede comparar productos con criterios
explícitos.

▸

Y tercero, **actuar**: traduce lo que pedís a filtros reales de categoría y
precio, consulta el precio vigente en el catálogo en lugar de inventarlo, y
agrega productos al carrito desde el chat.

### 4. Arquitectura

Esta es The Store: una interfaz web y los servicios de catálogo, carrito,
órdenes y checkout, cada uno en su lenguaje.

▸

Agregamos dos piezas. Un microservicio nuevo, `assistant`, en Java con Spring
Boot y Spring AI, que concentra toda la lógica de inteligencia artificial. Y
Qdrant, una base de datos vectorial donde guardamos el catálogo convertido en
vectores. La `ui` sigue siendo solo presentación: le pasa el mensaje al
`assistant` y le devuelve la respuesta al navegador en streaming.

▸

El `assistant` es el único servicio que sale a internet: llama a NVIDIA para
el modelo de lenguaje y a Google para los embeddings, siempre por HTTPS. Si el
`assistant` se cae, el resto de la tienda sigue funcionando igual.

### 5. Los modelos

Usamos modelos en la nube porque necesitábamos respuestas en pocos segundos y
un tool calling confiable, y eso un modelo chico corriendo con CPU no lo daba.

▸

El modelo de lenguaje es `muse-glimmer-30b`, a través de la API de NVIDIA, que
es compatible con la de OpenAI. Lo usamos en dos roles: para reescribir la
consulta con el mínimo razonamiento, y para responder, con más razonamiento
solo cuando hay que comparar. Lo elegimos midiendo: en las pruebas de varios
turnos agregó el producto correcto al carrito en 14 de 15 sesiones. Para los
embeddings usamos `gemini-embedding-001` de Google, con vectores de 768
dimensiones. Y tenemos un plan B probado, con modelos Nemotron, que se activa
cambiando la configuración, sin tocar código.

---

## Bloque B — Cómo funciona por atrás · Integrante 2

### 6. Apertura

Ahora vamos a seguir un mensaje de punta a punta, para ver todo lo que pasa
por atrás entre que el usuario escribe y recibe la respuesta. El ejemplo es
alguien que pide una lámpara para su escritorio.

### 7. Arranque e indexación

Antes de cualquier mensaje, cuando el `assistant` arranca, prepara el
catálogo.

▸

Lee los 80 productos del servicio de catálogo y, para cada uno, arma un texto
con el nombre, los tags y la descripción. Ese texto se lo manda a Gemini, que
lo convierte en un vector de 768 números: un embedding, que representa el
significado del producto. Los vectores se guardan en Qdrant junto con el id,
el nombre, el precio y los tags. Guardamos un hash de cada texto, así que si
el pod se reinicia y el catálogo no cambió, no se vuelve a llamar a Gemini.
Eso importa porque el tier gratuito tiene 1.000 requests por día.

### 8. Primer llamado: reescritura

Llega el mensaje: "lámpara para mi escritorio".

▸

El primer llamado al modelo no responde nada: reescribe. Recibe el mensaje y
los últimos turnos de la conversación, que es la memoria, y devuelve una
consulta corta y autocontenida en inglés, más los filtros que haya, como un
precio máximo o una categoría a excluir, y la intención del mensaje.

▸

En este caso sale `desk lamp`. Esa consulta limpia es la que se convierte en
embedding, esta vez como consulta. Si la reescritura tarda más de 12 segundos,
seguimos con el mensaje original para no trabar el turno.

### 9. Búsqueda vectorial

Con el embedding de la consulta vamos a Qdrant. Cada punto es un producto, y
los que significan cosas parecidas quedan cerca.

▸

Qdrant busca los vecinos más cercanos por similitud coseno, aplicando los
filtros de precio y tags sobre los datos guardados, y devuelve los 5 mejores.
Así pasamos de 80 productos a 5 candidatos, sin que ninguno tenga que
compartir palabras con el pedido.

### 10. Segundo llamado: contexto

Ahora viene el segundo llamado al modelo, el que responde.

▸

No le mandamos el catálogo entero: le mandamos solo esos 5 productos, junto
con las instrucciones del personaje, la conversación y el mensaje original. Es
lo que se llama RAG: el modelo responde con datos reales de la tienda que le
damos nosotros, en lugar de inventarlos.

### 11. Respuesta

La respuesta vuelve en streaming, por Server-Sent Events: del `assistant` a la
`ui` y de la `ui` al navegador, oración por oración, así el usuario empieza a
leer sin esperar a que termine. Y cuando el asistente nombra un producto, el
chat muestra abajo su tarjeta con la foto, el precio y el link a la ficha.

### 12. Seguimiento con memoria

Lo interesante es el turno siguiente. El usuario dice "nogal, más barata",
sin repetir que habla de una lámpara.

▸

Como la reescritura recibe la conversación, sabe que la lámpara anterior
costaba 149 dólares y genera el filtro "menos de 148". Y se repite el mismo
recorrido: reescritura, Qdrant y respuesta. La memoria guarda los últimos 10
turnos de cada sesión y se olvida a los 30 minutos sin uso.

### 13. Actuar: tools

Además de buscar, el asistente puede actuar, con function calling. El modelo
tiene tres herramientas: buscar con filtros, consultar el detalle de un
producto y agregar al carrito.

▸

Cuando el usuario pide "agregalo al carrito", el modelo no lo hace: pide una
tool, `addToCart` con el id del producto. El que la ejecuta es el `assistant`:
lee el precio vigente en el catálogo y hace el POST al servicio de carrito.

▸

El resultado vuelve al modelo, que arma la respuesta, y la `ui` recibe un
evento que actualiza el contador del carrito sin recargar la página. El
carrito siempre es el de la sesión del usuario: el modelo no puede elegir otro.

### 14. Cuidados

Darle herramientas a un modelo obliga a ponerle límites.

▸

Por turno hay como máximo 4 llamadas al modelo, así un turno nunca se queda
dando vueltas. Todas las llamadas a NVIDIA pasan por un limitador de 36 por
minuto, por debajo de la cuota de 40, y si igual llega un error 429, esperamos
lo que indica el proveedor y reintentamos. Y hay una salvaguarda: si el modelo
escribe "lo agregué al carrito" pero no ejecutó la tool, esa oración no se
muestra y se le pide que lo haga de verdad.

---

## Bloque C — Infraestructura y demo · Integrante 3

### 15. Kubernetes

Todo corre en un cluster kind, en el namespace `the-store`.

▸

El `assistant` es un Deployment con su Service de tipo ClusterIP, así que solo
es accesible desde adentro del cluster. Qdrant es un StatefulSet con un
volumen persistente de 1 GB, para que los vectores sobrevivan a los
reinicios. La configuración va en un ConfigMap, y las claves de NVIDIA y Google
en un Secret que crea el script de despliegue y nunca se versiona.

▸

Algunas decisiones: el `assistant` tiene una réplica, porque la memoria y el
limitador viven en el proceso. Su readiness no depende de Qdrant: si Qdrant
se cae, el `assistant` sigue respondiendo con un error controlado. Y no está
expuesto en el ingress: el navegador solo habla con la `ui`.

### 16. Red

El cluster corre en un nodo kind, que es un contenedor de Docker conectado a
la red bridge `kind`, con su propia IP en esa red. Adentro del cluster, los
pods toman IPs de la 10.244.0.0/16 y los Services de la 10.96.0.0/16, que son
los rangos por defecto de kind. El ingress-nginx publica los puertos 80 y 443
en el host.

▸

Adentro, el navegador habla con la `ui` por HTTP y Server-Sent Events. La `ui`
manda un keepalive cada 10 segundos para que nginx no corte la conexión
mientras el modelo piensa. La `ui` llama al `assistant` por su Service, y el
`assistant` a Qdrant por gRPC en el puerto 6334.

▸

Y la salida a internet: el pod resuelve el nombre de NVIDIA con kube-dns, que
lo reenvía al resolver del nodo. El paquete sale por el gateway del pod, el
nodo le hace SNAT con su IP, Docker hace un segundo NAT hacia el host, y de
ahí a internet por HTTPS en el puerto 443. Es el único tráfico que sale del
cluster.

### 17. Demo

Ahora lo vemos funcionando. Vamos a recorrer los casos de uso en la tienda y,
en paralelo, los logs del `assistant`, donde se ve la consulta reescrita, los
filtros y las tools de cada turno.

*(Demo en vivo, ≈ 8 minutos, en el orden del ensayo: ficha con similares →
búsqueda semántica → "cheaper" → "not a lamp" → errores de tipeo →
comparación → filtros → precio en vivo → agregar al carrito.)*

### 18. Cierre

Para cerrar: lo que más trabajo nos dio no fue conectar el modelo, sino
controlarlo. Reescribir la consulta antes de buscar, darle solo los productos
relevantes, validar cada herramienta del lado del servidor y no confiar en lo
que dice que hizo.

▸

El asistente tiene límites que conocemos: la memoria vive en el proceso, así
que se pierde si el pod se reinicia; tiene una sola réplica; y depende de
internet y de las cuotas de los proveedores. Muchas gracias, ¿preguntas?
