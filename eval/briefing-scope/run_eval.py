"""Briefing-scope eval: does GET /api/briefing keep a project's briefing on that project?

For each case in cases.json the runner calls the briefing endpoint with ``format=json`` and checks
(a) hard rules -- pages that must/must not appear as body text, warnings that must/must not fire,
a per-page section cap -- and (b) two scores: *separation* (share of body tokens that belong to the
requested clusters or are cited from them) and *diversity* (distinct pages per section). Manual run
against a live wiki, like eval/agent-grounding. Stdlib only. Exit 0 when every case passes, else 1.
"""
import argparse
import datetime
import fnmatch
import json
import os
import re
import sys
import urllib.error
import urllib.parse
import urllib.request
from dataclasses import dataclass, field

DEFAULT_BASE_URL = "https://wiki.wikantik.com"
CASES_PATH = os.path.join(os.path.dirname(os.path.abspath(__file__)), "cases.json")


@dataclass
class CaseResult:
    id: str
    passed: bool = True
    violations: list = field(default_factory=list)
    separation: float = None   # None = not applicable (no requested clusters / no body text / empty cluster)
    diversity: float = None    # None = no sections
    pointed_to: list = field(default_factory=list)   # may_point_to slugs seen as pointer items
    empty_clusters: list = field(default_factory=list)   # requested clusters with no members in the sitemap
    counts: dict = field(default_factory=dict)
    error: str = None


def est_tokens(text):
    """Rough token estimate: characters / 4."""
    return len(text or "") // 4


def _matches(slug, patterns):
    return any(fnmatch.fnmatchcase(slug, p) for p in patterns)


def body_pieces(briefing):
    """Yield (slug, estimated_tokens) for every section and every included item."""
    for s in briefing.get("sections") or []:
        yield s.get("slug", ""), est_tokens(s.get("text"))
    for i in briefing.get("items") or []:
        if i.get("included"):
            yield i.get("slug", ""), est_tokens(i.get("content"))


def evaluate(case, briefing, members):
    """Score one briefing against one case. ``members`` maps cluster name -> set of member slugs."""
    r = CaseResult(id=case["id"])
    sections = briefing.get("sections") or []
    items = briefing.get("items") or []
    warnings = briefing.get("warnings") or []

    body_slugs = {s for s, _ in body_pieces(briefing)}
    for slug in sorted(body_slugs):
        if _matches(slug, case.get("must_not_include_as_body", [])):
            r.violations.append("forbidden body page present: %s" % slug)
    included_items = {i.get("slug") for i in items if i.get("included")}
    item_slugs = {i.get("slug") for i in items}
    for slug in case.get("must_include_as_section", []):
        if slug not in {s.get("slug") for s in sections}:
            r.violations.append("required section missing: %s" % slug)
    for slug in case.get("must_include_as_item", []):
        if slug not in item_slugs:
            r.violations.append("required item missing: %s" % slug)
    for slug in case.get("must_include_as_body", []):
        if slug not in included_items:
            r.violations.append("required body (included item) missing: %s" % slug)

    pointers = {i.get("slug") for i in items if not i.get("included")}
    r.pointed_to = [s for s in case.get("may_point_to", []) if s in pointers]

    for pattern in case.get("must_warn", []):
        if not any(re.search(pattern, w, re.IGNORECASE) for w in warnings):
            r.violations.append("expected warning not found: /%s/" % pattern)
    for pattern in case.get("must_not_warn", []):
        for w in warnings:
            if re.search(pattern, w, re.IGNORECASE):
                r.violations.append("forbidden warning present: %s" % w)

    per_page = {}
    for s in sections:
        per_page[s.get("slug")] = per_page.get(s.get("slug"), 0) + 1
    cap = case.get("max_sections_per_page")
    if cap is not None:
        for slug, n in sorted(per_page.items()):
            if n > cap:
                r.violations.append("%s has %d sections (cap %d)" % (slug, n, cap))

    in_scope = set()
    for c in case.get("clusters", []):
        in_scope |= members.get(c, set())
    in_scope |= {i.get("slug") for i in items if i.get("origin") == "cited"}
    total = sum(t for _, t in body_pieces(briefing))
    scoped = sum(t for slug, t in body_pieces(briefing) if slug in in_scope)
    r.counts = {"sections": len(sections), "distinct_pages": len(per_page),
                "included_items": len(included_items), "warnings": len(warnings),
                "in_scope_tokens": scoped, "total_tokens": total,
                "used_tokens": briefing.get("usedTokens")}
    r.empty_clusters = [c for c in case.get("clusters", []) if not members.get(c)]
    # A requested cluster with no members is a typo or a stale case: every token would read as out of
    # scope and the score as a silent 0.00, which looks like a leaking briefing. Report n/a instead.
    if case.get("clusters") and total > 0 and not r.empty_clusters:
        r.separation = scoped / total
    if sections:
        r.diversity = len(per_page) / len(sections)

    r.passed = not r.violations
    return r


