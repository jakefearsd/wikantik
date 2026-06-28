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
