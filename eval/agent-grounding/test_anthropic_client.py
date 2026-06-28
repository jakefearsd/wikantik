import anthropic_client as ac
from types import SimpleNamespace

CFG = SimpleNamespace(anthropic_key="k")

def _text_resp(t):
    return {"stop_reason": "end_turn", "content": [{"type": "text", "text": t}]}

def _tool_resp(name, inp, tid="t1"):
    return {"stop_reason": "tool_use",
            "content": [{"type": "tool_use", "id": tid, "name": name, "input": inp}]}

def test_extract_text_concatenates():
    r = {"content": [{"type": "text", "text": "a"}, {"type": "tool_use"},
                     {"type": "text", "text": "b"}]}
    assert ac.extract_text(r) == "ab"

def test_run_tool_loop_executes_then_answers():
    scripted = [_tool_resp("search_knowledge", {"q": "x"}), _text_resp("final answer")]
    calls = {"i": 0}
    def fake_http(payload):
        r = scripted[calls["i"]]; calls["i"] += 1; return r
    executed = []
    def exec_tool(name, inp):
        executed.append((name, inp)); return "tool said hi"
    out = ac.run_tool_loop(CFG, "m", "sys", "question?",
                           tools=[{"name": "search_knowledge", "input_schema": {}}],
                           exec_tool=exec_tool, http=fake_http, max_iters=6)
    assert out["answer"] == "final answer"
    assert executed == [("search_knowledge", {"q": "x"})]
    assert out["tool_calls"][0]["name"] == "search_knowledge"
    assert out["tool_calls"][0]["result_excerpt"].startswith("tool said hi")

def test_run_tool_loop_caps_iterations():
    def fake_http(payload):
        return _tool_resp("search_knowledge", {"q": "loop"})
    def exec_tool(name, inp):
        return "again"
    out = ac.run_tool_loop(CFG, "m", "sys", "q",
                           tools=[{"name": "search_knowledge", "input_schema": {}}],
                           exec_tool=exec_tool, http=fake_http, max_iters=3)
    assert len(out["tool_calls"]) == 3  # stopped at the cap
