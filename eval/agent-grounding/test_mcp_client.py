import json
import mcp_client


def test_parse_sse_reads_last_data_event():
    body = "event: message\ndata: {\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"ok\":true}}\n\n"
    assert mcp_client.parse_sse(body) == {"jsonrpc": "2.0", "id": 1, "result": {"ok": True}}


def test_call_tool_extracts_content_text(monkeypatch):
    c = mcp_client.McpClient("http://x", "key")
    c._session_id = "S1"  # pretend connected

    def fake_post(payload, extra_headers=None):
        result = {"content": [{"type": "text", "text": "TOOL OUTPUT"}]}
        body = "data: " + json.dumps({"jsonrpc": "2.0", "id": 1, "result": result}) + "\n\n"
        return 200, {}, body

    monkeypatch.setattr(c, "_post", fake_post)
    assert c.call_tool("search_knowledge", {"q": "x"}) == "TOOL OUTPUT"


def test_connect_captures_session_id(monkeypatch):
    c = mcp_client.McpClient("http://x", "key")
    calls = []

    def fake_post(payload, extra_headers=None):
        calls.append(payload.get("method"))
        if payload.get("method") == "initialize":
            body = "data: " + json.dumps({"jsonrpc": "2.0", "id": 1,
                    "result": {"protocolVersion": "2024-11-05"}}) + "\n\n"
            return 200, {"Mcp-Session-Id": "SESS-9"}, body
        return 202, {}, ""

    monkeypatch.setattr(c, "_post", fake_post)
    c.connect()
    assert c._session_id == "SESS-9"
    assert "initialize" in calls and "notifications/initialized" in calls


def test_list_tools_maps_fields(monkeypatch):
    c = mcp_client.McpClient("http://x", "key")
    c._session_id = "S1"

    def fake_post(payload, extra_headers=None):
        result = {"tools": [{"name": "search_knowledge", "description": "d",
                             "inputSchema": {"type": "object", "properties": {}}}]}
        body = "data: " + json.dumps({"jsonrpc": "2.0", "id": 1, "result": result}) + "\n\n"
        return 200, {}, body

    monkeypatch.setattr(c, "_post", fake_post)
    tools = c.list_tools()
    assert tools[0]["name"] == "search_knowledge"
    assert tools[0]["input_schema"] == {"type": "object", "properties": {}}
