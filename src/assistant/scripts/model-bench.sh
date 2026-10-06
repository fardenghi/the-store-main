#!/usr/bin/env bash
# Benchmark de modelos de chat (select-assistant-models, D6).
#
# Corre un smoke del assistant N veces, de forma secuencial y con una pausa
# entre corridas, eligiendo los modelos y sus extra-body con -D (sin tocar los
# defaults versionados). Guarda cada corrida en target/model-bench/ y lleva un
# ledger acumulado de requests a NVIDIA y a Gemini: no arranca una corrida si
# lo consumido más la estimación de la corrida supera el tope (D5).
#
#   scripts/model-bench.sh --smoke MultiTurnCartSmokeIT --runs 5 \
#     --main meta/muse-glimmer-30b --main-on '<json>' --main-off '<json>' \
#     --rewrite meta/muse-glimmer-30b --rewrite-extra '<json>' \
#     --qdrant localhost:6334/products
#
# Opciones:
#   --smoke <Clase[#método]>  smoke a correr (obligatorio)
#   --runs <n>                corridas (default 1)
#   --main <modelo>           modelo principal
#   --main-on / --main-off    extra-body (JSON) con el razonamiento prendido / apagado
#   --rewrite <modelo>        modelo de reescritura
#   --rewrite-extra <json>    extra-body (JSON) de la reescritura
#   --qdrant host:port/col    colección ya indexada (-Dsmoke.qdrant.*); se verifica
#                             antes de cada corrida por la API REST (puerto 6333)
#   --pause <s>               pausa entre corridas (default 60)
#   --ledger <archivo>        ledger (default target/model-bench/ledger.tsv)
#   --estimate <n>            requests a NVIDIA estimadas por corrida (pisa la tabla)
#   --dry-run                 imprime los comandos y el control del tope, sin correr
#   -- <args>                 se agregan tal cual a ./mvnw (por ejemplo -Dspike.probe-variants=...)
#
# Topes: MODEL_BENCH_CAP_NVIDIA (500) y MODEL_BENCH_CAP_GEMINI (250), que se
# fijan en el ledger al crearlo. Reporte: python3 scripts/model_bench_report.py.
#
# Compatible con el bash 3.2 de macOS.

set -euo pipefail

cd "$(dirname "$0")/.."

SMOKE=""
RUNS=1
MAIN=""
MAIN_ON=""
MAIN_OFF=""
REWRITE=""
REWRITE_EXTRA=""
QDRANT=""
PAUSE=60
LEDGER="target/model-bench/ledger.tsv"
ESTIMATE=""
DRY_RUN=false
EXTRA=()

die() {
  echo "model-bench: $*" >&2
  exit 2
}

while [ $# -gt 0 ]; do
  case "$1" in
    --smoke) SMOKE="$2"; shift 2 ;;
    --runs) RUNS="$2"; shift 2 ;;
    --main) MAIN="$2"; shift 2 ;;
    --main-on) MAIN_ON="$2"; shift 2 ;;
    --main-off) MAIN_OFF="$2"; shift 2 ;;
    --rewrite) REWRITE="$2"; shift 2 ;;
    --rewrite-extra) REWRITE_EXTRA="$2"; shift 2 ;;
    --qdrant) QDRANT="$2"; shift 2 ;;
    --pause) PAUSE="$2"; shift 2 ;;
    --ledger) LEDGER="$2"; shift 2 ;;
    --estimate) ESTIMATE="$2"; shift 2 ;;
    --dry-run) DRY_RUN=true; shift ;;
    --) shift; EXTRA=("$@"); break ;;
    -h|--help) sed -n '2,32p' "$0"; exit 0 ;;
    *) die "opción desconocida: $1" ;;
  esac
done

[ -n "$SMOKE" ] || die "falta --smoke"
case "$RUNS" in ''|*[!0-9]*) die "--runs tiene que ser un entero" ;; esac

CLASS="${SMOKE%%#*}"
METHOD=""
[ "$CLASS" != "$SMOKE" ] && METHOD="${SMOKE#*#}"

# Requests estimadas por corrida (D5): NVIDIA y Gemini.
estimate() {
  case "$SMOKE" in
    ModelSpikeSmokeIT) echo "19 0" ;;
    'ModelSpikeSmokeIT#controlledToolCalling') echo "4 0" ;;
    'ModelSpikeSmokeIT#thinkingProbe') echo "12 0" ;;
    ModelSpikeSmokeIT\#*) echo "19 0" ;;
    RewriteEvalSmokeIT*) echo "10 20" ;;
    ChatEndToEndSmokeIT) echo "30 15" ;;
    ChatEndToEndSmokeIT\#*) echo "6 2" ;;
    ToolsEndToEndSmokeIT) echo "40 17" ;;
    ToolsEndToEndSmokeIT\#*) echo "8 3" ;;
    MultiTurnCartSmokeIT) echo "35 12" ;;
    MultiTurnCartSmokeIT\#*) echo "20 6" ;;
    *) echo "40 20" ;;
  esac
}
read -r EST_NVIDIA EST_GEMINI <<< "$(estimate)"
[ -n "$ESTIMATE" ] && EST_NVIDIA="$ESTIMATE"

