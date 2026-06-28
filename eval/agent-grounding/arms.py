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
