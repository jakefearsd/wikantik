import ollama_client as oc
from types import SimpleNamespace

CFG = SimpleNamespace(ollama_base_url="http://x")


def _text_resp(t):
    return {"message": {"role": "assistant", "content": t}}


def _tool_resp(name, args):
    return {"message": {"role": "assistant", "content": "",
                        "tool_calls": [{"function": {"name": name, "arguments": args}}]}}


def test_extract_text_reads_message_content():
    assert oc.extract_text({"message": {"content": "hello"}}) == "hello"


def test_extract_text_empty_when_no_message():
    assert oc.extract_text({}) == ""


def test_tools_from_mcp_shape():
    out = oc.tools_from_mcp([{"name": "search_knowledge", "description": "d",
                             "input_schema": {"type": "object"}}])
    assert out[0]["type"] == "function"
    assert out[0]["function"]["name"] == "search_knowledge"
    assert out[0]["function"]["parameters"] == {"type": "object"}


def test_tools_from_mcp_defaults_empty_params():
    out = oc.tools_from_mcp([{"name": "t"}])
    assert out[0]["function"]["parameters"] == {"type": "object"}


def test_complete_prepends_system_and_forces_think_false():
    captured = {}
    def fake_http(payload):
        captured.update(payload); return _text_resp("ok")
    oc.complete(CFG, "m", "sysprompt", [{"role": "user", "content": "hi"}], http=fake_http)
    assert captured["think"] is False
    assert captured["stream"] is False
    assert captured["messages"][0] == {"role": "system", "content": "sysprompt"}
    assert captured["messages"][1] == {"role": "user", "content": "hi"}
    assert "tools" not in captured


def test_run_tool_loop_executes_then_answers():
    scripted = [_tool_resp("search_knowledge", {"q": "x"}), _text_resp("final answer")]
    i = {"n": 0}
    def fake_http(payload):
        r = scripted[i["n"]]; i["n"] += 1; return r
    executed = []
    def exec_tool(name, inp):
        executed.append((name, inp)); return "tool said hi"
    out = oc.run_tool_loop(CFG, "m", "sys", "question?",
                           tools=[{"type": "function", "function": {"name": "search_knowledge"}}],
                           exec_tool=exec_tool, http=fake_http, max_iters=6)
    assert out["answer"] == "final answer"
    assert executed == [("search_knowledge", {"q": "x"})]
    assert out["tool_calls"][0]["name"] == "search_knowledge"
    assert out["tool_calls"][0]["result_excerpt"].startswith("tool said hi")


def test_run_tool_loop_appends_role_tool_result():
    """After a tool_call, the next turn carries a role:tool message with the result."""
    scripted = [_tool_resp("search_knowledge", {"q": "x"}), _text_resp("done")]
    i = {"n": 0}; payloads = []
    def fake_http(payload):
        payloads.append(payload)
        r = scripted[i["n"]]; i["n"] += 1; return r
    oc.run_tool_loop(CFG, "m", "sys", "q",
                     tools=[{"type": "function", "function": {"name": "search_knowledge"}}],
                     exec_tool=lambda n, a: "RESULT", http=fake_http, max_iters=6)
    second = payloads[1]["messages"]
    tool_msgs = [m for m in second if m.get("role") == "tool"]
    assert tool_msgs and tool_msgs[0]["content"] == "RESULT"
    assert tool_msgs[0]["tool_name"] == "search_knowledge"


def test_run_tool_loop_caps_iterations_and_final_omits_tools():
    payloads = []
    def fake_http(payload):
        payloads.append(payload)
        return _tool_resp("search_knowledge", {"q": "loop"})
    out = oc.run_tool_loop(CFG, "m", "sys", "q",
                           tools=[{"type": "function", "function": {"name": "search_knowledge"}}],
                           exec_tool=lambda n, a: "again", http=fake_http, max_iters=3)
    assert len(out["tool_calls"]) == 3  # stopped at the cap
    assert "tools" not in payloads[-1], "cap-hit call must omit tools"
