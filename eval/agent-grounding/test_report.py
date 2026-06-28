import report

GRADED = [
    {"arm": "cold", "qid": "q1", "correctness": 0, "citation_hit": False, "tool_calls": [], "rationale": "wrong"},
    {"arm": "grounded_mcp", "qid": "q1", "correctness": 2, "citation_hit": True,
     "tool_calls": [{"name": "assemble_bundle", "input": {}, "result_excerpt": "x"}], "rationale": "ok"},
    {"arm": "grounded_bundle", "qid": "q1", "correctness": 2, "citation_hit": True, "tool_calls": [], "rationale": "ok"},
]

def test_summarize_means():
    s = report.summarize(GRADED)
    assert s["cold"]["mean_correctness"] == 0.0
    assert s["grounded_mcp"]["mean_correctness"] == 2.0
    assert s["grounded_mcp"]["citation_rate"] == 1.0

def test_scorecard_mentions_delta():
    s = report.summarize(GRADED)
    md = report.scorecard_md(s, GRADED, {"run_id": "T", "lexical": False})
    assert "cold" in md and "grounded_mcp" in md and "T" in md
    assert "delta" in md

def test_findings_reports_tool_usage():
    md = report.findings_md(GRADED)
    assert "assemble_bundle" in md

def test_findings_loop_detection():
    """A grounded_mcp record with a tool called 3+ times must appear in the loops section."""
    graded = [
        {"arm": "grounded_mcp", "qid": "q_loop", "correctness": 1, "citation_hit": False,
         "tool_calls": [
             {"name": "retrieve_context", "input": {}, "result_excerpt": "a"},
             {"name": "retrieve_context", "input": {}, "result_excerpt": "b"},
             {"name": "retrieve_context", "input": {}, "result_excerpt": "c"},
         ], "rationale": "ok"},
    ]
    md = report.findings_md(graded)
    assert "q_loop" in md
    assert "retrieve_context" in md
    assert "×3" in md

def test_findings_hallucinated_flag():
    """A record whose rationale contains 'hallucinated' must appear in the vague/hallucinated section."""
    graded = [
        {"arm": "cold", "qid": "q_bad", "correctness": 0, "citation_hit": False,
         "tool_calls": [], "rationale": "Answer was hallucinated and not grounded."},
        {"arm": "grounded_mcp", "qid": "q_ok", "correctness": 2, "citation_hit": True,
         "tool_calls": [], "rationale": "correct"},
    ]
    md = report.findings_md(graded)
    assert "q_bad" in md
    assert "hallucinated" in md.lower()
    # the clean record must not appear in the flagged section under the wrong qid
    assert "q_ok" not in md.split("Judge flagged")[1]
