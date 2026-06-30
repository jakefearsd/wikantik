"""Raw Anthropic Messages API client + a tool-use loop. Stdlib only."""
import json
import urllib.request

API_URL = "https://api.anthropic.com/v1/messages"
_MAX_TOKENS = 1024


def _default_http(cfg):
    def post(payload):
        req = urllib.request.Request(
            API_URL, data=json.dumps(payload).encode(),
            headers={"x-api-key": cfg.anthropic_key,
                     "anthropic-version": "2023-06-01",
                     "content-type": "application/json"},
            method="POST")
        with urllib.request.urlopen(req, timeout=180) as r:
            return json.loads(r.read().decode())
    return post


def tools_from_mcp(tools):
    """MCP tool defs -> Anthropic tool schema."""
    return [{"name": t["name"], "description": t.get("description", ""),
             "input_schema": t.get("input_schema", {"type": "object"})} for t in tools]


def extract_text(response):
    return "".join(b.get("text", "") for b in response.get("content", [])
                   if b.get("type") == "text")


def complete(cfg, model, system, messages, tools=None, http=None):
    post = http or _default_http(cfg)
    payload = {"model": model, "max_tokens": _MAX_TOKENS, "system": system,
               "messages": messages}
    if tools is not None:
        payload["tools"] = tools
    return post(payload)


def run_tool_loop(cfg, model, system, user_text, tools, exec_tool, http=None, max_iters=6):
    post = http or _default_http(cfg)
    messages = [{"role": "user", "content": user_text}]
    tool_calls = []
    for _ in range(max_iters):
        resp = complete(cfg, model, system, messages, tools=tools, http=post)
        content = resp.get("content", [])
        messages.append({"role": "assistant", "content": content})
        tool_uses = [b for b in content if b.get("type") == "tool_use"]
        if not tool_uses:
            return {"answer": extract_text(resp), "tool_calls": tool_calls}
        results = []
        for tu in tool_uses:
            out = exec_tool(tu["name"], tu.get("input", {}))
            tool_calls.append({"name": tu["name"], "input": tu.get("input", {}),
                               "result_excerpt": (out or "")[:500]})
            results.append({"type": "tool_result", "tool_use_id": tu["id"], "content": out})
        messages.append({"role": "user", "content": results})
    # cap hit: messages already ends with a user (tool_result) turn; pass as-is with tools disabled
    final = complete(cfg, model, system, messages, tools=None, http=post)
    return {"answer": extract_text(final), "tool_calls": tool_calls}
