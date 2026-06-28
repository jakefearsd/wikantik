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

def test_cap_hit_no_consecutive_roles_and_no_tools():
    """Cap-hit final call must use messages as-is (strict role alternation, no tools key)."""
    max_iters = 2
    call_count = {"n": 0}
    captured_payloads = []

    def fake_http(payload):
        captured_payloads.append(payload)
        n = call_count["n"]
        call_count["n"] += 1
        if n < max_iters:
            return _tool_resp("search_knowledge", {"q": "x"}, tid=f"t{n}")
        return _text_resp("capped answer")

    def exec_tool(name, inp):
        return "result"

    out = ac.run_tool_loop(CFG, "m", "sys", "question",
                           tools=[{"name": "search_knowledge", "input_schema": {}}],
                           exec_tool=exec_tool, http=fake_http, max_iters=max_iters)

    assert out["answer"] == "capped answer"
    assert len(out["tool_calls"]) == max_iters

    # Final payload is the cap-hit call (tools-less)
    final_payload = captured_payloads[-1]
    assert "tools" not in final_payload, "cap-hit call must not include a tools key"

    # No two consecutive messages with the same role
    msgs = final_payload["messages"]
    for i in range(len(msgs) - 1):
        assert msgs[i]["role"] != msgs[i + 1]["role"], (
            f"Consecutive same role '{msgs[i]['role']}' at positions {i} and {i + 1}"
        )
