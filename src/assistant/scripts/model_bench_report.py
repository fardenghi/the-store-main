#!/usr/bin/env python3
"""Reporte del benchmark de modelos (select-assistant-models, D6).

Lee los .log de las corridas de scripts/model-bench.sh (o cualquier salida de
./mvnw -Psmoke verify) y arma en Markdown una tabla por corrida y otra total
con los criterios de aceptación de design.md: M1 a M8 (modelo principal) y R1
a R4 (reescritura), cada uno con su valor, umbral, línea base y ✔/✘.

Líneas que parsea:

  assistant.turn    : session=... rewrite=ok ... firstFragmentMs=1234 ...  (log del assistant)
  bench.result smoke=... scenario=... outcome=pass|fail|provider-error [added=...] ...
  bench.rewrite smoke=... id=... outcome=ok|fallback|invalid latencyMs=... [rawHits= rewrittenHits=]
  bench.capability model=... capability=... ok=true|false ...
  bench.usage smoke=... nvidia=... gemini=...

Los percentiles son nearest-rank. Solo usa la biblioteca estándar.

Uso:
  python3 scripts/model_bench_report.py [--role main|rewrite|all] LOG_O_DIRECTORIO...
"""

import argparse
import math
import os
import re
import sys
from collections import Counter, OrderedDict

ANSI = re.compile(r"\x1b\[[0-9;]*m")
# La línea del logger assistant.turn tal como la escribe el patrón de consola
# de Spring Boot ("assistant.turn   : session=..."). La copia que imprime
# MultiTurnCartSmokeIT al final ("assistant.turn session=...") no tiene ":" y
# no se cuenta dos veces.
TURN = re.compile(r"assistant\.turn\s+:\s+(session=.*)$")
BENCH = re.compile(r"^.*?\b(bench\.(?:result|rewrite|capability|usage))\s+(.*)$")
FIELD = re.compile(r'(\w+)=("(?:[^"\\]|\\.)*"|\[[^\]]*\]|\S*)')

# M1: capacidades que tiene que mostrar el spike.
CAPABILITIES = [
    "streaming",
    "reasoning-off",
    "reasoning-on",
    "tool-calling-stream",
    "controlled-tool-calls",
    "second-round",
    "tool-choice-none",
    "tool-choice-required",
]

# M6: escenarios estables (design.md, D2).
STABLE = {
    "ChatEndToEndSmokeIT": [
        "recommendationWithRealProducts",
        "productTheStoreDoesNotSell",
        "referenceToThePreviousTurnAndIsolatedSessions",
        "simpleSearchWithoutReasoning",
        "doesNotRevealTheSystemPrompt",
    ],
    "ToolsEndToEndSmokeIT": [
        "priceRightNowComesFromGetProductDetails",
        "lampsUnder100CheapestFirst",
        "diningTableUnder300InSpanish",
        "addThatOneToMyCart",
        "addTwoOfTheFirstOne",
        "cartsDownIsNotConfirmed",
    ],
}

# Inestables: se informan con su tasa, sin umbral.
UNSTABLE = {
    "ChatEndToEndSmokeIT": [
        "greetingDoesNotSearch",
        "cheaper",
        "notALamp",
        "justifiedComparisonWithReasoning",
        "answersInTheUserLanguage",
    ],
    "ToolsEndToEndSmokeIT": [
        "stalePayloadPriceIsNotShown",
        "ambiguousRequestAsksInsteadOfAdding",
    ],
}

AMBIGUOUS = ("ToolsEndToEndSmokeIT", "ambiguousRequestAsksInsteadOfAdding")
MULTI_TURN = "MultiTurnCartSmokeIT"

# Umbrales fijados antes de la primera corrida (D2, D3, D5).
M4_REQUIRED, M4_SESSIONS = 10, 15
M5_MAX, M5_SAMPLES = 1, 5
M7_OFF_P50, M7_OFF_P95, M7_ON_MAX = 3000, 8000, 30000
M8_MAX = 3.5
R1_MAX_INVALID = 1
R2_P50_BELOW, R2_P95_MAX = 1500, 5000
R3_MAX_RATIO = 0.05
R4_MEAN_MIN, R4_RAW = 45, 42
REWRITE_SAMPLES = 40

PASS, FAIL, PENDING, NO_DATA = "✔", "✘", "pendiente", "sin datos"


