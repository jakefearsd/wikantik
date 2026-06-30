import grade
import ollama_client
from types import SimpleNamespace

CFG = SimpleNamespace(provider="ollama", judge_model="m", ollama_base_url="http://o",
                      anthropic_key=None)


def _ollama_text(t):
    return lambda *a, **k: {"message": {"content": t}}


def test_citation_hit_case_insensitive():
    assert grade.citation_hit(["hybridretrieval"], ["HybridRetrieval"]) is True
    assert grade.citation_hit(["Other"], ["HybridRetrieval"]) is False
    assert grade.citation_hit([], ["X"]) is False


def test_judge_one_parses_score(monkeypatch):
    monkeypatch.setattr(ollama_client, "complete",
        _ollama_text("The answer matches the reference.\nSCORE: 2"))
    score, rationale = grade.judge_one(CFG, {"question": "q", "reference": "r"}, "ans")
    assert score == 2 and "matches" in rationale


def test_judge_one_defaults_to_zero_on_unparseable(monkeypatch):
    monkeypatch.setattr(ollama_client, "complete", _ollama_text("no score here"))
    score, _ = grade.judge_one(CFG, {"question": "q", "reference": "r"}, "ans")
    assert score == 0


def test_judge_one_last_match_anchored(monkeypatch):
    """Embedded 'SCORE: 0' in prose must not win; the final standalone line 'SCORE: 2' must."""
    monkeypatch.setattr(ollama_client, "complete",
        _ollama_text("I considered SCORE: 0 but chose the best answer.\nSCORE: 2"))
    score, _ = grade.judge_one(CFG, {"question": "q", "reference": "r"}, "ans")
    assert score == 2


def test_grade_run_attaches_fields(monkeypatch):
    monkeypatch.setattr(grade, "judge_one", lambda cfg, q, a, http=None: (2, "ok"))
    raw = {"results": [{"arm": "cold", "qid": "q1", "answer": "a",
                        "tool_calls": [], "cited_pages": ["HybridRetrieval"], "error": None}]}
    qbi = {"q1": {"question": "q", "reference": "r", "expect_sources": ["HybridRetrieval"]}}
    graded = grade.grade_run(CFG, raw, qbi)
    assert graded[0]["correctness"] == 2
    assert graded[0]["citation_hit"] is True
