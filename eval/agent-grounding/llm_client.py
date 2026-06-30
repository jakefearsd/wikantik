"""Selects the LLM backend module for a config. Both modules expose the same surface:
complete / run_tool_loop / extract_text / tools_from_mcp.
"""
import anthropic_client
import ollama_client


def for_cfg(cfg):
    """Return the client module for cfg.provider (default: ollama)."""
    return anthropic_client if getattr(cfg, "provider", "ollama") == "anthropic" else ollama_client
