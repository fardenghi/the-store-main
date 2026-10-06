"""Tests del reporte del benchmark con los logs de ejemplo de testdata/model-bench.

Correr desde src/assistant/scripts:  python3 -m unittest test_model_bench_report
"""

import os
import unittest

import model_bench_report as report

DATA = os.path.join(os.path.dirname(os.path.abspath(__file__)), "testdata", "model-bench")


def runs(*names):
    return report.load([os.path.join(DATA, name) for name in names])


class PercentileTest(unittest.TestCase):

    def test_nearest_rank(self):
        values = [15, 20, 35, 40, 50]
        self.assertEqual(report.percentile(values, 50), 35)
        self.assertEqual(report.percentile(values, 95), 50)
        self.assertEqual(report.percentile([7], 95), 7)
        self.assertIsNone(report.percentile([], 50))


class ParseTest(unittest.TestCase):

    def test_turn_lines_from_the_logger_only(self):
        multi = runs("multiturn-1.log")[0]
        # La copia sin ":" que imprime MultiTurnCartSmokeIT al final no se cuenta.
        self.assertEqual(len(multi.turns), 1)
        self.assertEqual(multi.turns[0]["raw"], "add two of the first one to my cart")

    def test_model_latency_discounts_rewrite_retrieval_and_limiter(self):
        tools = runs("tools-1.log")[0]
        self.assertEqual([report.model_latency(t) for t in tools.turns], [2000, 3000, 8000])

    def test_reasoning_latency_is_measured_apart_from_the_text(self):
        tools = runs("tools-1.log")[0]
        compare = [t for t in tools.turns if t.get("reasoning") == "on"][0]
        self.assertEqual(report.model_latency(compare), 8000)
        self.assertEqual(report.model_latency(compare, "firstReasoningMs"), 500)
        detail = report.latency_detail(tools.turns)
        self.assertIn("primer razonamiento p50 0,5 s", detail[-1])
        self.assertIn("primer texto visible p50 8,0 s", detail[-1])

    def test_results_keep_their_fields(self):
        tools = runs("tools-1.log")[0]
        down = tools.results[-1]
        self.assertEqual(down["outcome"], "provider-error")
        self.assertEqual(down["error"], "llm-provider-unavailable")
        self.assertEqual(tools.results[1]["added"], "wrong")


class MainRoleTest(unittest.TestCase):

    def setUp(self):
        self.all = runs("spike.log", "tools-1.log", "multiturn-1.log")

    def test_m1_fails_with_a_missing_capability(self):
        value, status = report.m1(self.all)
        self.assertEqual(status, report.FAIL)
        self.assertIn("tool-choice-required", value)

    def test_m1_passes_when_every_capability_is_ok(self):
        spike = runs("spike.log")
        spike[0].capabilities[-1]["ok"] = "true"
        self.assertEqual(report.m1(spike)[1], report.PASS)

    def test_m2_counts_false_claims(self):
        self.assertEqual(report.m2(self.all), ("1", report.FAIL))

    def test_m3_detects_a_wrong_product(self):
        value, status = report.m3(self.all)
        self.assertEqual(status, report.FAIL)
        self.assertTrue(value.startswith("1 de 5"))

    def test_m4_is_pending_while_it_can_still_be_reached(self):
        value, status = report.m4(self.all)
        self.assertEqual(status, report.PENDING)
        self.assertEqual(value, "1/3 (1 provider-error)")

    def test_m4_fails_when_it_can_no_longer_be_reached(self):
        multi = runs("multiturn-1.log")
        multi[0].results *= 3   # 9 sesiones, 3 ok: aunque pasen las 6 que faltan, son 9
        self.assertEqual(report.m4(multi)[1], report.FAIL)

    def test_m5_needs_five_samples(self):
        self.assertEqual(report.m5(self.all), ("agrega sin preguntar 0/1", report.PENDING))

    def test_m6_fails_on_a_model_failure_and_on_an_uncompensated_provider_error(self):
        value, status = report.m6(self.all)
        self.assertEqual(status, report.FAIL)
        self.assertIn("addThatOneToMyCart 0/1 (1 fail, 0 provider-error)", value)
        self.assertIn("cartsDownIsNotConfirmed 0/1 (0 fail, 1 provider-error)", value)
        self.assertNotIn("priceRightNow", value)

    def test_m7_uses_reasoning_off_percentiles_and_reasoning_on_maximum(self):
        value, status = report.m7(self.all)
        self.assertEqual(status, report.PASS)
        self.assertIn("off: p50 2,0 s, p95 3,0 s (3 turnos)", value)
        self.assertIn("on: máx 8,0 s (1 turnos)", value)

    def test_m8_mean_requests_per_turn(self):
        self.assertEqual(report.m8(self.all), ("3,00 en 4 turnos", report.PASS))


class RewriteRoleTest(unittest.TestCase):

    def setUp(self):
        self.all = runs("spike.log", "rewrite-eval-1.log")

    def test_r1_counts_invalid_among_responses(self):
        self.assertEqual(report.r1(self.all), ("1 inválidas de 19 que respondieron", report.PASS))

    def test_r2_percentiles_over_every_call(self):
        self.assertEqual(report.r2(self.all), ("p50 1,1 s, p95 1,4 s (20 llamadas)", report.PASS))

    def test_r3_counts_fallback_and_invalid_and_waits_for_forty_calls(self):
        self.assertEqual(report.r3(self.all), ("2/20 (10 %)", report.PENDING))

    def test_r4_per_run_hits_against_raw(self):
        value, status = report.r4(self.all)
        self.assertEqual(status, report.PENDING)
        self.assertEqual(value, "46 (media 46,0) contra crudo 42")

    def test_r4_fails_when_a_run_does_not_beat_raw(self):
        evals = runs("rewrite-eval-1.log")
        evals[0].rewrites[-1]["rawHits"] = "10"   # crudo 46 contra reescrito 46
        evals = evals * 3
        self.assertEqual(report.r4(evals)[1], report.FAIL)


class ReportTest(unittest.TestCase):

    def test_markdown_has_run_and_total_tables(self):
        text = report.report(runs("spike.log", "rewrite-eval-1.log", "tools-1.log",
                                  "multiturn-1.log"))
        self.assertIn("| tools-1.log | ToolsEndToEndSmokeIT | 2/1/1 | 1 | 0/1/1 |", text)
        self.assertIn("| M2 | Confirmaciones falsas | 0 | 0 | 1 | ✘ |", text)
        self.assertIn("| R1 | JSON válido |", text)
        self.assertIn("Reescritura en los turnos: fallback 1/4 (25 %)", text)
        self.assertIn("`claimGuard` descartados: dropped 1", text)


if __name__ == "__main__":
    unittest.main()
