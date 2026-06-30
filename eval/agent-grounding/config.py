"""Configuration for the grounded-agent eval harness. Stdlib only."""
import argparse
import os
import sys
from types import SimpleNamespace

OLLAMA_DEFAULT_MODEL = "gemma4:12b"
ANTHROPIC_DEFAULT_MODEL = "claude-sonnet-4-6"
OLLAMA_DEFAULT_BASE_URL = "http://inference.jakefear.com:11434"


def parse_mcp_key(raw: str) -> str:
    return raw.split(",")[0].strip()


def load_config(argv):
    p = argparse.ArgumentParser(description="Grounded-on-Wikantik agent eval")
    p.add_argument("--provider", choices=["ollama", "anthropic"], default="ollama",
                   help="LLM backend for the agent arms + judge (default: ollama, the local stack)")
    p.add_argument("--base-url", default="https://wiki.wikantik.com")
    p.add_argument("--ollama-base-url", default=OLLAMA_DEFAULT_BASE_URL)
    p.add_argument("--agent-model", default=None,
                   help="override the agent model (defaults per provider)")
    p.add_argument("--judge-model", default=None,
                   help="override the judge model (defaults per provider)")
    p.add_argument("--lexical", action="store_true",
                   help="hint the bundle/retrieval to lexical (BM25) mode for the dense-vs-BM25 comparison")
    p.add_argument("--max-tool-iters", type=int, default=6)
    p.add_argument("--samples", type=int, default=1,
                   help="how many independent runs to collect per (arm, question) before taking median")
    args = p.parse_args(argv)

    default_model = OLLAMA_DEFAULT_MODEL if args.provider == "ollama" else ANTHROPIC_DEFAULT_MODEL
    agent_model = args.agent_model or default_model
    judge_model = args.judge_model or default_model

    anthropic_key = os.environ.get("ANTHROPIC_API_KEY")
    if args.provider == "anthropic" and not anthropic_key:
        sys.exit("ANTHROPIC_API_KEY is not set (required for --provider anthropic)")

    mcp_raw = os.environ.get("MCP_ACCESS_KEYS")
    if not mcp_raw:
        sys.exit("MCP_ACCESS_KEYS is not set (source .env / .env.prod)")

    return SimpleNamespace(
        provider=args.provider,
        base_url=args.base_url.rstrip("/"),
        ollama_base_url=args.ollama_base_url.rstrip("/"),
        anthropic_key=anthropic_key,
        mcp_key=parse_mcp_key(mcp_raw),
        agent_model=agent_model,
        judge_model=judge_model,
        lexical=args.lexical,
        max_tool_iters=args.max_tool_iters,
        samples=args.samples,
    )