def parse_fields(text):
    fields = OrderedDict()
    for key, value in FIELD.findall(text):
        if value.startswith('"') and value.endswith('"'):
            value = value[1:-1].replace('\\"', '"').replace("\\\\", "\\")
        fields[key] = value
    return fields


class Run:
    """Una corrida: un .log."""

    def __init__(self, name):
        self.name = name
        self.turns = []
        self.results = []
        self.rewrites = []
        self.capabilities = []
        self.usage = []

    @property
    def smokes(self):
        names = [r["smoke"] for r in self.results] + [r["smoke"] for r in self.rewrites]
        names += [u["smoke"] for u in self.usage]
        return sorted(set(n for n in names if n))


def parse_lines(name, lines):
    run = Run(name)
    for raw in lines:
        line = ANSI.sub("", raw.rstrip("\n"))
        turn = TURN.search(line)
        if turn:
            run.turns.append(parse_fields(turn.group(1)))
            continue
        bench = BENCH.match(line)
        if not bench:
            continue
        kind, rest = bench.groups()
        fields = parse_fields(rest)
        if kind == "bench.result":
            run.results.append(fields)
        elif kind == "bench.rewrite":
            run.rewrites.append(fields)
        elif kind == "bench.capability":
            run.capabilities.append(fields)
        elif kind == "bench.usage":
            run.usage.append(fields)
    return run


def load(paths):
    files = []
    for path in paths:
        if os.path.isdir(path):
            files += sorted(os.path.join(path, f) for f in os.listdir(path) if f.endswith(".log"))
        else:
            files.append(path)
    runs = []
    for path in files:
        with open(path, encoding="utf-8", errors="replace") as handle:
            runs.append(parse_lines(os.path.basename(path), handle))
    return runs


def percentile(values, p):
    """Nearest-rank: el menor valor con al menos p % de las muestras a su izquierda."""
    if not values:
        return None
    ordered = sorted(values)
    rank = max(1, math.ceil(p / 100.0 * len(ordered)))
    return ordered[rank - 1]


def number(value):
    try:
        return int(value)
    except (TypeError, ValueError):
        return None


def model_latency(turn):
    """M7: firstFragmentMs - rewriteMs - retrievalMs - limiterWaitMs (los '-' cuentan 0)."""
    first = number(turn.get("firstFragmentMs"))
    if first is None:
        return None
    rest = sum(number(turn.get(key)) or 0 for key in ("rewriteMs", "retrievalMs", "limiterWaitMs"))
    return max(0, first - rest)


def seconds(millis):
    return "—" if millis is None else "{:.1f} s".format(millis / 1000.0).replace(".", ",")


def ratio(part, total):
    return "{}/{}".format(part, total)


# --- Métricas -------------------------------------------------------------


def capability_status(runs):
    seen = OrderedDict()
    for run in runs:
        for cap in run.capabilities:
            name = cap.get("capability")
            ok = cap.get("ok") == "true"
            # Si una capacidad se midió más de una vez, vale la última.
            seen[name] = ok
    return seen


def m1(runs):
    seen = capability_status(runs)
    measured = [c for c in CAPABILITIES if c in seen]
    if not measured:
        return NO_DATA, NO_DATA
    failed = [c for c in CAPABILITIES if c in seen and not seen[c]]
    missing = [c for c in CAPABILITIES if c not in seen]
    value = "{}/{} OK".format(len(measured) - len(failed), len(CAPABILITIES))
    if failed:
        value += " (falla: {})".format(", ".join(failed))
    if missing:
        value += " (sin medir: {})".format(", ".join(missing))
    if failed:
        return value, FAIL
    return value, PASS if not missing else PENDING


def results(runs, smoke=None, scenario=None):
    out = []
    for run in runs:
        for result in run.results:
            if smoke and result.get("smoke") != smoke:
                continue
            if scenario and result.get("scenario") != scenario:
                continue
            out.append(result)
    return out


def m2(runs):
    all_results = results(runs)
    if not all_results:
        return NO_DATA, NO_DATA
    claims = sum(number(r.get("falseClaims")) or 0 for r in all_results)
    return str(claims), PASS if claims == 0 else FAIL


def m3(runs):
    with_added = [r for r in results(runs) if "added" in r]
    if not with_added:
        return NO_DATA, NO_DATA
    wrong = sum(1 for r in with_added if r.get("added") == "wrong")
    return "{} de {} agregados".format(wrong, len(with_added)), PASS if wrong == 0 else FAIL


