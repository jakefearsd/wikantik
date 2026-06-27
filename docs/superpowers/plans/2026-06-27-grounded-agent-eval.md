# Grounded-on-Wikantik Agent Eval — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a reproducible, dependency-free Python harness that measures whether grounding an agent in the Wikantik wiki via the MCP/bundle interface beats a cold model on questions about Wikantik's own internals — and produces an interface-friction backlog.

**Architecture:** Three answer arms per question — COLD (no tools), GROUNDED-MCP (model-driven tool-use loop against `/knowledge-mcp`, harness acts as the MCP client), GROUNDED-BUNDLE (harness injects `/api/bundle` context, no tool-use). An LLM judge grades correctness (0–2) blind to arm; a programmatic check scores citation accuracy. Two markdown reports: a scorecard and an interface-findings list distilled from the logged tool calls.

**Tech Stack:** Python 3 standard library only (`urllib.request`, `json`, `argparse`) — matches the repo's `bin/eval/*.py` convention; no `requests`, no SDK, no `pyyaml`. Anthropic Messages API called over raw HTTP. Tests with `pytest` (precedent: `bin/eval/test_spike_kg_rerank.py`).

## Global Constraints

- Python 3, **standard library only** for runtime code (no third-party imports except `pytest` in test files). Copy the `bin/eval/*.py` stdlib + `urllib.request` style.
- All harness files live under `eval/agent-grounding/`; outputs under `eval/agent-grounding/runs/` (gitignored).
- Read-only against prod (`https://wiki.wikantik.com` default base URL). No deploys, no schema changes, no writes to the wiki.
- Secrets come from the environment only: `ANTHROPIC_API_KEY`; the MCP bearer key parsed from `MCP_ACCESS_KEYS` (first comma-separated token). Never hard-code or echo secret values.
- Anthropic API: header `x-api-key: <key>`, `anthropic-version: 2023-06-01`, `content-type: application/json`; endpoint `POST https://api.anthropic.com/v1/messages`. Default model `claude-sonnet-4-6` (agent and judge), overridable by flag.
- `/knowledge-mcp` MCP flow (per CLAUDE.md "Testing SPARQL via Knowledge MCP"): `initialize` → capture `Mcp-Session-Id` response header → `notifications/initialized` → `tools/call`, every call carrying the `Mcp-Session-Id` header; responses are usually `text/event-stream` (parse `data:` lines, JSON-decode, read `result.content[0].text`).
- SPARQL/KG namespace and tool names are discovered at runtime via `tools/list`; do not hard-code tool schemas.
- TDD: failing test first, minimal impl, passing test, commit. DRY, YAGNI.

---

## File Structure

```
eval/agent-grounding/
  README.md            # what it measures, prereqs, how to run
  config.py            # env + CLI config; secret parsing; validation
  mcp_client.py        # /knowledge-mcp HTTP client (session, tools/list, tools/call, SSE parse)
  bundle_client.py     # GET /api/bundle?q= -> sections list
  anthropic_client.py  # raw Anthropic Messages API; single call + tool-use loop
  questions.json       # ~16-18 authored Wikantik-internals questions + ground truth
  questions.py         # load + validate questions.json
  arms.py              # cold(), grounded_bundle(), grounded_mcp() -> AnswerResult dict
  run_eval.py          # orchestrate questions x arms -> runs/<ts>/raw.json
  grade.py             # citation check + LLM judge -> runs/<ts>/graded.json
  report.py            # graded.json -> scorecard.md + interface-findings.md
  test_mcp_client.py
  test_bundle_client.py
  test_anthropic_client.py
  test_questions.py
  test_arms.py
  test_grade.py
  test_report.py
  runs/                # gitignored outputs
```

Shared data contracts (used across tasks):

- **AnswerResult** (dict): `{"arm": str, "qid": str, "answer": str, "tool_calls": [ToolCall], "cited_pages": [str], "error": str|None}`
- **ToolCall** (dict): `{"name": str, "input": dict, "result_excerpt": str}`
- **Question** (dict): `{"id": str, "question": str, "reference": str, "expect_sources": [str], "difficulty": str}`
- **GradedResult** (dict): AnswerResult + `{"correctness": 0|1|2, "rationale": str, "citation_hit": bool}`

---

## Task 1: Scaffold + config

**Files:**
- Create: `eval/agent-grounding/config.py`
- Create: `eval/agent-grounding/.gitignore` (one line: `runs/`)
- Create: `eval/agent-grounding/README.md` (skeleton; expanded in Task 10)
- Test: `eval/agent-grounding/test_config.py`

**Interfaces:**
- Produces: `load_config(argv: list[str]) -> Config` where `Config` is a `dataclass`-free dict-like with attributes `base_url, anthropic_key, mcp_key, agent_model, judge_model, lexical, max_tool_iters`. Also `parse_mcp_key(raw: str) -> str` (first comma-separated token, stripped).

- [ ] **Step 1: Write the failing test**

```python
# eval/agent-grounding/test_config.py
import config

def test_parse_mcp_key_takes_first_token():
    assert config.parse_mcp_key("abc123, def456 ") == "abc123"

def test_parse_mcp_key_single():
    assert config.parse_mcp_key("solo") == "solo"

def test_load_config_defaults(monkeypatch):
    monkeypatch.setenv("ANTHROPIC_API_KEY", "sk-test")
    monkeypatch.setenv("MCP_ACCESS_KEYS", "k1,k2")
    cfg = config.load_config([])
    assert cfg.base_url == "https://wiki.wikantik.com"
    assert cfg.anthropic_key == "sk-test"
    assert cfg.mcp_key == "k1"
    assert cfg.agent_model == "claude-sonnet-4-6"
    assert cfg.lexical is False
    assert cfg.max_tool_iters == 6

def test_load_config_flags(monkeypatch):
    monkeypatch.setenv("ANTHROPIC_API_KEY", "sk-test")
    monkeypatch.setenv("MCP_ACCESS_KEYS", "k1")
    cfg = config.load_config(["--base-url", "http://localhost:8080", "--lexical",
                              "--agent-model", "claude-opus-4-8"])
    assert cfg.base_url == "http://localhost:8080"
    assert cfg.lexical is True
    assert cfg.agent_model == "claude-opus-4-8"

def test_load_config_missing_anthropic_key(monkeypatch):
    monkeypatch.delenv("ANTHROPIC_API_KEY", raising=False)
    monkeypatch.setenv("MCP_ACCESS_KEYS", "k1")
    try:
        config.load_config([])
        assert False, "should have raised"
    except SystemExit:
        pass
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd eval/agent-grounding && python3 -m pytest test_config.py -v`
Expected: FAIL (`ModuleNotFoundError: No module named 'config'`)

