# RAG-as-a-Service is an in-process module serving in-wiki content under human–machine parity

RAG-as-a-Service ships as a **module inside the wiki** with its own dedicated REST endpoint
and MCP actions — not as a separate deployable service. "Composable" is bought through a
transport-agnostic bundle contract and a `ContentSource` seam (so it *could* be extracted
later), not through premature microservices: the chunking, embeddings, Knowledge Graph,
ontology, and human Knowledge Base all already live in-process over the wiki's database, so
splitting before "good" is even defined would force a rebuild across a network boundary and
threaten the baseline.

New content types (PDFs and documents first) are ingested as **first-class in-wiki content**
rather than into a machine-only index, accepting some data-model complication, so that
**human–machine parity** holds: *anything a machine can ingest, retrieve, or see is
browsable and curatable by a human through the Wikantik UI and MCP surfaces, mutually* —
there is no machine-only ingestion path.

We will extract RAG-as-a-Service into a standalone service only once the bundle is
measurably excellent and a genuinely non-wiki content source demands it.
