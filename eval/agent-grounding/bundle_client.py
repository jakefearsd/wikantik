"""Client for GET /api/bundle?q= — the RAG context bundle. Stdlib only.

Field names match the live BundleSection JSON serialisation:
  sections[].slug        — page slug (identifier)
  sections[].headingPath — list[str] heading path (camelCase)
  sections[].text        — section body text

Note: --lexical is a no-op for the bundle arm; /api/bundle has no lexical-toggle
param. The --lexical flag is meaningful only for the MCP tool arm (assemble_bundle).
"""
import json
import urllib.parse
import urllib.request


def _default_http(url):
    req = urllib.request.Request(url, headers={"Accept": "application/json"})
    with urllib.request.urlopen(req, timeout=120) as r:
        return r.status, r.read().decode()


def sections_to_context(sections):
    """Flatten a list of BundleSection dicts into a markdown-formatted context string."""
    blocks = []
    for s in sections:
        heading = " > ".join(s.get("headingPath", []) or [])
        slug = s.get("slug", "?")
        head = slug + (" — " + heading if heading else "")
        blocks.append("## " + head + "\n" + s.get("text", ""))
    return "\n\n".join(blocks)


def fetch_bundle(base_url, query, lexical=False, http=None):  # noqa: ARG001  lexical unused (MCP-arm-only)
    """GET /api/bundle?q=<query> and return {"context": str, "cited_pages": [str]}.

    Args:
        base_url: Wiki base URL (e.g. "http://localhost:8080").
        query:    Natural-language retrieval query.
        lexical:  Accepted but ignored — /api/bundle has no lexical toggle.
                  Pass lexical=True to assemble_bundle MCP tool instead.
        http:     Optional injectable (url) -> (status, body_str) for testing.
    """
    http = http or _default_http
    params = {"q": query}
    url = base_url.rstrip("/") + "/api/bundle?" + urllib.parse.urlencode(params)
    status, body = http(url)
    data = json.loads(body) if body else {}
    sections = data.get("sections", [])
    cited = []
    for s in sections:
        slug = s.get("slug")
        if slug and slug not in cited:
            cited.append(slug)
    return {"context": sections_to_context(sections), "cited_pages": cited}