- [ ] **Step 3: Write minimal implementation**

```python
# eval/agent-grounding/config.py
"""Configuration for the grounded-agent eval harness. Stdlib only."""
import argparse
import os
import sys
from types import SimpleNamespace


def parse_mcp_key(raw: str) -> str:
    return raw.split(",")[0].strip()


def load_config(argv):
    p = argparse.ArgumentParser(description="Grounded-on-Wikantik agent eval")
    p.add_argument("--base-url", default="https://wiki.wikantik.com")
    p.add_argument("--agent-model", default="claude-sonnet-4-6")
    p.add_argument("--judge-model", default="claude-sonnet-4-6")
    p.add_argument("--lexical", action="store_true",
                   help="hint the bundle/retrieval to lexical (BM25) mode for the dense-vs-BM25 comparison")
    p.add_argument("--max-tool-iters", type=int, default=6)
    args = p.parse_args(argv)

    anthropic_key = os.environ.get("ANTHROPIC_API_KEY")
    if not anthropic_key:
        sys.exit("ANTHROPIC_API_KEY is not set (export it or source .env)")
    mcp_raw = os.environ.get("MCP_ACCESS_KEYS")
    if not mcp_raw:
        sys.exit("MCP_ACCESS_KEYS is not set (source .env / .env.prod)")

    return SimpleNamespace(
        base_url=args.base_url.rstrip("/"),
        anthropic_key=anthropic_key,
        mcp_key=parse_mcp_key(mcp_raw),
        agent_model=args.agent_model,
        judge_model=args.judge_model,
        lexical=args.lexical,
        max_tool_iters=args.max_tool_iters,
    )
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd eval/agent-grounding && python3 -m pytest test_config.py -v`
Expected: PASS (5 passed)

- [ ] **Step 5: Create `.gitignore` and README skeleton**

```bash
printf 'runs/\n' > eval/agent-grounding/.gitignore
printf '# Grounded-on-Wikantik agent eval\n\nSee docs/superpowers/plans/2026-06-27-grounded-agent-eval.md.\nUsage filled in at Task 10.\n' > eval/agent-grounding/README.md
```

- [ ] **Step 6: Commit**

```bash
git add eval/agent-grounding/config.py eval/agent-grounding/test_config.py \
        eval/agent-grounding/.gitignore eval/agent-grounding/README.md
git commit -m "feat(eval): scaffold grounded-agent eval harness config"
```

---

## Task 2: MCP client for /knowledge-mcp

**Files:**
- Create: `eval/agent-grounding/mcp_client.py`
- Test: `eval/agent-grounding/test_mcp_client.py`

**Interfaces:**
- Consumes: nothing (uses `urllib`).
- Produces: `class McpClient(base_url, bearer)` with `connect()` (does initialize + notifications/initialized, stores session id), `list_tools() -> list[dict]` (each `{"name","description","input_schema"}`), `call_tool(name, arguments) -> str` (returns the text payload). Plus module fn `parse_sse(body: str) -> dict` (returns the JSON of the last `data:` event). The HTTP layer is funneled through `self._post(payload, extra_headers) -> (status, headers, body)` so tests can monkeypatch one method.

- [ ] **Step 1: Write the failing test**

```python
# eval/agent-grounding/test_mcp_client.py
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
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd eval/agent-grounding && python3 -m pytest test_mcp_client.py -v`
Expected: FAIL (`ModuleNotFoundError: No module named 'mcp_client'`)

- [ ] **Step 3: Write minimal implementation**

```python
# eval/agent-grounding/mcp_client.py
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
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd eval/agent-grounding && python3 -m pytest test_mcp_client.py -v`
Expected: PASS (4 passed)

- [ ] **Step 5: Commit**

```bash
git add eval/agent-grounding/mcp_client.py eval/agent-grounding/test_mcp_client.py
git commit -m "feat(eval): /knowledge-mcp client (session, tools/list, tools/call, SSE)"
```

---

## Task 3: Bundle client for /api/bundle

**Files:**
- Create: `eval/agent-grounding/bundle_client.py`
- Test: `eval/agent-grounding/test_bundle_client.py`

**Interfaces:**
- Produces: `fetch_bundle(base_url, query, lexical=False, http=None) -> dict` returning `{"context": str, "cited_pages": [str]}`. `http` is an injectable `(url) -> (status, body_str)` for tests; defaults to a urllib-based getter. Section→text flattening fn `sections_to_context(sections: list[dict]) -> str`.

- [ ] **Step 1: Write the failing test**

```python
# eval/agent-grounding/test_bundle_client.py
import json
import bundle_client


SAMPLE = {"sections": [
    {"page": "HybridRetrieval", "heading_path": ["Fusion"], "text": "RRF combines BM25 and dense."},
    {"page": "Citations", "heading_path": ["Staleness"], "text": "Span hashes detect drift."},
]}


def test_sections_to_context_includes_page_and_text():
    ctx = bundle_client.sections_to_context(SAMPLE["sections"])
    assert "HybridRetrieval" in ctx and "RRF combines" in ctx


def test_fetch_bundle_collects_cited_pages():
    def fake_http(url):
        assert "q=" in url
        return 200, json.dumps(SAMPLE)
    out = bundle_client.fetch_bundle("http://x", "how does fusion work", http=fake_http)
    assert out["cited_pages"] == ["HybridRetrieval", "Citations"]
    assert "Span hashes" in out["context"]


def test_fetch_bundle_lexical_flag_in_url():
    seen = {}
    def fake_http(url):
        seen["url"] = url
        return 200, json.dumps({"sections": []})
    bundle_client.fetch_bundle("http://x", "q", lexical=True, http=fake_http)
    assert "mode=lexical" in seen["url"] or "lexical=true" in seen["url"]
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd eval/agent-grounding && python3 -m pytest test_bundle_client.py -v`
Expected: FAIL (`ModuleNotFoundError`)

