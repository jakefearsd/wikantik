import json
import os

_REQUIRED = ("id", "question", "reference", "expect_sources", "difficulty")


def load_questions(path=None):
    path = path or os.path.join(os.path.dirname(__file__), "questions.json")
    with open(path) as f:
        return json.load(f)


def validate(questions):
    for q in questions:
        for k in _REQUIRED:
            if k not in q or q[k] in (None, "", []):
                raise ValueError("question %r missing %s" % (q.get("id"), k))
        if q["difficulty"] not in {"easy", "medium", "hard"}:
            raise ValueError("bad difficulty for %r" % q["id"])
