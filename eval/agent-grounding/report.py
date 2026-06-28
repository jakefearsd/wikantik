"""Render scorecard.md + interface-findings.md from graded.json. Stdlib only."""
import collections
import statistics

_ARMS = ["cold", "grounded_bundle", "grounded_mcp"]


def _reduce_samples(graded):
    """Collapse multiple samples per (arm, qid) to a single row with median correctness.

    Groups rows by (arm, qid). For each group, emits one row — a copy of the first
    row in the group — with ``correctness`` overwritten by the integer median of all
    correctness values in that group. With samples=1 every group has one row and this
    is the identity transformation.
    """
    groups = collections.OrderedDict()
    for r in graded:
        key = (r["arm"], r["qid"])
        groups.setdefault(key, []).append(r)
    result = []
    for rows in groups.values():
        representative = dict(rows[0])
        representative["correctness"] = int(statistics.median(r["correctness"] for r in rows))
        result.append(representative)
    return result


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
    lines += ["", "_Small-N directional result; not a statistical claim._",
              "_Citation-hit rates are per-arm heuristics (bundle = retrieval slugs; mcp/cold = the model's Sources: line) and are NOT directly comparable across arms; the correctness column is the comparable headline. Errored rows score correctness 0._", "",
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
    # repeated tool calls within one answer (possible loops)
    lines += ["", "## Repeated tool calls within one answer (possible loops)"]
    loop_bullets = []
    for r in mcp:
        per_record = collections.Counter(tc["name"] for tc in r.get("tool_calls", []))
        for tool_name, count in per_record.items():
            if count >= 3:
                loop_bullets.append("- %s: `%s` ×%d" % (r["qid"], tool_name, count))
    lines += loop_bullets if loop_bullets else ["- none"]
    # judge flagged vague or hallucinated
    lines += ["", "## Judge flagged vague or hallucinated"]
    flag_bullets = []
    for r in graded:
        rationale = r.get("rationale", "")
        if "vague" in rationale.lower() or "hallucinated" in rationale.lower():
            flag_bullets.append("- %s (%s): %s" % (r["qid"], r["arm"], rationale[:140]))
    lines += flag_bullets if flag_bullets else ["- none"]
    return "\n".join(lines) + "\n"


def main(argv):
    import json, os, sys
    if not argv:
        sys.exit("usage: report.py runs/<run-id>")
    run_dir = argv[0]
    with open(os.path.join(run_dir, "graded.json")) as f:
        data = json.load(f)
    graded = _reduce_samples(data["results"])
    summary = summarize(graded)
    with open(os.path.join(run_dir, "scorecard.md"), "w") as f:
        f.write(scorecard_md(summary, graded, data))
    with open(os.path.join(run_dir, "interface-findings.md"), "w") as f:
        f.write(findings_md(graded))
    print("wrote scorecard.md + interface-findings.md in", run_dir)


if __name__ == "__main__":
    import sys
    main(sys.argv[1:])
