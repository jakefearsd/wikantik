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
