"""Tests for bundle_client.py — /api/bundle RAG context-bundle client.

Field names match the live BundleSection JSON:
  slug        (not 'page') — the page slug
  headingPath (camelCase, not 'heading_path')
  text, sections — unchanged from the brief's assumption.

No lexical/mode toggle exists on /api/bundle (--lexical is MCP-arm-only).
"""
import json
import bundle_client


SAMPLE = {"sections": [
    {"slug": "HybridRetrieval", "headingPath": ["Fusion"], "text": "RRF combines BM25 and dense."},
    {"slug": "Citations", "headingPath": ["Staleness"], "text": "Span hashes detect drift."},
]}


def test_sections_to_context_includes_slug_and_text():
    ctx = bundle_client.sections_to_context(SAMPLE["sections"])
    assert "HybridRetrieval" in ctx and "RRF combines" in ctx


def test_fetch_bundle_collects_cited_pages():
    def fake_http(url):
        assert "q=" in url
        return 200, json.dumps(SAMPLE)

    out = bundle_client.fetch_bundle("http://x", "how does fusion work", http=fake_http)
    assert out["cited_pages"] == ["HybridRetrieval", "Citations"]
    assert "Span hashes" in out["context"]


def test_fetch_bundle_deduplicates_cited_pages():
    data = {"sections": [
        {"slug": "PageA", "headingPath": ["Intro"], "text": "First."},
        {"slug": "PageA", "headingPath": ["Detail"], "text": "Second."},
        {"slug": "PageB", "headingPath": [], "text": "Third."},
    ]}

    def fake_http(url):
        return 200, json.dumps(data)

    out = bundle_client.fetch_bundle("http://x", "q", http=fake_http)
    assert out["cited_pages"] == ["PageA", "PageB"]


def test_fetch_bundle_empty_sections():
    def fake_http(url):
        return 200, json.dumps({"sections": []})

    out = bundle_client.fetch_bundle("http://x", "q", http=fake_http)
    assert out["cited_pages"] == []
    assert out["context"] == ""