- [ ] **Step 3: Write minimal implementation**

> NOTE for the implementer: confirm the real `/api/bundle` response field names against `wikantik-api/.../bundle/*` and `bin/eval/spike-api-bundle.py` before finalizing. Adjust `page`/`heading_path`/`text`/`sections` keys here and in the test to match the live JSON; keep the test in lockstep. Confirm whether a lexical toggle exists (`mode=lexical`/`lexical=true`/`debug=rankings`); if none exists, drop the lexical-URL test and document that `--lexical` is a no-op for the bundle arm (still meaningful for the MCP arm if a tool param exists).

```python
# eval/agent-grounding/bundle_client.py
"""Client for GET /api/bundle?q= — the RAG context bundle. Stdlib only."""
import json
import urllib.parse
import urllib.request


def _default_http(url):
    req = urllib.request.Request(url, headers={"Accept": "application/json"})
    with urllib.request.urlopen(req, timeout=120) as r:
        return r.status, r.read().decode()


def sections_to_context(sections):
    blocks = []
    for s in sections:
        heading = " > ".join(s.get("heading_path", []) or [])
        head = s.get("page", "?") + (" — " + heading if heading else "")
        blocks.append("## " + head + "\n" + s.get("text", ""))
    return "\n\n".join(blocks)


def fetch_bundle(base_url, query, lexical=False, http=None):
    http = http or _default_http
    params = {"q": query}
    if lexical:
        params["mode"] = "lexical"
    url = base_url.rstrip("/") + "/api/bundle?" + urllib.parse.urlencode(params)
    status, body = http(url)
    data = json.loads(body) if body else {}
    sections = data.get("sections", [])
    cited = []
    for s in sections:
        p = s.get("page")
        if p and p not in cited:
            cited.append(p)
    return {"context": sections_to_context(sections), "cited_pages": cited}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd eval/agent-grounding && python3 -m pytest test_bundle_client.py -v`
Expected: PASS (3 passed)

- [ ] **Step 5: Commit**

```bash
git add eval/agent-grounding/bundle_client.py eval/agent-grounding/test_bundle_client.py
git commit -m "feat(eval): /api/bundle client"
```

---

## Task 4: Anthropic Messages client + tool-use loop

**Files:**
- Create: `eval/agent-grounding/anthropic_client.py`
- Test: `eval/agent-grounding/test_anthropic_client.py`

**Interfaces:**
- Produces:
  - `complete(cfg, model, system, messages, tools=None, http=None) -> dict` — one Messages API call; returns the parsed response JSON. `http` injectable `(payload_dict) -> response_dict`.
  - `run_tool_loop(cfg, model, system, user_text, tools, exec_tool, http=None, max_iters=6) -> dict` returning `{"answer": str, "tool_calls": [ToolCall]}`. `tools` is the Anthropic-format tool list; `exec_tool(name, input) -> str` runs a tool and returns text. Loops while the model emits `tool_use`, appending `tool_result` blocks, until it returns a final text or `max_iters` is hit.
  - `extract_text(response: dict) -> str` (concatenate `text` blocks).

- [ ] **Step 1: Write the failing test**

```python
# eval/agent-grounding/test_anthropic_client.py
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
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd eval/agent-grounding && python3 -m pytest test_anthropic_client.py -v`
Expected: FAIL (`ModuleNotFoundError`)

- [ ] **Step 3: Write minimal implementation**

```python
# eval/agent-grounding/anthropic_client.py
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


def extract_text(response):
    return "".join(b.get("text", "") for b in response.get("content", [])
                   if b.get("type") == "text")


def complete(cfg, model, system, messages, tools=None, http=None):
    post = http or _default_http(cfg)
    payload = {"model": model, "max_tokens": _MAX_TOKENS, "system": system,
               "messages": messages}
    if tools:
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
    # cap hit: ask once more for a final answer with tools disabled
    final = complete(cfg, model, system,
                     messages + [{"role": "user", "content": "Answer now with what you have."}],
                     tools=None, http=post)
    return {"answer": extract_text(final), "tool_calls": tool_calls}
```

> NOTE: `test_run_tool_loop_caps_iterations` expects exactly `max_iters` tool calls; the loop above performs `max_iters` tool rounds then a final tool-less call. With one tool_use per round that yields 3 tool_calls for `max_iters=3`. Keep the final tool-less call out of `tool_calls`.

- [ ] **Step 4: Run test to verify it passes**

Run: `cd eval/agent-grounding && python3 -m pytest test_anthropic_client.py -v`
Expected: PASS (3 passed)

- [ ] **Step 5: Commit**

```bash
git add eval/agent-grounding/anthropic_client.py eval/agent-grounding/test_anthropic_client.py
git commit -m "feat(eval): Anthropic Messages client + tool-use loop"
```

---

## Task 5: Question set + loader

**Files:**
- Create: `eval/agent-grounding/questions.json`
- Create: `eval/agent-grounding/questions.py`
- Test: `eval/agent-grounding/test_questions.py`

**Interfaces:**
- Produces: `load_questions(path="questions.json") -> list[Question]`; `validate(questions) -> None` (raises `ValueError` on a bad record).