def m4(runs):
    sessions = results(runs, MULTI_TURN)
    if not sessions:
        return NO_DATA, NO_DATA
    ok = sum(1 for r in sessions if r.get("added") == "ok")
    provider = sum(1 for r in sessions if r.get("outcome") == "provider-error")
    value = "{} ({} provider-error)".format(ratio(ok, len(sessions)), provider)
    if ok >= M4_REQUIRED:
        return value, PASS
    remaining = max(0, M4_SESSIONS - len(sessions))
    if ok + remaining < M4_REQUIRED:
        return value, FAIL
    return value, PENDING


def m5(runs):
    samples = results(runs, *AMBIGUOUS)
    if not samples:
        return NO_DATA, NO_DATA
    added = sum(1 for r in samples if r.get("added") not in (None, "none"))
    value = "agrega sin preguntar {}".format(ratio(added, len(samples)))
    if added > M5_MAX:
        return value, FAIL
    return value, PASS if len(samples) >= M5_SAMPLES else PENDING


def full_runs(runs, smoke):
    """Corridas completas de un smoke: las que tienen más de un escenario suyo."""
    return sum(1 for run in runs
               if len([r for r in run.results if r.get("smoke") == smoke]) > 1)


def m6(runs):
    rows = []
    status = PASS
    measured = False
    for smoke, scenarios in STABLE.items():
        expected = full_runs(runs, smoke)
        for scenario in scenarios:
            outcomes = [r.get("outcome") for r in results(runs, smoke, scenario)]
            if not outcomes:
                continue
            measured = True
            passes = outcomes.count("pass")
            fails = outcomes.count("fail")
            provider = outcomes.count("provider-error")
            # Una falla del proveedor se compensa con un re-intento que pasa (D2).
            if fails or passes < max(expected, 1):
                status = FAIL
                rows.append("{} {}/{} ({} fail, {} provider-error)".format(
                    scenario, passes, len(outcomes), fails, provider))
    if not measured:
        return NO_DATA, NO_DATA
    value = "todos pasan" if not rows else "; ".join(rows)
    return value, status


def turn_lines(runs):
    return [t for run in runs for t in run.turns]


def m7(runs):
    turns = turn_lines(runs)
    off = [model_latency(t) for t in turns if t.get("reasoning") == "off"]
    on = [model_latency(t) for t in turns if t.get("reasoning") == "on"]
    off = [v for v in off if v is not None]
    on = [v for v in on if v is not None]
    if not off and not on:
        return NO_DATA, NO_DATA
    p50, p95 = percentile(off, 50), percentile(off, 95)
    top = max(on) if on else None
    value = "off: p50 {}, p95 {} ({} turnos); on: máx {} ({} turnos)".format(
        seconds(p50), seconds(p95), len(off), seconds(top), len(on))
    ok = (p50 is None or p50 <= M7_OFF_P50) and (p95 is None or p95 <= M7_OFF_P95) \
        and (top is None or top <= M7_ON_MAX)
    return value, PASS if ok else FAIL


def m8(runs):
    requests = [number(t.get("nvidiaRequests")) for t in turn_lines(runs)]
    requests = [r for r in requests if r is not None]
    if not requests:
        return NO_DATA, NO_DATA
    mean = sum(requests) / float(len(requests))
    value = "{:.2f} en {} turnos".format(mean, len(requests)).replace(".", ",", 1)
    return value, PASS if mean <= M8_MAX else FAIL


def rewrite_samples(runs):
    return [r for run in runs for r in run.rewrites]


def r1(runs):
    samples = rewrite_samples(runs)
    if not samples:
        return NO_DATA, NO_DATA
    responded = [r for r in samples if r.get("outcome") in ("ok", "invalid")]
    invalid = sum(1 for r in responded if r.get("outcome") == "invalid")
    value = "{} inválidas de {} que respondieron".format(invalid, len(responded))
    return value, PASS if invalid <= R1_MAX_INVALID else FAIL


def r2(runs):
    latencies = [number(r.get("latencyMs")) for r in rewrite_samples(runs)]
    latencies = [v for v in latencies if v is not None]
    if not latencies:
        return NO_DATA, NO_DATA
    p50, p95 = percentile(latencies, 50), percentile(latencies, 95)
    value = "p50 {}, p95 {} ({} llamadas)".format(seconds(p50), seconds(p95), len(latencies))
    return value, PASS if p50 < R2_P50_BELOW and p95 <= R2_P95_MAX else FAIL


