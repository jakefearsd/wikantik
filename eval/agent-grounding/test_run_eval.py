import run_eval
from types import SimpleNamespace

CFG = SimpleNamespace(agent_model="m", base_url="x", mcp_key="k", lexical=False, max_tool_iters=3, samples=1)
QS = [{"id": "q1", "question": "?", "reference": "r", "expect_sources": ["P"], "difficulty": "easy"}]

class StubArms:
    def cold(self, cfg, q, http=None): return {"arm": "cold", "qid": q["id"], "answer": "c", "tool_calls": [], "cited_pages": [], "error": None}
    def grounded_bundle(self, cfg, q, http=None): return {"arm": "grounded_bundle", "qid": q["id"], "answer": "b", "tool_calls": [], "cited_pages": [], "error": None}
    def grounded_mcp(self, cfg, q, mcp=None, http=None):
        raise RuntimeError("boom")

def test_run_all_three_arms_and_isolates_errors():
    rows = run_eval.run_all(CFG, QS, StubArms(), mcp=None)
    arms_seen = {r["arm"] for r in rows}
    assert arms_seen == {"cold", "grounded_bundle", "grounded_mcp"}
    err = [r for r in rows if r["arm"] == "grounded_mcp"][0]
    assert err["error"] and "boom" in err["error"]

def test_main_exits_on_bare_run_id_flag():
    """--run-id with no following value must SystemExit, not IndexError."""
    import pytest
    with pytest.raises(SystemExit):
        run_eval.main(["--run-id"])
