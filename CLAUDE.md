# The Store — TPE Redes de Información (Tema 9: Asistente de compras con GenAI)

## Contexto

- `docs/preentrega.md` es la pre-entrega **aprobada** por la cátedra y funciona como contrato: el enunciado exige implementar el 100% de lo propuesto. Todo desvío tiene que quedar documentado y justificado.
- Entrega final: 4 de noviembre de 2026. Presentaciones: 5 y 10 de noviembre.
- Responder y escribir los artifacts de OpenSpec en **español**.
- Commits **sin** la línea `Co-Authored-By` de Claude: la autoría del TP tiene que ser del grupo.

## Flujo de trabajo: OpenSpec

Todo cambio funcional se planifica y se ejecuta como un change de OpenSpec en `openspec/changes/<nombre>/`.

### Setup (una vez por máquina)

```bash
npm install -g @fission-ai/openspec@latest
openspec init   # genera los comandos /opsx y los skills
```

### Ciclo de un change


| Paso        | Comando                  | Qué hace                                                             |
| ----------- | ------------------------ | -------------------------------------------------------------------- |
| Explorar    | `/opsx:explore <tema>`   | Pensar, investigar el código y comparar opciones. No implementa nada |
| Planificar  | `/opsx:propose <change>` | Completa specs, design y tasks a partir del `proposal.md`            |
| Implementar | `/opsx:apply <change>`   | Ejecuta las tasks de `tasks.md` y las va marcando                    |
| Cerrar      | `/opsx:archive <change>` | Archiva el change e incorpora sus specs a `openspec/specs/`          |


### Reglas

- Nunca crear un change a mano: usar siempre `openspec new change "<nombre>"`, que genera el `.openspec.yaml`.
- Antes de escribir un artifact, correr `openspec instructions <artifact> --change <nombre> --json` y seguir su template.
- Cualquier desvío respecto de `docs/preentrega.md` va en el `design.md` del change, con su justificación.
- Validar antes de dar un change por terminado: `openspec validate <change>`.

### Comandos útiles

```bash
openspec list                     # changes en curso
openspec list --specs             # capabilities ya archivadas
openspec status --change <nombre> # qué artifacts faltan
openspec show <nombre>            # ver un change o spec
```

## Changes en curso

Todos tienen el `proposal.md` escrito; faltan specs, design y tasks.

```
replace-catalog-with-home-furniture   sin dependencias
add-assistant-service                 sin dependencias
add-product-indexing                  <- add-assistant-service
add-assistant-chat                    <- add-product-indexing
add-assistant-tools                   <- add-assistant-chat
integrate-ui-assistant                <- add-product-indexing, add-assistant-chat, add-assistant-tools
```

## Decisiones cerradas (no volver a discutirlas)

- **LLM en la nube vía NVIDIA** (`https://integrate.api.nvidia.com`, API compatible con OpenAI), autorizado por la cátedra. No hay Ollama.
  - Reescritura de consulta: `nvidia/nemotron-3.5-lightning-30b-a3b` con thinking desactivado.
  - Modelo principal (razonamiento + tools): `nvidia/nemotron-3-super-120b-a12b`.
  - Planes B: `deepseek-ai/deepseek-v4.1-flash` (principal) y `google/gemma-3-12b-it` (reescritura).
- **Embeddings:** `gemini-embedding-001` de Google (tier gratuito), con el starter nativo `spring-ai-starter-model-google-genai-embedding`, `task-type` `RETRIEVAL_DOCUMENT` al indexar y `RETRIEVAL_QUERY` al buscar, y **768 dimensiones**.
- **Vector store:** Qdrant, 768d, coseno. El filtro por tags es OR (`match any`), igual que el `IN` de `GET /catalog/products`.
- **Cuotas:** NVIDIA (chat) 40 RPM; Gemini (embeddings) 100 RPM y 1.000 requests por día, independientes entre sí. Embeber en lotes, calcular similares desde Qdrant sin llamar al proveedor, y manejar 429.
- **Secrets:** `NVIDIA_API_KEY` y `GOOGLE_API_KEY`, nunca versionados.
- **Catálogo:** \~80 productos de hogar y muebles del dataset Amazon Berkeley Objects (CC BY 4.0), con varios tags por producto. Se mantiene el envoltorio spy de la UI y de la persona A.G.E.N.T.
- **Sesión:** el `X-Session-ID` (cookie `SESSIONID`) identifica tanto la memoria del chat como el carrito (`customerId`). El servicio de carrito se llama `carts`.

