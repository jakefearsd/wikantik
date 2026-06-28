import questions


def test_load_and_validate_seed():
    qs = questions.load_questions()
    assert len(qs) >= 8
    questions.validate(qs)
    ids = [q["id"] for q in qs]
    assert len(ids) == len(set(ids)), "ids must be unique"
    for q in qs:
        assert q["expect_sources"], "every question needs expect_sources"
        assert q["difficulty"] in {"easy", "medium", "hard"}


def test_validate_rejects_missing_field():
    bad = [{"id": "x", "question": "q", "reference": "r"}]  # no expect_sources
    try:
        questions.validate(bad); assert False
    except ValueError:
        pass