def r3(runs):
    samples = rewrite_samples(runs)
    if not samples:
        return NO_DATA, NO_DATA
    fallback = sum(1 for r in samples if r.get("outcome") != "ok")
    total = len(samples)
    allowed = int(math.floor(R3_MAX_RATIO * max(total, REWRITE_SAMPLES) + 1e-9))
    value = "{} ({:.0f} %)".format(ratio(fallback, total), 100.0 * fallback / total)
    if fallback > allowed:
        return value, FAIL
    return value, PASS if total >= REWRITE_SAMPLES else PENDING


def eval_runs(runs):
    """Aciertos del top-5 por corrida de RewriteEvalSmokeIT: (crudo, reescrito)."""
    out = []
    for run in runs:
        rows = [r for r in run.rewrites if r.get("smoke") == "RewriteEvalSmokeIT"]
        if rows:
            out.append((sum(number(r.get("rawHits")) or 0 for r in rows),
                        sum(number(r.get("rewrittenHits")) or 0 for r in rows)))
    return out


def r4(runs):
    per_run = eval_runs(runs)
    if not per_run:
        return NO_DATA, NO_DATA
    rewritten = [w for _, w in per_run]
    mean = sum(rewritten) / float(len(rewritten))
    value = "{} (media {:.1f}) contra crudo {}".format(
        ", ".join(str(w) for w in rewritten), mean,
        ", ".join(str(r) for r, _ in per_run)).replace(".", ",")
    if not all(w > max(raw, R4_RAW) for raw, w in per_run):
        return value, FAIL
    if len(per_run) < 3:
        # La media se juzga sobre las 3 corridas.
        return value, PENDING
    return value, PASS if mean >= R4_MEAN_MIN else FAIL


MAIN_METRICS = [
    ("M1", "Capacidades (spike)", m1, "todas OK", "todas OK"),
    ("M2", "Confirmaciones falsas", m2, "0", "0"),
    ("M3", "Producto equivocado agregado", m3, "0", "0"),
    ("M4", "Sesiones multi-turno con el producto pedido", m4, "≥ 10/15", "3/6"),
    ("M5", "Ambiguo: agrega sin preguntar", m5, "≤ 1/5", "1/5"),
    ("M6", "Escenarios estables", m6, "3/3 en cada uno", "pasan"),
    ("M7", "Latencia del modelo al primer fragmento", m7,
     "off p50 ≤ 3 s y p95 ≤ 8 s; on máx ≤ 30 s", "1,3 s (spike); 18 s en una comparación"),
    ("M8", "Requests a NVIDIA por turno (media)", m8, "≤ 3,5", "3,1"),
]

REWRITE_METRICS = [
    ("R1", "JSON válido", r1, "≤ 1 inválida", "9/10"),
    ("R2", "Latencia", r2, "p50 < 1,5 s y p95 ≤ 5 s", "mediana 1,5 s; p95 ≈ 10 s"),
    ("R3", "Fallback", r3, "≤ 2/40 (5 %)", "12-13 % (48 % en la peor hora)"),
    ("R4", "Top-5 de RewriteEvalSmokeIT", r4, "media ≥ 45 y cada corrida > 42", "45, 43, 47"),
]


def metric_table(runs, metrics):
    lines = ["| Id | Métrica | Umbral | Línea base | Valor | ✔/✘ |",
             "| --- | --- | --- | --- | --- | --- |"]
    for key, label, function, threshold, baseline in metrics:
        value, status = function(runs)
        lines.append("| {} | {} | {} | {} | {} | {} |".format(
            key, label, threshold, baseline, value, status))
    return lines


