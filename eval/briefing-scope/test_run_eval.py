"""Pure-function tests for the briefing-scope runner. Stdlib only; no network."""
import unittest

import run_eval

MEMBERS = {"roller": {"RollerHub", "RollerMcp"}}


def section(slug, text="x" * 40):
    return {"slug": slug, "text": text, "headingPath": ["H"], "score": 0.5}


def item(slug, origin="cluster", included=True, content="y" * 40):
    return {"slug": slug, "origin": origin, "included": included, "content": content}


def briefing(sections=(), items=(), warnings=()):
    return {"sections": list(sections), "items": list(items),
            "warnings": list(warnings), "usedTokens": 100}


def case(**kw):
    base = {"id": "c", "clusters": ["roller"], "prompt": "p"}
    base.update(kw)
    return base


class EvaluateTest(unittest.TestCase):
    def test_body_violation_detected_for_included_item(self):
        c = case(must_not_include_as_body=["StockroomPhase7PhotoIdentificationPlan"])
        b = briefing(items=[item("StockroomPhase7PhotoIdentificationPlan", "cluster", True)])
        r = run_eval.evaluate(c, b, MEMBERS)
        self.assertFalse(r.passed)
        self.assertTrue(any("StockroomPhase7PhotoIdentificationPlan" in v for v in r.violations))

    def test_body_violation_detected_for_section_and_glob(self):
        c = case(must_not_include_as_body=["Incident*"])
        r = run_eval.evaluate(c, briefing(sections=[section("IncidentRunbookX")]), MEMBERS)
        self.assertFalse(r.passed)

    def test_pointer_does_not_violate_body_rule(self):
        c = case(must_not_include_as_body=["RollerTelemetryHandoffSpec"],
                 may_point_to=["RollerTelemetryHandoffSpec"])
        b = briefing(items=[item("RollerTelemetryHandoffSpec", "elsewhere", False, "")])
        r = run_eval.evaluate(c, b, MEMBERS)
        self.assertTrue(r.passed, r.violations)
        self.assertEqual(["RollerTelemetryHandoffSpec"], r.pointed_to)

    def test_missing_required_item_fails(self):
        c = case(must_include_as_item=["RollerHub"])
        r = run_eval.evaluate(c, briefing(items=[item("RollerHub", "cluster", False, "")]), MEMBERS)
        self.assertFalse(r.passed)
        r = run_eval.evaluate(c, briefing(items=[item("RollerHub")]), MEMBERS)
        self.assertTrue(r.passed, r.violations)

    def test_separation_score_counts_only_in_scope_tokens(self):
        # in scope: RollerHub section (40 chars) + cited item (40 chars) = 80; out of scope: 40 + 40
        b = briefing(sections=[section("RollerHub"), section("Other")],
                     items=[item("Cited", "cited"), item("Stray", "cluster")])
        r = run_eval.evaluate(case(), b, MEMBERS)
        self.assertAlmostEqual(0.5, r.separation)
        self.assertEqual(20, r.counts["in_scope_tokens"])
        self.assertEqual(40, r.counts["total_tokens"])

    def test_separation_none_without_clusters(self):
        r = run_eval.evaluate(case(clusters=[]), briefing(sections=[section("A")]), MEMBERS)
        self.assertIsNone(r.separation)

    def test_diversity_score(self):
        b = briefing(sections=[section("A"), section("A"), section("B"), section("C")])
        r = run_eval.evaluate(case(), b, MEMBERS)
        self.assertAlmostEqual(0.75, r.diversity)
        self.assertIsNone(run_eval.evaluate(case(), briefing(), MEMBERS).diversity)

    def test_per_page_cap_violation(self):
        c = case(max_sections_per_page=2)
        b = briefing(sections=[section("A"), section("A"), section("A"), section("B")])
        r = run_eval.evaluate(c, b, MEMBERS)
        self.assertFalse(r.passed)
        self.assertTrue(any("A" in v and "3" in v for v in r.violations))
        ok = run_eval.evaluate(c, briefing(sections=[section("A"), section("A")]), MEMBERS)
        self.assertTrue(ok.passed)

    def test_unknown_argument_warning_match(self):
        c = case(extra_params={"scope": "strict"}, must_warn=[r"^unknown argument: scope"])
        r = run_eval.evaluate(c, briefing(), MEMBERS)
        self.assertFalse(r.passed)
        r = run_eval.evaluate(c, briefing(warnings=["unknown argument: scope"]), MEMBERS)
        self.assertTrue(r.passed, r.violations)

    def test_must_not_warn(self):
        c = case(must_not_warn=["unknown cluster"])
        r = run_eval.evaluate(c, briefing(warnings=["Unknown cluster: stockroom"]), MEMBERS)
        self.assertFalse(r.passed)


class UrlAndMembersTest(unittest.TestCase):
    def test_build_url_appends_extra_params_and_omits_empty(self):
        u = run_eval.build_url("http://h", case(scope_mode="prefer", extra_params={"scope": "strict"}))
        self.assertIn("clusters=roller", u)
        self.assertIn("scope_mode=prefer", u)
        self.assertIn("scope=strict", u)
        self.assertIn("format=json", u)
        u = run_eval.build_url("http://h", case(clusters=[]))
        self.assertNotIn("clusters=", u)
        self.assertNotIn("scope_mode", u)

    def test_members_from_sitemap_includes_subclusters_only_by_segment(self):
        sm = {"data": {"pages": [
            {"slug": "A", "cluster": "roller"}, {"slug": "B", "cluster": "roller/sub"},
            {"slug": "C", "cluster": "rollerblades"}, {"slug": "D"}]}}
        self.assertEqual({"A", "B"}, run_eval.members_from_sitemap(sm, ["roller"])["roller"])


if __name__ == "__main__":
    unittest.main()
