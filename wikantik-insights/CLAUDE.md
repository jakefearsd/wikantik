# wikantik-insights

Module notes, loaded when working under this directory (moved from the root CLAUDE.md).

- **wikantik-insights** (Content Intelligence): search-visibility facts, opportunity rules, effect measurement (`com.wikantik.insights.*`). Depends on neither `wikantik-api` nor `wikantik-main` — page state arrives through the `PageFacts` port. Surfaces `/admin/insights/{acquisition,backlog,ingest}`. The reference implementation of the Simple Agility feedback loop.