**Authoring criteria for `questions.json` (this IS the deliverable, not a placeholder):** 16–18 questions about Wikantik internals. Each must (a) be answerable from the live wiki corpus, (b) name `expect_sources` that exist as pages in the corpus, and (c) plausibly defeat a COLD model (it cannot know this codebase's specifics). Draw from these design docs (already in the corpus): `HybridRetrieval`, `PageGraphVsKnowledgeGraph`, `KgInclusionPolicy`, the RAG/bundle + citation specs, `OntologyManagement`/the ontology design, `StructuralSpineDesign`, `AgentGradeContentDesign`, the security model. Phrase questions in operator language, not lifted doc sentences. Keep `reference` to 1–3 sentences of the correct answer.

Seed set (write these, then author the rest to 16–18 following the criteria):

```json
[
  {"id": "stale-citation-grading",
   "question": "When a Wikantik page cites another page's section, how does the system later notice that citation has gone stale, and at what granularity does it report staleness?",
   "reference": "Citations are parsed from inline cite:// markup at save into the citations table, version-pinned and span-hashed. A check compares the stored span hash against the current target section and reports graded span-level staleness, surfaced via list_stale_citations and /admin/drift/citations.",
   "expect_sources": ["Citations"], "difficulty": "medium"},

  {"id": "kg-rerank-default",
   "question": "Does Wikantik's hybrid search use the Knowledge Graph to rerank results by default? Why or why not?",
   "reference": "No. The KG graph-rerank is shelved: boost defaults to 0 and it is never wired into production. A 2026-06-16 ceiling experiment measured zero net lift, so production search runs BM25 + dense only.",
   "expect_sources": ["KnowledgeGraphRerank", "HybridRetrieval"], "difficulty": "hard"},

  {"id": "page-vs-knowledge-graph",
   "question": "What is the difference between Wikantik's Page Graph and its Knowledge Graph, and what are the edges in each?",
   "reference": "The Page Graph's edges are real page-to-page wikilinks (plus canonical_id and cluster companions). The Knowledge Graph's nodes are LLM-extracted entities and its edges are co-mention or typed-relation predicates (kg_* tables).",
   "expect_sources": ["PageGraphVsKnowledgeGraph"], "difficulty": "easy"},

  {"id": "derived-page-definition",
   "question": "What makes a page 'derived' in Wikantik, and what is special about its body?",
   "reference": "A page is derived iff its frontmatter has derived_from (the retained source attachment). Its body is machine-owned/regenerable; reflow re-extracts the body while preserving body-independent curation.",
   "expect_sources": ["rag-as-a-service-and-knowledge-base-design"], "difficulty": "medium"},

  {"id": "bundle-vs-synthesis",
   "question": "Does Wikantik's RAG endpoint answer questions, or do something else? What does it return?",
   "reference": "It never synthesizes answers (ADR-0001). /api/bundle and assemble_bundle return a ranked, de-duplicated, version-pinned-cited context bundle of sections; answer synthesis is left to the caller.",
   "expect_sources": ["rag-as-a-service-and-knowledge-base-design"], "difficulty": "medium"},

  {"id": "entity-class-count",
   "question": "How many entity classes can Wikantik's extractor emit, and what governs the list?",
   "reference": "Exactly 9 canonical classes (person, organization, place, event, product, technology, concept, project, version) defined in EntityTypeVocabulary; both extractors are allowlisted to it, defaulting unknowns to concept.",
   "expect_sources": ["OntologyManagement"], "difficulty": "medium"},

  {"id": "read-path-acl",
   "question": "If a page is restricted, can it still show up in an agent's retrieved context or search results?",
   "reference": "No. View ACLs are enforced on the read path including the agent surface — REST read endpoints, the /knowledge-mcp retrieval/KG tools, and /api/bundle all run candidates through a session view gate, so restricted content never enters search, retrieval, or a context bundle.",
   "expect_sources": ["PageOwnership"], "difficulty": "medium"},

  {"id": "canonical-id-rename",
   "question": "What happens to inbound wikilinks when a Wikantik page is renamed?",
   "reference": "Each page has an immutable 26-char canonical_id assigned at first save; references can resolve by canonical_id regardless of renames, and slug history is tracked, so links survive renames.",
   "expect_sources": ["StructuralSpineDesign", "PageGraphVsKnowledgeGraph"], "difficulty": "easy"}
]
```

- [ ] **Step 1: Write the failing test**

```python
# eval/agent-grounding/test_questions.py
import questions

def test_load_and_validate_seed():
    qs = questions.load_questions()
    assert len(qs) >= 8
    questions.validate(qs)
    ids = [q["id"] for q in qs]
    assert len(ids) == len(set(ids)), "ids must be unique"
    for q in qs:
        assert q["expect_sources"], "every question needs expect_sources"
        assert q["difficulty"] in {"easy", "medium", "hard"}

def test_validate_rejects_missing_field():
    bad = [{"id": "x", "question": "q", "reference": "r"}]  # no expect_sources
    try:
        questions.validate(bad); assert False
    except ValueError:
        pass
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd eval/agent-grounding && python3 -m pytest test_questions.py -v`
Expected: FAIL (`ModuleNotFoundError` / file missing)

- [ ] **Step 3: Write `questions.json` (seed set above, then expand to 16–18) and `questions.py`**

```python
# eval/agent-grounding/questions.py
import json
import os

_REQUIRED = ("id", "question", "reference", "expect_sources", "difficulty")

def load_questions(path=None):
    path = path or os.path.join(os.path.dirname(__file__), "questions.json")
    with open(path) as f:
        return json.load(f)

def validate(questions):
    for q in questions:
        for k in _REQUIRED:
            if k not in q or q[k] in (None, "", []):
                raise ValueError("question %r missing %s" % (q.get("id"), k))
        if q["difficulty"] not in {"easy", "medium", "hard"}:
            raise ValueError("bad difficulty for %r" % q["id"])
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd eval/agent-grounding && python3 -m pytest test_questions.py -v`
Expected: PASS (2 passed)

- [ ] **Step 5: Verify every `expect_sources` page exists in the live corpus**

Run (spot-check a couple; the runner also tolerates misses):
```bash
source <(grep -v '^#' .env | sed 's/^/export /') 2>/dev/null
for pg in Citations PageGraphVsKnowledgeGraph KnowledgeGraphRerank OntologyManagement PageOwnership StructuralSpineDesign; do
  code=$(curl -s -o /dev/null -w '%{http_code}' "https://wiki.wikantik.com/wiki/$pg?format=md"); echo "$pg -> $code"; done
```
Expected: 200 for pages that exist. Fix `expect_sources` for any 404 (use the correct page name).

- [ ] **Step 6: Commit**

```bash
git add eval/agent-grounding/questions.json eval/agent-grounding/questions.py \
        eval/agent-grounding/test_questions.py
git commit -m "feat(eval): Wikantik-internals question set + loader"
```

---

## Task 6: The three answer arms

**Files:**
- Create: `eval/agent-grounding/arms.py`
- Test: `eval/agent-grounding/test_arms.py`

**Interfaces:**
- Consumes: `anthropic_client.run_tool_loop`/`complete`, `mcp_client.McpClient`, `bundle_client.fetch_bundle`.
- Produces:
  - `mcp_tools_to_anthropic(tools) -> list[dict]` (map `{name,description,input_schema}` → Anthropic tool dict).
  - `cold(cfg, question, http=None) -> AnswerResult`
  - `grounded_bundle(cfg, question, bundle_fetch=None, http=None) -> AnswerResult`
  - `grounded_mcp(cfg, question, mcp=None, http=None) -> AnswerResult`
  - `cited_pages_from_tool_calls(tool_calls) -> [str]` (scan tool result excerpts + bundle for page names; best-effort).
  - Module constant `SYSTEM` (instruction to answer concisely and cite the wiki pages used as `Sources: [...]`).

- [ ] **Step 1: Write the failing test**

```python
# eval/agent-grounding/test_arms.py
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
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd eval/agent-grounding && python3 -m pytest test_arms.py -v`
Expected: FAIL (`ModuleNotFoundError`)

- [ ] **Step 3: Write minimal implementation**

```python
# eval/agent-grounding/arms.py
"""The three answer arms: cold, grounded_bundle, grounded_mcp."""
import re
import anthropic_client
import bundle_client
import mcp_client as mcp_mod

SYSTEM = ("You are answering questions about the Wikantik wiki engine. Answer "
          "concisely and accurately. When you use information from wiki pages, end "
          "your reply with a line: Sources: [Page1, Page2]. If you do not know, say so.")

_SRC_RE = re.compile(r"Sources:\s*\[([^\]]*)\]")


def mcp_tools_to_anthropic(tools):
    return [{"name": t["name"], "description": t.get("description", ""),
             "input_schema": t.get("input_schema", {"type": "object"})} for t in tools]


def _pages_from_answer(answer):
    m = _SRC_RE.search(answer or "")
    if not m:
        return []
    return [p.strip() for p in m.group(1).split(",") if p.strip()]


def cited_pages_from_tool_calls(tool_calls, extra_text=""):
    found = []
    hay = extra_text + " " + " ".join(tc.get("result_excerpt", "") for tc in tool_calls)
    for word in re.findall(r"[A-Z][A-Za-z0-9]{3,}", hay):
        if word not in found:
            found.append(word)
    return found


def cold(cfg, question, http=None):
    resp = anthropic_client.complete(cfg, cfg.agent_model, SYSTEM,
                                     [{"role": "user", "content": question["question"]}], http=http)
    ans = anthropic_client.extract_text(resp)
    return {"arm": "cold", "qid": question["id"], "answer": ans,
            "tool_calls": [], "cited_pages": _pages_from_answer(ans), "error": None}


def grounded_bundle(cfg, question, bundle_fetch=None, http=None):
    fetch = bundle_fetch or bundle_client.fetch_bundle
    b = fetch(cfg.base_url, question["question"], lexical=cfg.lexical, http=None)
    user = ("Context from the wiki:\n\n" + b["context"] +
            "\n\n---\nUsing ONLY the context above, answer: " + question["question"])
    resp = anthropic_client.complete(cfg, cfg.agent_model, SYSTEM,
                                     [{"role": "user", "content": user}], http=http)
    ans = anthropic_client.extract_text(resp)
    return {"arm": "grounded_bundle", "qid": question["id"], "answer": ans,
            "tool_calls": [], "cited_pages": b["cited_pages"] or _pages_from_answer(ans),
            "error": None}


def grounded_mcp(cfg, question, mcp=None, http=None):
    client = mcp
    if client is None:
        client = mcp_mod.McpClient(cfg.base_url, cfg.mcp_key)
        client.connect()
    tools = mcp_tools_to_anthropic(client.list_tools())
    loop = anthropic_client.run_tool_loop(
        cfg, cfg.agent_model, SYSTEM, question["question"], tools,
        exec_tool=client.call_tool, http=http, max_iters=cfg.max_tool_iters)
    pages = _pages_from_answer(loop["answer"]) or \
        cited_pages_from_tool_calls(loop["tool_calls"])
    return {"arm": "grounded_mcp", "qid": question["id"], "answer": loop["answer"],
            "tool_calls": loop["tool_calls"], "cited_pages": pages, "error": None}
```

> NOTE: `cited_pages_from_tool_calls` is a best-effort CamelCase scan; the authoritative citation signal is the model's `Sources: [...]` line. The scorecard's citation metric (Task 8) uses `cited_pages`, so prefer the `Sources:` line and treat the scan as fallback.

- [ ] **Step 4: Run test to verify it passes**

Run: `cd eval/agent-grounding && python3 -m pytest test_arms.py -v`
Expected: PASS (4 passed)

- [ ] **Step 5: Commit**

```bash
git add eval/agent-grounding/arms.py eval/agent-grounding/test_arms.py
git commit -m "feat(eval): cold / grounded-bundle / grounded-mcp answer arms"
```

---

## Task 7: Orchestrator (run_eval.py)

**Files:**
- Create: `eval/agent-grounding/run_eval.py`
- Test: `eval/agent-grounding/test_run_eval.py`

**Interfaces:**
- Consumes: `config.load_config`, `questions.load_questions`, `arms.*`, one shared connected `McpClient`.
- Produces: `run_all(cfg, questions, arms_impl, mcp) -> list[AnswerResult]` (3 arms × N, each wrapped in try/except so one failure becomes `error` not a crash); `main(argv)` writes `runs/<timestamp>/raw.json`. Timestamp is passed in (no `Date.now()` surprises) via `--run-id` (default: the shell supplies one).

- [ ] **Step 1: Write the failing test**

```python
# eval/agent-grounding/test_run_eval.py
import run_eval
from types import SimpleNamespace

CFG = SimpleNamespace(agent_model="m", base_url="x", mcp_key="k", lexical=False, max_tool_iters=3)
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
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd eval/agent-grounding && python3 -m pytest test_run_eval.py -v`
Expected: FAIL (`ModuleNotFoundError`)

- [ ] **Step 3: Write minimal implementation**

```python
# eval/agent-grounding/run_eval.py
"""Run every question through all three arms; write raw results. Stdlib only."""
import json
import os
import sys
import config
import questions as questions_mod
import arms as arms_mod
import mcp_client


def _safe(fn, arm, qid):
    try:
        return fn()
    except Exception as e:  # isolate one failure
        return {"arm": arm, "qid": qid, "answer": "", "tool_calls": [],
                "cited_pages": [], "error": "%s: %s" % (type(e).__name__, e)}


def run_all(cfg, qs, arms_impl, mcp):
    rows = []
    for q in qs:
        rows.append(_safe(lambda: arms_impl.cold(cfg, q), "cold", q["id"]))
        rows.append(_safe(lambda: arms_impl.grounded_bundle(cfg, q), "grounded_bundle", q["id"]))
        rows.append(_safe(lambda: arms_impl.grounded_mcp(cfg, q, mcp=mcp), "grounded_mcp", q["id"]))
    return rows


def main(argv):
    run_id = None
    if "--run-id" in argv:
        i = argv.index("--run-id"); run_id = argv[i + 1]; argv = argv[:i] + argv[i + 2:]
    if not run_id:
        sys.exit("pass --run-id <timestamp> (e.g. --run-id $(date -u +%Y%m%dT%H%M%SZ))")
    cfg = config.load_config(argv)
    qs = questions_mod.load_questions()
    questions_mod.validate(qs)
    mcp = mcp_client.McpClient(cfg.base_url, cfg.mcp_key)
    mcp.connect()
    rows = run_all(cfg, qs, arms_mod, mcp)
    out_dir = os.path.join(os.path.dirname(__file__), "runs", run_id)
    os.makedirs(out_dir, exist_ok=True)
    with open(os.path.join(out_dir, "raw.json"), "w") as f:
        json.dump({"run_id": run_id, "lexical": cfg.lexical, "model": cfg.agent_model,
                   "results": rows}, f, indent=2)
    print("wrote", os.path.join(out_dir, "raw.json"), "(%d rows)" % len(rows))


if __name__ == "__main__":
    main(sys.argv[1:])
```

> NOTE: `arms_mod` is passed as `arms_impl`; its functions are module-level, so `arms_impl.cold(cfg, q)` works for both the real module and the test stub.

- [ ] **Step 4: Run test to verify it passes**

Run: `cd eval/agent-grounding && python3 -m pytest test_run_eval.py -v`
Expected: PASS (1 passed)

- [ ] **Step 5: Commit**

```bash
git add eval/agent-grounding/run_eval.py eval/agent-grounding/test_run_eval.py
git commit -m "feat(eval): orchestrator across questions x arms with error isolation"
```

---

## Task 8: Grader (grade.py)

**Files:**
- Create: `eval/agent-grounding/grade.py`
- Test: `eval/agent-grounding/test_grade.py`

**Interfaces:**
- Consumes: `anthropic_client.complete`, the question set (for `reference`/`expect_sources`).
- Produces:
  - `citation_hit(cited_pages, expect_sources) -> bool` (case-insensitive intersection).
  - `judge_one(cfg, question, answer, http=None) -> (int, str)` — returns `(correctness 0|1|2, rationale)`; prompts the judge with the question + reference + answer, blind to arm; parses a strict `SCORE: N` line.
  - `grade_run(cfg, raw, questions_by_id, http=None) -> list[GradedResult]`.

- [ ] **Step 1: Write the failing test**

```python
# eval/agent-grounding/test_grade.py
import grade
from types import SimpleNamespace

CFG = SimpleNamespace(judge_model="m", anthropic_key="k")

def test_citation_hit_case_insensitive():
    assert grade.citation_hit(["hybridretrieval"], ["HybridRetrieval"]) is True
    assert grade.citation_hit(["Other"], ["HybridRetrieval"]) is False
    assert grade.citation_hit([], ["X"]) is False

def test_judge_one_parses_score(monkeypatch):
    monkeypatch.setattr(grade.anthropic_client, "complete",
        lambda *a, **k: {"content": [{"type": "text",
            "text": "The answer matches the reference.\nSCORE: 2"}]})
    score, rationale = grade.judge_one(CFG, {"question": "q", "reference": "r"}, "ans")
    assert score == 2 and "matches" in rationale

def test_judge_one_defaults_to_zero_on_unparseable(monkeypatch):
    monkeypatch.setattr(grade.anthropic_client, "complete",
        lambda *a, **k: {"content": [{"type": "text", "text": "no score here"}]})
    score, _ = grade.judge_one(CFG, {"question": "q", "reference": "r"}, "ans")
    assert score == 0

def test_grade_run_attaches_fields(monkeypatch):
    monkeypatch.setattr(grade, "judge_one", lambda cfg, q, a, http=None: (2, "ok"))
    raw = {"results": [{"arm": "cold", "qid": "q1", "answer": "a",
                        "tool_calls": [], "cited_pages": ["HybridRetrieval"], "error": None}]}
    qbi = {"q1": {"question": "q", "reference": "r", "expect_sources": ["HybridRetrieval"]}}
    graded = grade.grade_run(CFG, raw, qbi)
    assert graded[0]["correctness"] == 2
    assert graded[0]["citation_hit"] is True
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd eval/agent-grounding && python3 -m pytest test_grade.py -v`
Expected: FAIL (`ModuleNotFoundError`)

- [ ] **Step 3: Write minimal implementation**

```python
# eval/agent-grounding/grade.py
"""Grade answers: programmatic citation check + blind LLM correctness judge."""
import re
import anthropic_client

_JUDGE_SYS = ("You are a strict grader. Compare a candidate ANSWER to a REFERENCE answer "
              "for the same QUESTION. Score correctness: 2 = fully correct and complete, "
              "1 = partially correct or vague, 0 = wrong, refused, or hallucinated. "
              "Reply with one or two sentences of rationale, then a final line exactly "
              "'SCORE: N' where N is 0, 1, or 2.")
_SCORE_RE = re.compile(r"SCORE:\s*([012])")


def citation_hit(cited_pages, expect_sources):
    want = {s.lower() for s in expect_sources}
    have = {c.lower() for c in cited_pages}
    return bool(want & have)


def judge_one(cfg, question, answer, http=None):
    user = ("QUESTION:\n%s\n\nREFERENCE:\n%s\n\nANSWER:\n%s" %
            (question["question"], question["reference"], answer or "(empty)"))
    resp = anthropic_client.complete(cfg, cfg.judge_model, _JUDGE_SYS,
                                     [{"role": "user", "content": user}], http=http)
    text = anthropic_client.extract_text(resp)
    m = _SCORE_RE.search(text)
    return (int(m.group(1)) if m else 0), text.strip()


def grade_run(cfg, raw, questions_by_id, http=None):
    graded = []
    for r in raw["results"]:
        q = questions_by_id[r["qid"]]
        score, rationale = judge_one(cfg, q, r["answer"], http=http)
        row = dict(r)
        row["correctness"] = score
        row["rationale"] = rationale
        row["citation_hit"] = citation_hit(r.get("cited_pages", []), q["expect_sources"])
        graded.append(row)
    return graded
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd eval/agent-grounding && python3 -m pytest test_grade.py -v`
Expected: PASS (4 passed)

- [ ] **Step 5: Add a `grade` CLI entrypoint + commit**

Append to `grade.py`:
```python
def main(argv):
    import json, os, sys, config, questions as qm
    if len(argv) < 1:
        sys.exit("usage: grade.py runs/<run-id> [config flags]")
    run_dir = argv[0]
    cfg = config.load_config(argv[1:])
    with open(os.path.join(run_dir, "raw.json")) as f:
        raw = json.load(f)
    qbi = {q["id"]: q for q in qm.load_questions()}
    graded = grade_run(cfg, raw, qbi)
    with open(os.path.join(run_dir, "graded.json"), "w") as f:
        json.dump({"run_id": raw.get("run_id"), "lexical": raw.get("lexical"),
                   "results": graded}, f, indent=2)
    print("wrote", os.path.join(run_dir, "graded.json"))

if __name__ == "__main__":
    import sys
    main(sys.argv[1:])
```

```bash
git add eval/agent-grounding/grade.py eval/agent-grounding/test_grade.py
git commit -m "feat(eval): citation check + blind LLM correctness judge"
```

---

## Task 9: Reports (report.py)

**Files:**
- Create: `eval/agent-grounding/report.py`
- Test: `eval/agent-grounding/test_report.py`

**Interfaces:**
- Produces:
  - `summarize(graded) -> dict` per-arm: `{arm: {"n", "mean_correctness", "citation_rate"}}`.
  - `scorecard_md(summary, graded, meta) -> str`.
  - `findings_md(graded) -> str` — distills interface friction: questions where `grounded_mcp` underperformed `grounded_bundle` (autonomy hurt), tool-call counts, arms with empty/looping tool use, and any judge rationale flagged "vague/hallucinated".
  - `main(argv)` writes `scorecard.md` + `interface-findings.md` into the run dir.

- [ ] **Step 1: Write the failing test**

```python
# eval/agent-grounding/test_report.py
import report

GRADED = [
    {"arm": "cold", "qid": "q1", "correctness": 0, "citation_hit": False, "tool_calls": [], "rationale": "wrong"},
    {"arm": "grounded_mcp", "qid": "q1", "correctness": 2, "citation_hit": True,
     "tool_calls": [{"name": "assemble_bundle", "input": {}, "result_excerpt": "x"}], "rationale": "ok"},
    {"arm": "grounded_bundle", "qid": "q1", "correctness": 2, "citation_hit": True, "tool_calls": [], "rationale": "ok"},
]

def test_summarize_means():
    s = report.summarize(GRADED)
    assert s["cold"]["mean_correctness"] == 0.0
    assert s["grounded_mcp"]["mean_correctness"] == 2.0
    assert s["grounded_mcp"]["citation_rate"] == 1.0

def test_scorecard_mentions_delta():
    s = report.summarize(GRADED)
    md = report.scorecard_md(s, GRADED, {"run_id": "T", "lexical": False})
    assert "cold" in md and "grounded_mcp" in md and "T" in md

def test_findings_reports_tool_usage():
    md = report.findings_md(GRADED)
    assert "assemble_bundle" in md
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd eval/agent-grounding && python3 -m pytest test_report.py -v`
Expected: FAIL (`ModuleNotFoundError`)

- [ ] **Step 3: Write minimal implementation**

```python
# eval/agent-grounding/report.py
"""Render scorecard.md + interface-findings.md from graded.json. Stdlib only."""
import collections

_ARMS = ["cold", "grounded_bundle", "grounded_mcp"]


def summarize(graded):
    by = collections.defaultdict(list)
    for r in graded:
        by[r["arm"]].append(r)
    out = {}
    for arm, rows in by.items():
        n = len(rows)
        out[arm] = {
            "n": n,
            "mean_correctness": round(sum(r["correctness"] for r in rows) / n, 3) if n else 0.0,
            "citation_rate": round(sum(1 for r in rows if r.get("citation_hit")) / n, 3) if n else 0.0,
        }
    return out


def scorecard_md(summary, graded, meta):
    lines = ["# Grounded-agent eval scorecard", "",
             "Run: `%s` | lexical(BM25-forced): %s | N questions: %d" % (
                 meta.get("run_id"), meta.get("lexical"),
                 len({r["qid"] for r in graded})), "",
             "| Arm | N | Mean correctness (0-2) | Citation hit rate |",
             "|-----|---|------------------------|-------------------|"]
    for arm in _ARMS:
        s = summary.get(arm)
        if s:
            lines.append("| %s | %d | %.3f | %.0f%% |" % (
                arm, s["n"], s["mean_correctness"], s["citation_rate"] * 100))
    cold = summary.get("cold", {}).get("mean_correctness", 0.0)
    for arm in ("grounded_bundle", "grounded_mcp"):
        if arm in summary:
            lines.append("")
            lines.append("**%s − cold delta:** %+.3f" % (arm, summary[arm]["mean_correctness"] - cold))
    lines += ["", "_Small-N directional result; not a statistical claim._", "",
              "## Per-question", "", "| qid | cold | bundle | mcp |", "|---|---|---|---|"]
    by_q = collections.defaultdict(dict)
    for r in graded:
        by_q[r["qid"]][r["arm"]] = r["correctness"]
    for qid in sorted(by_q):
        row = by_q[qid]
        lines.append("| %s | %s | %s | %s |" % (
            qid, row.get("cold", "-"), row.get("grounded_bundle", "-"), row.get("grounded_mcp", "-")))
    return "\n".join(lines) + "\n"


def findings_md(graded):
    lines = ["# Interface-friction findings", "",
             "Distilled from the grounded-mcp tool-call logs and judge rationales.", ""]
    mcp = [r for r in graded if r["arm"] == "grounded_mcp"]
    bundle = {r["qid"]: r for r in graded if r["arm"] == "grounded_bundle"}
    # tool usage histogram
    tool_counts = collections.Counter()
    for r in mcp:
        for tc in r.get("tool_calls", []):
            tool_counts[tc["name"]] += 1
    lines.append("## Tool usage (grounded-mcp)")
    if tool_counts:
        for name, c in tool_counts.most_common():
            lines.append("- `%s`: %d calls" % (name, c))
    else:
        lines.append("- (no tools were called)")
    # autonomy-hurt cases
    lines += ["", "## Where model-driven tool-use underperformed the canned bundle"]
    hurt = [r for r in mcp if r["qid"] in bundle and r["correctness"] < bundle[r["qid"]]["correctness"]]
    if hurt:
        for r in hurt:
            lines.append("- `%s`: mcp=%d < bundle=%d — %s" % (
                r["qid"], r["correctness"], bundle[r["qid"]]["correctness"], r.get("rationale", "")[:160]))
    else:
        lines.append("- none — tool-use autonomy never lost to the canned bundle")
    # no-tool answers
    lines += ["", "## grounded-mcp answers that used NO tools"]
    notool = [r["qid"] for r in mcp if not r.get("tool_calls")]
    lines.append("- " + (", ".join(notool) if notool else "none"))
    return "\n".join(lines) + "\n"


def main(argv):
    import json, os, sys
    if not argv:
        sys.exit("usage: report.py runs/<run-id>")
    run_dir = argv[0]
    with open(os.path.join(run_dir, "graded.json")) as f:
        data = json.load(f)
    graded = data["results"]
    summary = summarize(graded)
    with open(os.path.join(run_dir, "scorecard.md"), "w") as f:
        f.write(scorecard_md(summary, graded, data))
    with open(os.path.join(run_dir, "interface-findings.md"), "w") as f:
        f.write(findings_md(graded))
    print("wrote scorecard.md + interface-findings.md in", run_dir)


if __name__ == "__main__":
    import sys
    main(sys.argv[1:])
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd eval/agent-grounding && python3 -m pytest test_report.py -v`
Expected: PASS (3 passed)

- [ ] **Step 5: Commit**

```bash
git add eval/agent-grounding/report.py eval/agent-grounding/test_report.py
git commit -m "feat(eval): scorecard + interface-findings report generation"
```

---

## Task 10: README, full-suite green, live smoke run

**Files:**
- Modify: `eval/agent-grounding/README.md`

**Interfaces:** none (integration + docs).

- [ ] **Step 1: Run the whole test suite**

Run: `cd eval/agent-grounding && python3 -m pytest -v`
Expected: PASS (all tests across the 7 test files green).

- [ ] **Step 2: Write the README**

Cover: what it measures (3 arms, correctness + citation), prereqs (`ANTHROPIC_API_KEY`, `MCP_ACCESS_KEYS` via `source <(grep -v '^#' .env | sed 's/^/export /')`), the run sequence, and the dense-vs-BM25 comparison. Real commands:

```markdown
## Run

    source <(grep -v '^#' ../../.env | sed 's/^/export /')   # ANTHROPIC_API_KEY, MCP_ACCESS_KEYS
    RUN=$(date -u +%Y%m%dT%H%M%SZ)
    python3 run_eval.py --run-id "$RUN"          # dense (GPU up) — headline
    python3 grade.py runs/$RUN
    python3 report.py runs/$RUN
    # optional BM25 comparison arm:
    python3 run_eval.py --run-id "${RUN}-bm25" --lexical && \
      python3 grade.py runs/${RUN}-bm25 && python3 report.py runs/${RUN}-bm25

Outputs land in `runs/<run-id>/`: `raw.json`, `graded.json`, `scorecard.md`, `interface-findings.md`.
```

- [ ] **Step 3: Live smoke run (GPU is up)**

Run (real API calls; a few cents):
```bash
cd eval/agent-grounding
source <(grep -v '^#' ../../.env | sed 's/^/export /')
RUN=smoke-$(date -u +%Y%m%dT%H%M%SZ)
python3 run_eval.py --run-id "$RUN" && python3 grade.py runs/$RUN && python3 report.py runs/$RUN
cat runs/$RUN/scorecard.md
```
Expected: `scorecard.md` shows three arm rows; grounded arms should out-score cold on the internals questions. If `grounded_mcp` errors (auth/WAF), retry with `--base-url http://<internal-docker1>:8080` from a host that can reach it, or inspect the first raw error.

- [ ] **Step 4: Commit the README (NOT the runs/ outputs — they're gitignored)**

```bash
git add eval/agent-grounding/README.md
git commit -m "docs(eval): grounded-agent eval usage + smoke-verified"
```

---

## Self-Review

**Spec coverage:**
- Three arms (cold / MCP tool-use / bundle-injected) → Tasks 4, 6. ✓
- Local-agent-loop MCP client (harness is the client; logs tool calls) → Tasks 2, 4, 6. ✓
- Question set ~16–18 on Wikantik internals w/ reference + expect_sources → Task 5. ✓
- LLM judge (0–2, blind) + programmatic citation check → Task 8. ✓
- scorecard.md (per-arm + cold→grounded delta + per-question) → Task 9. ✓
- interface-findings.md (tool usage, autonomy-hurt, no-tool cases) → Task 9. ✓
- DENSE vs BM25 via `--lexical` → Tasks 1, 3, 10. ✓ (caveat: lexical toggle for the bundle is verified live in Task 3 Step 3; if absent, documented as MCP-only.)
- Read-only, prod, secrets from env → Global Constraints, Task 1. ✓
- Re-runnable one command; no code change dense↔lexical → Task 10. ✓
- Stdlib-only, matches bin/eval conventions → Global Constraints. ✓

**Placeholder scan:** No "TBD/TODO" in code steps. Two flagged verification points (real `/api/bundle` field names; lexical toggle existence) are explicit Task-3 sub-steps with fallback behavior, not silent gaps. The question set has 8 written seeds + explicit authoring criteria to reach 16–18 (content authoring, not a code placeholder).

**Type consistency:** AnswerResult / GradedResult / Question / ToolCall dict shapes are consistent across arms.py → run_eval.py → grade.py → report.py. `cited_pages` is the citation signal end-to-end. `judge_one` returns `(int, str)` consumed by `grade_run`. `summarize` keys (`mean_correctness`, `citation_rate`, `n`) match `scorecard_md`/`findings_md` reads. ✓
