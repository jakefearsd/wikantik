"""Ollama /api/chat client + a tool-use loop. Stdlib only.

Mirrors anthropic_client's public surface (complete / run_tool_loop / extract_text /
tools_from_mcp) so the arms + judge can talk to either backend via llm_client. Ollama's
message and tool-call shapes differ from Anthropic's, so the loop is implemented here
natively rather than shared. `think` is forced false on every call — gemma/qwen reasoning
variants emit broken structured output / tool calls when thinking is on.
"""
import json
import urllib.request


def _default_http(cfg):
    url = cfg.ollama_base_url.rstrip("/") + "/api/chat"

    def post(payload):
        req = urllib.request.Request(
            url, data=json.dumps(payload).encode(),
            headers={"content-type": "application/json"}, method="POST")
        with urllib.request.urlopen(req, timeout=180) as r:
            return json.loads(r.read().decode())
    return post


def tools_from_mcp(tools):
    """MCP tool defs -> Ollama function-calling schema."""
    return [{"type": "function",
             "function": {"name": t["name"],
                          "description": t.get("description", ""),
                          "parameters": t.get("input_schema") or {"type": "object"}}}
            for t in tools]


def extract_text(response):
    """Text of an Ollama /api/chat response (the assistant message content)."""
    return (response.get("message", {}) or {}).get("content") or ""


def _payload(model, messages, tools=None):
    p = {"model": model, "stream": False, "think": False, "messages": messages}
    if tools is not None:
        p["tools"] = tools
    return p


def complete(cfg, model, system, messages, tools=None, http=None):
    post = http or _default_http(cfg)
    msgs = [{"role": "system", "content": system}] + list(messages)
    return post(_payload(model, msgs, tools))


def run_tool_loop(cfg, model, system, user_text, tools, exec_tool, http=None, max_iters=6):
    post = http or _default_http(cfg)
    messages = [{"role": "system", "content": system},
                {"role": "user", "content": user_text}]
    tool_calls = []
    for _ in range(max_iters):
        resp = post(_payload(model, messages, tools))
        msg = resp.get("message", {}) or {}
        tcs = msg.get("tool_calls") or []
        if not tcs:
            return {"answer": msg.get("content") or "", "tool_calls": tool_calls}
        messages.append(msg)  # assistant turn, as returned
        for tc in tcs:
            fn = tc.get("function", {}) or {}
            name = fn.get("name", "")
            args = fn.get("arguments") or {}
            out = exec_tool(name, args)
            tool_calls.append({"name": name, "input": args,
                               "result_excerpt": (out or "")[:500]})
            messages.append({"role": "tool", "content": out or "", "tool_name": name})
    # cap hit: one final tools-less call so the model must answer in text
    final = post(_payload(model, messages))
    return {"answer": (final.get("message", {}) or {}).get("content") or "",
            "tool_calls": tool_calls}
