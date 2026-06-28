"""Client for GET /api/bundle?q= — the RAG context bundle. Stdlib only.

Field names match the live BundleSection JSON serialisation:
  sections[].slug        — page slug (identifier)
  sections[].headingPath — list[str] heading path (camelCase)
  sections[].text        — section body text

When lexical=True, appends &mode=lexical to the request so the server uses
BM25-only retrieval (A3). When lexical=False (default), mode is omitted and
the server applies its default HYBRID strategy.
"""
import json
import urllib.parse
import urllib.request


def _default_http(url):
    req = urllib.request.Request(url, headers={
        "Accept": "application/json",
        "User-Agent": "curl/7.68.0",
    })
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


def fetch_bundle(base_url, query, lexical=False, http=None):
    """GET /api/bundle?q=<query>[&mode=lexical] and return {"context": str, "cited_pages": [str]}.

    Args:
        base_url: Wiki base URL (e.g. "http://localhost:8080").
        query:    Natural-language retrieval query.
        lexical:  When True, appends mode=lexical so the server uses BM25-only
                  retrieval. When False (default), mode is omitted and the server
                  applies its default HYBRID strategy (dense+BM25 RRF).
        http:     Optional injectable (url) -> (status, body_str) for testing.
    """
    http = http or _default_http
    params = {"q": query}
    if lexical:
        params["mode"] = "lexical"
    url = base_url.rstrip("/") + "/api/bundle?" + urllib.parse.urlencode(params)
    status, body = http(url)
    if not (200 <= status < 300):
        raise RuntimeError("bundle HTTP %d" % status)
    data = json.loads(body) if body else {}
    sections = data.get("sections", [])
    cited = []
    for s in sections:
        slug = s.get("slug")
        if slug and slug not in cited:
            cited.append(slug)
    return {"context": sections_to_context(sections), "cited_pages": cited}
