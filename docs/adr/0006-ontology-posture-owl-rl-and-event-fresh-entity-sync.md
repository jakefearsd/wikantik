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

## Implementation status (2026-09-07)

**The reasoning-level decision above is not implemented.** `OntologyModelManager
.buildInferenceSnapshot()` (`wikantik-ontology/.../OntologyModelManager.java:280`) builds its
snapshot with `ModelFactory.createRDFSModel( union )` — plain RDFS, the option this ADR
explicitly rejected. No OWL-RL rule reasoner exists anywhere in the codebase, and
`JenaOntologyQueryService` (the only consumer of `inferenceSnapshot()`) sees RDFS entailments
only.

By this ADR's own argument, that means the `owl:equivalentClass` and `subPropertyOf` axioms
authored in `wikantik.ttl` — the schema.org equivalences, the SKOS mappings, predicate
transitivity — are currently **silently inert** at query time. A `sparql_query` caller asking
for a schema.org type will not match an entity typed only with its `wikantik:` equivalent.

Everything else in this ADR did ship: event-fresh entity sync (`OntologyEntitySync` +
`KgChangeEvent`, nightly rebuild demoted to a reconciliation backstop), lazy SHACL (2 of 21
predicates shaped), and deferral of external `owl:sameAs` reconciliation.

The decision is left standing rather than rewritten — switching the snapshot to a Jena OWL-RL
rule reasoner is a behaviour and performance change that needs its own measurement, not a
documentation edit.
