import grade
from types import SimpleNamespace

CFG = SimpleNamespace(judge_model="m", anthropic_key="k")

def test_citation_hit_case_insensitive():
    assert grade.citation_hit(["hybridretrieval"], ["HybridRetrieval"]) is True
    assert grade.citation_hit(["Other"], ["HybridRetrieval"]) is False
    assert grade.citation_hit([], ["X"]) is False

def test_judge_one_parses_score(monkeypatch):
    monkeypatch.setattr(grade.anthropic_client, "complete",
        lambda *a, **k: {"content": [{"type": "text",
            "text": "The answer matches the reference.\nSCORE: 2"}]})
    score, rationale = grade.judge_one(CFG, {"question": "q", "reference": "r"}, "ans")
    assert score == 2 and "matches" in rationale

def test_judge_one_defaults_to_zero_on_unparseable(monkeypatch):
    monkeypatch.setattr(grade.anthropic_client, "complete",
        lambda *a, **k: {"content": [{"type": "text", "text": "no score here"}]})
    score, _ = grade.judge_one(CFG, {"question": "q", "reference": "r"}, "ans")
    assert score == 0

def test_grade_run_attaches_fields(monkeypatch):
    monkeypatch.setattr(grade, "judge_one", lambda cfg, q, a, http=None: (2, "ok"))
    raw = {"results": [{"arm": "cold", "qid": "q1", "answer": "a",
                        "tool_calls": [], "cited_pages": ["HybridRetrieval"], "error": None}]}
    qbi = {"q1": {"question": "q", "reference": "r", "expect_sources": ["HybridRetrieval"]}}
    graded = grade.grade_run(CFG, raw, qbi)
    assert graded[0]["correctness"] == 2
    assert graded[0]["citation_hit"] is True
