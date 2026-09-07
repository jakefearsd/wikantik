# Architecture Decision Records (legacy series)

This directory holds one older ADR: `001-extract-manager-interfaces-to-api.md`,
recording the decision to extract manager interfaces and utility types out of
`wikantik-main` into `wikantik-api` so `wikantik-admin-mcp` / `wikantik-knowledge`
(then a single `wikantik-mcp` module) could compile without the full engine
implementation. It uses a three-digit numbering convention (`001`) that
predates the current series.

The current, active ADR series lives at [`docs/adr/`](../adr/) (singular
directory name) — four-digit-numbered records (`0001`–`0010` so far) covering
RAG-as-a-Service, the Knowledge Graph, the ontology layer, cluster taxonomy,
and the JDBC data-access primitive. Both directories are load-bearing; this
one was never merged into or renumbered onto the other.