def members_from_sitemap(sitemap, clusters):
    """Derive cluster -> member slugs from /api/structure/sitemap (data.pages[].{slug,cluster}).

    A page belongs to a requested cluster when its (primary) cluster equals it or is a
    '/'-delimited sub-cluster of it. The sitemap lists only the primary cluster per page.
    """
    pages = (sitemap.get("data") or sitemap).get("pages") or []
    out = {c: set() for c in clusters}
    for p in pages:
        pc = p.get("cluster")
        if not isinstance(pc, str):
            continue
        for c in clusters:
            if pc == c or pc.startswith(c + "/"):
                out[c].add(p.get("slug"))
    return out


def empty_cluster_warnings(cases, members):
    """One warning line per requested cluster that has no members in the sitemap (sorted, de-duplicated)."""
    names = sorted({c for case in cases for c in case.get("clusters", []) if not members.get(c)})
    return ["warning: cluster '%s' has no members in the sitemap" % c for c in names]


def build_url(base_url, case):
    params = []
    if case.get("clusters"):
        params.append(("clusters", ",".join(case["clusters"])))
    params.append(("prompt", case.get("prompt", "")))
    if case.get("scope_mode"):
        params.append(("scope_mode", case["scope_mode"]))
    for k, v in (case.get("extra_params") or {}).items():
        params.append((k, v))
    params.append(("format", "json"))
    return "%s/api/briefing?%s" % (base_url.rstrip("/"), urllib.parse.urlencode(params))


def http_json(url, timeout=60):
    req = urllib.request.Request(url, headers={"Accept": "application/json", "User-Agent": "wikantik-briefing-scope-eval/1.0"})
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        return json.load(resp)


def run_case(base_url, case, members):
    try:
        briefing = http_json(build_url(base_url, case))
    except (urllib.error.URLError, OSError, ValueError) as e:
        r = CaseResult(id=case["id"], passed=False, error="%s: %s" % (type(e).__name__, e))
        r.violations.append("request failed: %s" % r.error)
        return r
    return evaluate(case, briefing, members)


def _fmt(x):
    return "n/a" if x is None else "%.2f" % x


def render_markdown(results, base_url, heading):
    passed = sum(1 for r in results if r.passed)
    lines = ["## %s" % heading, "",
             "Base URL: `%s` -- %d of %d cases passed." % (base_url, passed, len(results)), "",
             "| case | result | separation | diversity | violations |",
             "|------|--------|-----------:|----------:|------------|"]
    for r in results:
        v = "; ".join(r.violations).replace("|", "\\|") or "-"
        lines.append("| `%s` | %s | %s | %s | %s |" % (
            r.id, "PASS" if r.passed else "FAIL", _fmt(r.separation), _fmt(r.diversity), v))
    lines += ["", "Raw per-case counts (token estimate = chars / 4):", "", "```json",
              json.dumps({r.id: r.counts for r in results}, indent=2), "```", ""]
    return "\n".join(lines)


def main(argv):
    ap = argparse.ArgumentParser(description="Briefing-scope eval against /api/briefing.")
    ap.add_argument("--base-url", default=DEFAULT_BASE_URL)
    ap.add_argument("--case", action="append", help="run only this case id (repeatable)")
    ap.add_argument("--cases", default=CASES_PATH, help="path to cases.json")
    ap.add_argument("--write-baseline", metavar="PATH", help="write the markdown results table here")
    ap.add_argument("--heading", default="Briefing-scope run (%s)" % datetime.date.today().isoformat(),
                    help="heading for the --write-baseline section")
    args = ap.parse_args(argv)

    with open(args.cases) as f:
        cases = json.load(f)
    if args.case:
        cases = [c for c in cases if c["id"] in args.case]
        if not cases:
            print("no case matches %s" % args.case, file=sys.stderr)
            return 2
    clusters = sorted({c for case in cases for c in case.get("clusters", [])})
    try:
        sitemap = http_json(args.base_url.rstrip("/") + "/api/structure/sitemap")
    except (urllib.error.URLError, OSError, ValueError) as e:
        print("cannot fetch sitemap: %s: %s" % (type(e).__name__, e), file=sys.stderr)
        return 2
    members = members_from_sitemap(sitemap, clusters)
    for line in empty_cluster_warnings(cases, members):
        print(line, file=sys.stderr)

    results = [run_case(args.base_url, case, members) for case in cases]
    for r in results:
        print("%-4s %-28s sep=%s div=%s %s" % ("PASS" if r.passed else "FAIL", r.id,
              _fmt(r.separation), _fmt(r.diversity), "; ".join(r.violations)))
    if args.write_baseline:
        with open(args.write_baseline, "w") as f:
            f.write(render_markdown(results, args.base_url, args.heading))
        print("wrote", args.write_baseline)
    return 0 if all(r.passed for r in results) else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