# --- Ledger -----------------------------------------------------------------

CAP_NVIDIA="${MODEL_BENCH_CAP_NVIDIA:-500}"
CAP_GEMINI="${MODEL_BENCH_CAP_GEMINI:-250}"

init_ledger() {
  mkdir -p "$(dirname "$LEDGER")"
  {
    printf '# cap\tnvidia\t%s\n' "$CAP_NVIDIA"
    printf '# cap\tgemini\t%s\n' "$CAP_GEMINI"
    printf 'started\tfinished\tsmoke\trun\tmain\trewrite\tnvidia\tgemini\tsource\texit\tlog\n'
  } > "$LEDGER"
}

if [ -f "$LEDGER" ]; then
  CAP_NVIDIA=$(awk -F'\t' '$1=="# cap" && $2=="nvidia" {print $3}' "$LEDGER")
  CAP_GEMINI=$(awk -F'\t' '$1=="# cap" && $2=="gemini" {print $3}' "$LEDGER")
elif [ "$DRY_RUN" = false ]; then
  init_ledger
fi

consumed() {
  # $1: columna (7 nvidia, 8 gemini)
  if [ -f "$LEDGER" ]; then
    awk -F'\t' -v col="$1" '$1 !~ /^#/ && $1 != "started" {sum += $col} END {print sum + 0}' \
      "$LEDGER"
  else
    echo 0
  fi
}

last_finished() {
  if [ -f "$LEDGER" ]; then
    awk -F'\t' '$1 !~ /^#/ && $1 != "started" {last = $2} END {print last + 0}' "$LEDGER"
  else
    echo 0
  fi
}

# --- Comando ------------------------------------------------------------------

