---
title: Ontology Design Patterns
type: article
cluster: agentic-ai
status: active
date: '2026-04-25'
tags:
- ontology
- knowledge-graph
- semantic-web
- rdf
- owl
summary: Ontology design patterns for knowledge graphs — when formal ontologies
  pay off, the patterns (taxonomy, mereology, partonomy, n-ary relations),
  and the lighter-weight alternatives most projects should reach for.
related:
- KnowledgeGraphCompletion
- KnowledgeGraphVsRelationalDatabase
- DatabaseDesign
- AbstractAlgebra
hubs:
- AgenticAi Hub
---
# Ontology Design Patterns

An ontology is a formal description of the kinds of things in a domain and the relationships among them. Ontology design patterns are reusable templates for common modelling situations — taxonomies, parts-of-things, time-stamped facts, n-ary relationships.

Most knowledge-graph projects in 2026 don't use formal ontologies (OWL, RDFS) directly. They use lighter property-graph schemas with informal modelling guidelines. But the patterns from the formal-ontology tradition still inform good schema design.

## When formal ontologies are worth it

Specific cases:

- **Long-lived, broadly-shared knowledge** — biomedical (UMLS, SNOMED), library science (Dublin Core), legal (LKIF). Multi-decade lifespan; cross-organisation use.
- **Strong reasoning requirements** — entailment ("if A is a B and B is a C, then A is a C") computed by an ontology reasoner. Useful in some research and compliance contexts.
- **Cross-organisation interoperability** — linked data, semantic web. Standardised vocabularies enable integration.
- **Regulatory or compliance frameworks** — pharma, financial services where formal definitions matter.

Most enterprise knowledge graphs don't fit these cases. They benefit from ontology-style thinking without ontology-style formalism.

## Common patterns

### Class hierarchy (taxonomy)

The simplest pattern: types organised in an is-a hierarchy.

```
Vehicle
├── Car
│   ├── Sedan
│   ├── SUV
│   └── Hatchback
├── Truck
└── Motorcycle
```

In RDFS / OWL, `rdfs:subClassOf`. In a property graph, multiple labels or a `parent_class` relationship.

Patterns to follow:

- **Single inheritance is simpler**; multiple inheritance is sometimes necessary but creates ambiguity.
- **Don't over-classify**. A 6-level deep tree is harder than 2-3 levels with finer-grained relations.
- **Keep types orthogonal where possible**. A vehicle might be `(:Car {fuel:'electric'})` rather than `:ElectricCar` if "electric" is a property.

### Part-of (mereology)

Modelling parts of things:

```
Engine -[:PART_OF]-> Car
Wheel -[:PART_OF]-> Car
Cylinder -[:PART_OF]-> Engine
```

Subtleties:

- **Composition vs aggregation.** Composition: the part can't exist without the whole (an engine in a specific car). Aggregation: the part is shared (an off-the-shelf bolt).
- **Transitive part-of.** A cylinder is part of an engine which is part of a car; queries for "what's part of this car" should follow transitively. Most graph DBs handle via variable-length traversal.
- **Part-of vs has-property.** "The car has a colour" is property; "the car has wheels" is part-of.

### Membership (taxonomic)

Things belonging to groups, roles, categories:

```
Alice -[:MEMBER_OF]-> EngineeringTeam
Alice -[:HAS_ROLE]-> Manager
Alice -[:WORKS_FOR]-> Anthropic
```

Pattern: use distinct relationship types for distinct membership concepts. Don't overload `MEMBER_OF` to mean both "is in this team" and "has this role."

### Time-indexed facts

Most facts are true at a particular time. The CEO of a company changes; the price of a product changes; a relationship is established and ended.

Three approaches:

#### Reified relationships (common in RDF)

A relationship becomes its own node:

```
(Dario)-[:HOLDS_POSITION]->(position_1)
position_1 :Position {role:'CEO', company:'Anthropic', from:2021}
```

Pros: time, source, confidence all attached cleanly.
Cons: more nodes; queries are more verbose.

#### Edge properties (property graphs)

Properties on the edge:

```
(Dario)-[:CEO_OF {since:2021, until:NULL, source:'web'}]->(Anthropic)
```

Pros: less verbose; queries simpler.
Cons: limited support for time-querying patterns.

#### Effective-dated rows (relational)

Each fact gets a separate row with `valid_from` / `valid_until`. See [DatabaseDesign].

For most modern KGs, edge properties suffice. Reified relationships are formally cleaner but heavier.

