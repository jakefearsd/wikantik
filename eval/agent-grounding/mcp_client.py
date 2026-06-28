"""Minimal MCP (Streamable HTTP) client for /knowledge-mcp. Stdlib only.

Flow per CLAUDE.md: initialize -> capture Mcp-Session-Id -> notifications/initialized
-> tools/call (every request carries the session id). Responses are text/event-stream.
"""
import json
import urllib.request


def parse_sse(body: str) -> dict:
    last = None
    for line in body.splitlines():
        if line.startswith("data:"):
            last = json.loads(line[len("data:"):].strip())
    if last is None:
        # some servers reply application/json directly
        return json.loads(body) if body.strip() else {}
    return last


class McpClient:
    def __init__(self, base_url, bearer):
        self._url = base_url.rstrip("/") + "/knowledge-mcp"
        self._bearer = bearer
        self._session_id = None
        self._id = 0

    def _next_id(self):
        self._id += 1
        return self._id

    def _post(self, payload, extra_headers=None):
        headers = {
            "Content-Type": "application/json",
            "Accept": "application/json, text/event-stream",
            "Authorization": "Bearer " + self._bearer,
        }
        if self._session_id:
            headers["Mcp-Session-Id"] = self._session_id
        if extra_headers:
            headers.update(extra_headers)
        data = json.dumps(payload).encode()
        req = urllib.request.Request(self._url, data=data, headers=headers, method="POST")
        with urllib.request.urlopen(req, timeout=120) as r:
            return r.status, dict(r.headers), r.read().decode()

    def connect(self):
        init = {"jsonrpc": "2.0", "id": self._next_id(), "method": "initialize",
                "params": {"protocolVersion": "2024-11-05", "capabilities": {},
                           "clientInfo": {"name": "agent-grounding-eval", "version": "1"}}}
        status, headers, body = self._post(init)
        sid = headers.get("Mcp-Session-Id") or headers.get("mcp-session-id")
        if not sid:
            raise RuntimeError("no Mcp-Session-Id returned from initialize: %s" % body[:200])
        self._session_id = sid
        self._post({"jsonrpc": "2.0", "method": "notifications/initialized"})

    def list_tools(self):
        payload = {"jsonrpc": "2.0", "id": self._next_id(), "method": "tools/list"}
        _, _, body = self._post(payload)
        result = parse_sse(body).get("result", {})
        out = []
        for t in result.get("tools", []):
            out.append({"name": t["name"], "description": t.get("description", ""),
                        "input_schema": t.get("inputSchema", {"type": "object"})})
        return out

    def call_tool(self, name, arguments):
        payload = {"jsonrpc": "2.0", "id": self._next_id(), "method": "tools/call",
                   "params": {"name": name, "arguments": arguments}}
        _, _, body = self._post(payload)
        result = parse_sse(body).get("result", {})
        parts = result.get("content", [])
        texts = [p.get("text", "") for p in parts if p.get("type") == "text"]
        return "\n".join(texts) if texts else json.dumps(result)
