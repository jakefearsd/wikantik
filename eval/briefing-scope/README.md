# Briefing-scope eval -- `eval/briefing-scope/`

Measures whether `GET /api/briefing` keeps a session-start briefing on the project the caller named. A briefing for
`clusters=roller` should be built from Roller pages (plus pages those cite), not padded with the best-scoring chunks
of unrelated projects. This harness was written *before* the scoping change so the before/after is recorded in
[`BASELINE.md`](BASELINE.md).

Manual, like `eval/agent-grounding`: it is not wired into CI and calls a live wiki. Both endpoints it uses
(`/api/briefing`, `/api/structure/sitemap`) are public and read-only -- no API key. Python 3.9+, stdlib only.

## What it measures

Each case in `cases.json` is one briefing request plus assertions:

| Field | Meaning |
|-------|---------|
| `clusters`, `prompt`, `scope_mode` | the request (`scope_mode` omitted when absent) |
| `extra_params` | extra query parameters appended verbatim (e.g. `{"scope": "strict"}`) |
| `must_include_as_section` | slugs that must appear in `sections[]` |
| `must_include_as_item` | slugs that must appear in `items[]` as *any* entry, included or pointer (a contract may legitimately be budget-trimmed to a pointer) |
| `must_include_as_body` | slugs that must appear in `items[]` with `included == true` (empty in the seed cases) |
| `must_not_include_as_body` | slugs (`fnmatch` globs, e.g. `Incident*`) that fail the case if they appear in `sections[]` **or** as an `items[]` entry with `included == true`. A pointer item (`included == false`, e.g. `origin: elsewhere`) never fails it |
| `may_point_to` | informational only: reported as `pointed_to` when the slug is present as a pointer item |
| `max_sections_per_page` | cap on `sections[]` entries sharing one slug |
| `must_warn` / `must_not_warn` | regexes (case-insensitive, `re.search`) over `warnings[]` |

Two scores per case (reported, not pass/fail):

- **separation** = estimated tokens (chars / 4) of sections and included items whose slug is a member of a requested
  cluster, or has an `items[]` entry with `origin == "cited"`, divided by the same estimate over *all* sections and
  included items. `n/a` when no cluster was requested, the briefing has no body text, or a requested cluster has no
  members in the sitemap (a typo or a stale case; the runner prints `warning: cluster '<name>' has no members in the
  sitemap` on stderr rather than reporting a misleading `0.00`).
- **diversity** = distinct `sections[].slug` / `sections.length`.

Cluster membership comes from **`GET /api/structure/sitemap`** (`data.pages[].{slug,cluster}`), fetched once per run.
That endpoint lists only each page's *primary* cluster, so a page that is a secondary member of a cluster (list-valued
`cluster:`) is not counted as in scope; sub-clusters (`parent/child`) are matched segment-wise. The per-cluster
fallback `GET /api/structure/clusters/{name}` was not needed. `origin` values `cited` / `elsewhere` and the `cluster`
field on items are optional -- the runner works on today's `pin` / `cluster`-only responses.

## Run

```bash
cd eval/briefing-scope
python3 -I run_eval.py --base-url https://wiki.wikantik.com                  # all cases
python3 -I run_eval.py --base-url https://wiki.wikantik.com --case roller-unknown-arg
python3 -I run_eval.py --base-url https://wiki.wikantik.com \
    --write-baseline BASELINE.md --heading "After Phase 0 (YYYY-MM-DD)"      # markdown table + raw counts
```

Exit code: 0 when every case passes, 1 if any fails, 2 if the sitemap cannot be fetched or `--case` matches nothing.
`--write-baseline` overwrites its target file; copy the new section into `BASELINE.md` by hand to keep older runs.

## Tests

```bash
cd eval/briefing-scope
python3 -I -m unittest discover -s .
```

Pure-function tests over canned JSON; no network.

## Caveat

Cases are seeded from the eight observed 2026-10-23 runs, with their original prompts and clusters. `stockroom-itemsold` names the
`stockroom` cluster, which did not exist when the runs were taken; Phase 0 created `StockroomHub` and the cluster, so that case
passes against current prod. `roller-unknown-arg` asserts the `unknown argument: scope` warning, which prod emits only once the
release carrying it is deployed, so it fails against an older deployment.