build_command() {
  CMD=(./mvnw -Psmoke verify -Dtest=NoUnitTests -Dsurefire.failIfNoSpecifiedTests=false
    "-Dit.test=$SMOKE")
  if [ "$CLASS" = "ModelSpikeSmokeIT" ]; then
    [ -n "$MAIN" ] && CMD+=("-Dspike.main-model=$MAIN")
    [ -n "$MAIN_ON" ] && CMD+=("-Dspike.main-on=$MAIN_ON")
    [ -n "$MAIN_OFF" ] && CMD+=("-Dspike.main-off=$MAIN_OFF")
    [ -n "$REWRITE" ] && CMD+=("-Dspike.rewrite-model=$REWRITE")
    [ -n "$REWRITE_EXTRA" ] && CMD+=("-Dspike.rewrite-extra=$REWRITE_EXTRA")
  else
    [ -n "$MAIN" ] && CMD+=("-Dspring.ai.openai.chat.options.model=$MAIN")
    [ -n "$REWRITE" ] && CMD+=("-Dretail.assistant.models.rewrite=$REWRITE")
    local json=""
    [ -n "$MAIN_ON" ] && json="$json\"retail.assistant.chat.reasoning.on-extra-body\":$MAIN_ON,"
    [ -n "$MAIN_OFF" ] && json="$json\"retail.assistant.chat.reasoning.off-extra-body\":$MAIN_OFF,"
    [ -n "$REWRITE_EXTRA" ] && json="$json\"retail.assistant.rewrite.extra-body\":$REWRITE_EXTRA,"
    [ -n "$json" ] && CMD+=("-Dspring.application.json={${json%,}}")
  fi
  if [ -n "$QDRANT" ]; then
    local hostport="${QDRANT%%/*}" collection="products"
    [ "$hostport" != "$QDRANT" ] && collection="${QDRANT#*/}"
    CMD+=("-Dsmoke.qdrant.host=${hostport%%:*}")
    [ "${hostport#*:}" != "$hostport" ] && CMD+=("-Dsmoke.qdrant.port=${hostport#*:}")
    CMD+=("-Dsmoke.qdrant.collection=$collection")
  fi
  if [ ${#EXTRA[@]} -gt 0 ]; then
    CMD+=("${EXTRA[@]}")
  fi
}

print_command() {
  local out="" arg
  for arg in "${CMD[@]}"; do
    case "$arg" in
      *[\ \"\'\{\}\[\]\#\$]*) out="$out '${arg//\'/\'\\\'\'}'" ;;
      *) out="$out $arg" ;;
    esac
  done
  echo "${out# }"
}

check_qdrant() {
  [ -n "$QDRANT" ] || return 0
  local hostport="${QDRANT%%/*}" collection="products" points
  [ "$hostport" != "$QDRANT" ] && collection="${QDRANT#*/}"
  points=$(curl -sf "http://${hostport%%:*}:6333/collections/$collection" \
    | python3 -c 'import json,sys; print(json.load(sys.stdin)["result"]["points_count"])' \
    2>/dev/null || echo "?")
  if [ "$points" != "${MODEL_BENCH_EXPECTED_POINTS:-80}" ]; then
    die "la colección $collection tiene $points puntos (se esperan ${MODEL_BENCH_EXPECTED_POINTS:-80}): no se corre para no reindexar"
  fi
}

# Requests medidas en el log: bench.usage y, para el chat, la suma de
# nvidiaRequests de las líneas assistant.turn (la mayor de las dos).
measured() {
  python3 - "$1" <<'PY'
import re, sys
nvidia = gemini = turns = 0
usage = False
for line in open(sys.argv[1], encoding="utf-8", errors="replace"):
    m = re.search(r"bench\.usage smoke=\S+ nvidia=(\d+) gemini=(\d+)", line)
    if m:
        usage = True
        nvidia += int(m.group(1)); gemini += int(m.group(2))
    t = re.search(r"assistant\.turn\s+:\s+session=.*\bnvidiaRequests=(\d+)", line)
    if t:
        turns += int(t.group(1))
print(max(nvidia, turns), gemini, "medido" if usage or turns else "estimado")
PY
}

# --- Corridas -----------------------------------------------------------------

build_command
TOTAL_NVIDIA=$(consumed 7)
TOTAL_GEMINI=$(consumed 8)

if [ "$DRY_RUN" = true ]; then
  echo "# ledger $LEDGER: NVIDIA $TOTAL_NVIDIA/$CAP_NVIDIA, Gemini $TOTAL_GEMINI/$CAP_GEMINI"
  echo "# estimación por corrida: NVIDIA $EST_NVIDIA, Gemini $EST_GEMINI"
fi

for run in $(seq 1 "$RUNS"); do
  if [ $((TOTAL_NVIDIA + EST_NVIDIA)) -gt "$CAP_NVIDIA" ] \
      || [ $((TOTAL_GEMINI + EST_GEMINI)) -gt "$CAP_GEMINI" ]; then
    echo "model-bench: la corrida $run de $SMOKE superaría el tope (NVIDIA $TOTAL_NVIDIA+$EST_NVIDIA/$CAP_NVIDIA, Gemini $TOTAL_GEMINI+$EST_GEMINI/$CAP_GEMINI): no se corre" >&2
    exit 3
  fi
  if [ "$DRY_RUN" = true ]; then
    echo "# corrida $run/$RUNS"
    print_command
    TOTAL_NVIDIA=$((TOTAL_NVIDIA + EST_NVIDIA))
    TOTAL_GEMINI=$((TOTAL_GEMINI + EST_GEMINI))
    continue
  fi

  # Pausa desde la corrida anterior (también si fue de otra invocación).
  wait=$(( $(last_finished) + PAUSE - $(date +%s) ))
  if [ "$wait" -gt 0 ]; then
    echo "model-bench: pausa de $wait s"
    sleep "$wait"
  fi
  check_qdrant

  stamp=$(date +%Y%m%d-%H%M%S)
  log="target/model-bench/$stamp-${SMOKE//[#+]/_}-$run.log"
  mkdir -p target/model-bench
  echo "model-bench: corrida $run/$RUNS de $SMOKE -> $log"
  started=$(date +%s)
  set +e
  "${CMD[@]}" > "$log" 2>&1
  status=$?
  set -e
  finished=$(date +%s)
  read -r nvidia gemini source <<< "$(measured "$log")"
  if [ "$source" = "estimado" ]; then
    nvidia=$EST_NVIDIA
    gemini=$EST_GEMINI
  fi
  printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n' "$started" "$finished" "$SMOKE" "$run" \
    "${MAIN:--}" "${REWRITE:--}" "$nvidia" "$gemini" "$source" "$status" "$log" >> "$LEDGER"
  TOTAL_NVIDIA=$((TOTAL_NVIDIA + nvidia))
  TOTAL_GEMINI=$((TOTAL_GEMINI + gemini))
  grep -E "bench\.|Tests run:|sincronización" "$log" | grep -v "^\[INFO\] Tests run: 0" || true
  echo "model-bench: exit $status, NVIDIA $nvidia ($source), Gemini $gemini;" \
    "ledger NVIDIA $TOTAL_NVIDIA/$CAP_NVIDIA, Gemini $TOTAL_GEMINI/$CAP_GEMINI"

  # La colección se reutiliza: si la sincronización embebió algo, se corta (D5).
  if grep -Eq "SyncReport\[[^]]*embedded=[1-9]" "$log"; then
    echo "model-bench: la sincronización embebió productos (reindexación): se corta la serie" >&2
    exit 4
  fi
  if grep -q "llm-quota-exceeded" "$log"; then
    echo "model-bench: la corrida recibió un 429 (llm-quota-exceeded): queda invalidada (D2)" >&2
  fi
done
