import arms
from types import SimpleNamespace

CFG = SimpleNamespace(anthropic_key="k", agent_model="m", base_url="http://x",
                      mcp_key="mk", lexical=False, max_tool_iters=4)
Q = {"id": "q1", "question": "How does fusion work?", "reference": "...",
     "expect_sources": ["HybridRetrieval"], "difficulty": "easy"}

def test_mcp_tools_to_anthropic_shape():
    out = arms.mcp_tools_to_anthropic([{"name": "search_knowledge", "description": "d",
                                        "input_schema": {"type": "object"}}])
    assert out[0]["name"] == "search_knowledge"
    assert out[0]["input_schema"] == {"type": "object"}

def test_cold_arm(monkeypatch):
    monkeypatch.setattr(arms.anthropic_client, "complete",
                        lambda *a, **k: {"content": [{"type": "text", "text": "cold ans. Sources: [HybridRetrieval]"}]})
    r = arms.cold(CFG, Q)
    assert r["arm"] == "cold" and "cold ans" in r["answer"]
    assert r["tool_calls"] == []
    assert "HybridRetrieval" in r["cited_pages"]

def test_grounded_bundle_arm(monkeypatch):
    monkeypatch.setattr(arms.bundle_client, "fetch_bundle",
                        lambda base, q, lexical=False, http=None:
                            {"context": "RRF fuses BM25+dense", "cited_pages": ["HybridRetrieval"]})
    monkeypatch.setattr(arms.anthropic_client, "complete",
                        lambda *a, **k: {"content": [{"type": "text", "text": "grounded"}]})
    r = arms.grounded_bundle(CFG, Q)
    assert r["arm"] == "grounded_bundle"
    assert r["cited_pages"] == ["HybridRetrieval"]  # from the bundle

def test_grounded_mcp_arm(monkeypatch):
    class FakeMcp:
        def connect(self): pass
        def list_tools(self): return [{"name": "assemble_bundle", "description": "d", "input_schema": {}}]
        def call_tool(self, n, a): return "section from HybridRetrieval"
    monkeypatch.setattr(arms.anthropic_client, "run_tool_loop",
                        lambda *a, **k: {"answer": "ans. Sources: [HybridRetrieval]",
                                         "tool_calls": [{"name": "assemble_bundle", "input": {},
                                                         "result_excerpt": "section from HybridRetrieval"}]})
    r = arms.grounded_mcp(CFG, Q, mcp=FakeMcp())
    assert r["arm"] == "grounded_mcp"
    assert r["tool_calls"][0]["name"] == "assemble_bundle"
    assert "HybridRetrieval" in r["cited_pages"]