### N-ary relationships

A relationship that involves more than two things:

```
"In 2021, Anthropic, with Series A funding from Google, founded its San Francisco office."
```

Two entities (Anthropic, Google), a relation (funded), a year (2021), an event (founding office).

Modelling options:

- **A relationship node** that connects all the participants:
  ```
  (event_1) -[:HAPPENED_IN]-> (2021)
  (event_1) -[:INVOLVES]-> (Anthropic)
  (event_1) -[:FUNDED_BY]-> (Google)
  (event_1) -[:ESTABLISHED]-> (SF_office)
  ```
- **Multiple binary relationships with shared metadata** — clutter; harder to query.

Reified n-ary relationships are how RDF/OWL handle this; property graphs increasingly adopt the same pattern.

### Provenance

Where did this fact come from? Critical for any KG that ingests from multiple sources.

Patterns:

- **Provenance on edges**: `since`, `source`, `confidence`, `extraction_method`, `extracted_at`.
- **Provenance graph**: a separate graph layer linking facts to their sources, witnesses, derivation chains.

For agentic / RAG use cases, provenance is non-negotiable. Without it, you can't tell "the model said this from training data" from "the KG said this from a verified source."

### Identity and equivalence

Same entity in different sources, or the same entity referred to differently:

- **`owl:sameAs`** in RDF: declares two URIs refer to the same thing.
- **Entity resolution table** in property graphs: maps source-specific IDs to canonical IDs.

This is also where the "open-world" vs "closed-world" assumption matters. Open world: absence of a fact doesn't mean it's false. Closed world: everything I haven't said is false. KGs typically operate open-world; SQL databases closed-world. Mismatching produces bugs.

## Lightweight alternative patterns

For most teams in 2026 building a KG, the formal-ontology toolkit (OWL, RDF, SPARQL) is overkill. Lighter alternatives:

### Schema as documentation

Document your KG's vocabulary in a wiki or schema-as-code (a Markdown file, a YAML schema, dbt docs).

```yaml
node_types:
  Person:
    description: A natural person
    properties: [name, email, birth_year]
  Company:
    description: A legal entity
    properties: [name, founded_year, headquarters]
edge_types:
  WORKS_AT:
    description: Employment
    source: Person
    target: Company
    properties: [role, start_date, end_date]
```

Enforced by code at ingestion. No reasoner required; constraints are concrete.

### Schema validation

For structured KGs, validate insertions against the schema:

- Allowed node types and properties.
- Allowed edge types and source/target combinations.
- Property type constraints.

Tools: `pydantic` for Python; JSON Schema; custom validators. Reject malformed data at insertion.

### Light formal vocabulary

If you need some formal-ontology benefits without full RDF/OWL:

- Use SKOS (Simple Knowledge Organization System) for taxonomies.
- Adopt schema.org vocabulary for common entity types.
- Use Wikidata IDs as a cross-reference for well-known entities.

This gives interoperability and shared vocabulary without committing to the full semantic-web stack.

## Anti-patterns

- **Over-engineered class hierarchies.** 12 levels of `Thing → Object → ... → SmartphoneCase`. The deeper the hierarchy, the less it helps.
- **Properties masquerading as classes.** "RedCar" as a class instead of "Car with colour=red."
- **No provenance.** Facts pour in from everywhere; no record of where; debugging is impossible.
- **Mixing time-varying and time-invariant facts** without distinguishing. The CEO is time-varying; the founding year isn't.
- **Trying to model the world.** Your domain is bounded; resist the urge to import every related concept.

## Pragmatic recommendations

For a new KG project:

1. **Start with a small, concrete schema.** Five to ten node types; ten to fifteen edge types. Document each.
2. **Use property graphs (not RDF) unless you have a specific reason.** Easier; more tooling.
3. **Adopt time, provenance, and confidence as edge properties.** Standardise from day one.
4. **Reuse vocabulary from existing schemas where applicable.** Schema.org, Wikidata, domain-specific ontologies.
5. **Validate at ingestion.** Schema as code; reject malformed.
6. **Iterate.** The schema will evolve; design for additive change.

You'll have an ontology, just an informal one. That's usually enough.

## Further reading

- [KnowledgeGraphCompletion] — building / extending the graph
- [KnowledgeGraphVsRelationalDatabase] — substrate decision
- [DatabaseDesign] — relational schema discipline carries over
- [AbstractAlgebra] — formal-structure math underlying ontologies
