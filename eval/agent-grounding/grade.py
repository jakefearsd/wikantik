"""Grade answers: programmatic citation check + blind LLM correctness judge."""
import re
import anthropic_client

_JUDGE_SYS = ("You are a strict grader. Compare a candidate ANSWER to a REFERENCE answer "
              "for the same QUESTION. Score correctness: 2 = fully correct and complete, "
              "1 = partially correct or vague, 0 = wrong, refused, or hallucinated. "
              "Reply with one or two sentences of rationale, then a final line exactly "
              "'SCORE: N' where N is 0, 1, or 2.")
_SCORE_RE = re.compile(r"SCORE:\s*([012])")


def citation_hit(cited_pages, expect_sources):
    want = {s.lower() for s in expect_sources}
    have = {c.lower() for c in cited_pages}
    return bool(want & have)


def judge_one(cfg, question, answer, http=None):
    user = ("QUESTION:\n%s\n\nREFERENCE:\n%s\n\nANSWER:\n%s" %
            (question["question"], question["reference"], answer or "(empty)"))
    resp = anthropic_client.complete(cfg, cfg.judge_model, _JUDGE_SYS,
                                     [{"role": "user", "content": user}], http=http)
    text = anthropic_client.extract_text(resp)
    m = _SCORE_RE.search(text)
    return (int(m.group(1)) if m else 0), text.strip()


def grade_run(cfg, raw, questions_by_id, http=None):
    graded = []
    for r in raw["results"]:
        q = questions_by_id[r["qid"]]
        score, rationale = judge_one(cfg, q, r["answer"], http=http)
        row = dict(r)
        row["correctness"] = score
        row["rationale"] = rationale
        row["citation_hit"] = citation_hit(r.get("cited_pages", []), q["expect_sources"])
        graded.append(row)
    return graded


def main(argv):
    import json, os, sys, config, questions as qm
    if len(argv) < 1:
        sys.exit("usage: grade.py runs/<run-id> [config flags]")
    run_dir = argv[0]
    cfg = config.load_config(argv[1:])
    with open(os.path.join(run_dir, "raw.json")) as f:
        raw = json.load(f)
    qbi = {q["id"]: q for q in qm.load_questions()}
    graded = grade_run(cfg, raw, qbi)
    with open(os.path.join(run_dir, "graded.json"), "w") as f:
        json.dump({"run_id": raw.get("run_id"), "lexical": raw.get("lexical"),
                   "results": graded}, f, indent=2)
    print("wrote", os.path.join(run_dir, "graded.json"))

if __name__ == "__main__":
    import sys
    main(sys.argv[1:])
