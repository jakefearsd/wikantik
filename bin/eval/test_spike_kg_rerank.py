#!/usr/bin/env python3
"""Unit tests for spike-kg-rerank.py's pure pieces (no live server).

Run: python3 bin/eval/test_spike_kg_rerank.py   (or pytest)
"""
import importlib.util
import pathlib
import tempfile

_HERE = pathlib.Path(__file__).resolve().parent
_spec = importlib.util.spec_from_file_location("skr", _HERE / "spike-kg-rerank.py")
skr = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(skr)

_CSV = (
    "# eval/bundle-corpus/queries.csv — header comment\n"
    "query_id,query,category,gold_canonical_id,gold_heading_path,notes\n"
    "r01,how does X relate to Y,RELATIONAL,CID1,Overview > Details,multi-hop\n"
    "s01,what is X,SIMILARITY,CID2,Intro,plain\n"
    "r02,what config enables Z,RELATIONAL,CID3,Configuration,multi-hop\n"
)


def _csv():
    f = tempfile.NamedTemporaryFile("w", suffix=".csv", delete=False, encoding="utf-8")
    f.write(_CSV)
    f.close()
    return f.name


def test_load_slice_relational_only():
    # slice = the RELATIONAL category (load_corpus_pairs with a string cat filter)
    rows = skr.load_corpus_pairs(_csv(), "RELATIONAL")
    assert {r["qid"] for r in rows} == {"r01", "r02"}, rows


def test_load_slice_by_ids():
    # slice = explicit query ids (load_corpus_pairs with a collection filter)
    rows = skr.load_corpus_pairs(_csv(), ["r02"])
    assert {r["qid"] for r in rows} == {"r02"}, rows


def test_section_hit_contiguous_sublist():
    # sections carry already-normalised (lowercased) heading paths, as fetch_bundle produces.
    sections = [("CID1", ["overview", "details", "sub"]), ("CID2", ["x"])]
    assert skr.section_hit("CID1", "Details > Sub", sections) is True
    assert skr.section_hit("CID1", "Details > Missing", sections) is False
    assert skr.section_hit("WRONGCID", "Details", sections) is False


if __name__ == "__main__":
    test_load_slice_relational_only()
    test_load_slice_by_ids()
    test_section_hit_contiguous_sublist()
    print("ALL TESTS PASS")
