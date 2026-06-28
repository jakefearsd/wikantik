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

def test_findings_reports_tool_usage():
    md = report.findings_md(GRADED)
    assert "assemble_bundle" in md
