# Ontology posture: OWL-RL reasoning and event-fresh entity sync — advanced, not overkill

The ontology layer is a projection of the Knowledge Graph + Page Graph (ADR-0002), calibrated
"advanced but not overkill" and consistent with linked-data publishing being subordinate to
RAG (the primary customer).

- **Reasoning level: OWL-RL** (Jena rule reasoner, OWL-RL ruleset) — not RDFS-only, not
  OWL-DL. RDFS-only left the `owl:equivalentClass` / `subPropertyOf` axioms already authored
  in `wikantik.ttl` **silently inert**; OWL-RL activates them (schema.org equivalence, SKOS
  mappings, transitivity) and is scalable / materializable. OWL-DL (Pellet/HermiT/Openllet)
  is rejected as overkill — its consistency-checking and classification cost is not justified
  for a browse-and-retrieve workload.
- **Freshness: event-incremental entity sync.** Entity graphs previously reconciled only on
  the 24h nightly rebuild (no KG events existed) — incoherent for a highly dynamic,
  agent-edited base. KG node/edge changes now emit events that incrementally re-project
  entities into the ontology dataset, mirroring the existing `OntologyPageSync` for pages.
  The nightly rebuild remains as a backstop reconciler.
- **SHACL: lazy.** Shapes are completed per-predicate as that predicate is actually curated
  (2 of 21 today), not in a big-bang push.
- **External reconciliation (`owl:sameAs` to Wikidata/DBpedia): deferred** until a concrete
  linked-data consumer exists.
