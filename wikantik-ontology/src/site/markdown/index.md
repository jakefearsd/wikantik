# Wikantik Ontology

RDF/OWL ontology layer built on Apache Jena: the page-class `wikantik:`
T-Box (no entity vocabulary), page-to-RDF projectors for pages and tag/cluster
concepts, and a TDB2-backed `OntologyModelManager` with RDFS `subClassOf`
inference. Depends only on `wikantik-api` and Jena — it has no dependency on
the wiki engine itself.

Runtime wiring (incremental rebuild on save, the nightly reconciliation
scheduler, and the public `/sparql`, `/id/*`, and `/export/*` read surfaces)
lives in `wikantik-main` and `wikantik-rest`; this module owns the model and
the projection logic they call.
