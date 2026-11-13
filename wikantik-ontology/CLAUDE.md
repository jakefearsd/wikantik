# wikantik-ontology

Module notes, loaded when working under this directory (moved from the root CLAUDE.md).

- **wikantik-ontology**: RDF/OWL layer (Apache Jena, TDB2) — `wikantik.ttl` T-Box (`Page` + 5 content classes, SKOS), Page/Concept projectors, event-incremental sync (`OntologyEventListener`) with a nightly rebuild backstop. Public read surface `/sparql`, `/id/*`, `/export/*` materializes only anonymously-viewable pages. `SemanticHeadRenderer` takes JSON-LD `@type` from the ontology (`SemanticHeadOntologyAgreementTest`). **Kept by decision (2026-11-09)** after the KG removal.