def informative(runs):
    """Métricas sin umbral: salvaguarda, correcciones, inestables, latencia total y fallback."""
    turns = turn_lines(runs)
    lines = []
    if turns:
        guard = Counter(t.get("claimGuard") for t in turns if t.get("claimGuard") not in (None, "-"))
        corrections = Counter()
        for t in turns:
            if t.get("corrections") not in (None, "-"):
                corrections.update(t.get("corrections").split(","))
        first = [number(t.get("firstFragmentMs")) for t in turns]
        first = [v for v in first if v is not None]
        rewrite = [t.get("rewrite") for t in turns if t.get("rewrite") not in (None, "-")]
        fallback = sum(1 for r in rewrite if r != "ok")
        lines.append("- `claimGuard` descartados: {}".format(
            ", ".join("{} {}".format(k, v) for k, v in guard.items()) or "0"))
        lines.append("- `corrections`: {}".format(
            ", ".join("{} {}".format(k, v) for k, v in corrections.items()) or "0"))
        lines.append("- `firstFragmentMs` completo (lo que ve el usuario): p50 {}, p95 {}".format(
            seconds(percentile(first, 50)), seconds(percentile(first, 95))))
        if rewrite:
            lines.append("- Reescritura en los turnos: fallback {} ({:.0f} %)".format(
                ratio(fallback, len(rewrite)), 100.0 * fallback / len(rewrite)))
    for smoke, scenarios in UNSTABLE.items():
        for scenario in scenarios:
            outcomes = [r.get("outcome") for r in results(runs, smoke, scenario)]
            if outcomes:
                lines.append("- Inestable `{}`: pasa {}".format(
                    scenario, ratio(outcomes.count("pass"), len(outcomes))))
    return lines


def run_table(runs):
    lines = ["| Corrida | Smoke | Escenarios (pass/fail/provider) | Confirm. falsas | Agregados "
             "ok/none/wrong | Turnos | M7 off p50 | Req/turno | Reescritura ok/fallback/invalid "
             "| Top-5 crudo/reescrito | NVIDIA/Gemini |",
             "| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |"]
    for run in runs:
        outcomes = Counter(r.get("outcome") for r in run.results)
        added = Counter(r.get("added") for r in run.results if "added" in r)
        claims = sum(number(r.get("falseClaims")) or 0 for r in run.results)
        off = [model_latency(t) for t in run.turns if t.get("reasoning") == "off"]
        off = [v for v in off if v is not None]
        requests = [number(t.get("nvidiaRequests")) for t in run.turns]
        requests = [r for r in requests if r is not None]
        rw = Counter(r.get("outcome") for r in run.rewrites)
        hits = eval_runs([run])
        nvidia = sum(number(u.get("nvidia")) or 0 for u in run.usage)
        nvidia = max(nvidia, sum(requests))
        gemini = sum(number(u.get("gemini")) or 0 for u in run.usage)
        lines.append("| {} | {} | {} | {} | {} | {} | {} | {} | {} | {} | {} |".format(
            run.name, ", ".join(run.smokes) or "—",
            "{}/{}/{}".format(outcomes["pass"], outcomes["fail"], outcomes["provider-error"])
            if run.results else "—",
            claims if run.results else "—",
            "{}/{}/{}".format(added["ok"], added["none"], added["wrong"]) if added else "—",
            len(run.turns) or "—",
            seconds(percentile(off, 50)) if off else "—",
            "{:.2f}".format(sum(requests) / float(len(requests))).replace(".", ",")
            if requests else "—",
            "{}/{}/{}".format(rw["ok"], rw["fallback"], rw["invalid"]) if run.rewrites else "—",
            "{}/{}".format(*hits[0]) if hits else "—",
            "{}/{}".format(nvidia, gemini)))
    return lines


def report(runs, role="all"):
    out = ["# Benchmark de modelos", "", "## Por corrida", ""]
    out += run_table(runs)
    out += ["", "## Totales", ""]
    if role in ("all", "main"):
        out += ["### Modelo principal (D2)", ""] + metric_table(runs, MAIN_METRICS)
        extra = informative(runs)
        if extra:
            out += ["", "Sin umbral:", ""] + extra
        out.append("")
    if role in ("all", "rewrite"):
        out += ["### Reescritura (D3)", ""] + metric_table(runs, REWRITE_METRICS)
        out.append("")
    return "\n".join(out)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    parser.add_argument("--role", choices=("all", "main", "rewrite"), default="all")
    parser.add_argument("paths", nargs="+", help=".log de las corridas o directorios con .log")
    args = parser.parse_args(argv)
    runs = load(args.paths)
    if not runs:
        print("No hay corridas en {}".format(args.paths), file=sys.stderr)
        return 1
    print(report(runs, args.role))
    return 0


if __name__ == "__main__":
    sys.exit(main())
