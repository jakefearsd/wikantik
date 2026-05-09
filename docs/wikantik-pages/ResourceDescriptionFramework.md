---
cluster: agentic-ai
canonical_id: 01KQ0P44VJHYHZGVJV9ECB4MDE
title: Resource Description Framework (RDF)
type: article
tags:
- rdf
- semantic-web
- linked-data
- turtle
- n-triples
status: active
date: 2025-05-15
summary: A technical deep dive into RDF graph models, the Open World Assumption, and N-Triples/Turtle syntax.
auto-generated: false
---

# Resource Description Framework (RDF): Semantic Graph Modeling

RDF is a foundational W3C standard for modeling data as a directed, labeled graph. It is the primary data model for the Semantic Web and Knowledge Graphs.

## 1. The Core Model: Triples

RDF decomposes all information into **Triples** consisting of a Subject, Predicate, and Object $(s, p, o)$.

*   **Subject ($s$):** The resource being described (always an IRI or a Blank Node).
*   **Predicate ($p$):** The relationship or property (always an IRI).
*   **Object ($o$):** The value or related resource (IRI, Blank Node, or Literal).

### 1.1 IRIs vs. Literals
*   **IRI (Internationalized Resource Identifier):** Provides a global, unique identity to nodes and edges.
*   **Literal:** A raw value (string, integer, date). Literals can have a **Datatype** (e.g., `xsd:integer`) or a **Language Tag** (e.g., `"Hello"@en`).

## 2. Theoretical Foundation: Open World Assumption (OWA)

RDF operates under the **Open World Assumption**.
*   **Closed World (SQL):** If a fact is not in the database, it is assumed to be false.
*   **Open World (RDF):** If a fact is not in the database, it is simply *unknown*. Information may exist elsewhere on the web that has not yet been discovered or asserted.

## 3. Syntax and Serialization

While RDF is a conceptual graph, it must be serialized for storage and transmission.

### 3.1 N-Triples (.nt)
The simplest, most verbose format. One triple per line.
```nt
<http://example.org/Alice> <http://schema.org/jobTitle> "Engineer" .
<http://example.org/Alice> <http://schema.org/knows> <http://example.org/Bob> .
```

### 3.2 Turtle (.ttl)
The human-readable standard. Uses prefixes and shorthand.
```turtle
@prefix ex: <http://example.org/> .
@prefix schema: <http://schema.org/> .

ex:Alice 
    schema:jobTitle "Engineer" ;
    schema:knows ex:Bob .
```

## 4. Blank Nodes and Reification

*   **Blank Nodes:** Represent resources without a global IRI. Used for grouping related properties (e.g., an address block) where the identity of the group is only relevant locally.
*   **Reification:** The process of making statements about statements. Since a triple is the smallest unit, saying "John believes (Alice knows Bob)" requires treating the triple $(Alice, knows, Bob)$ as a new resource (the subject of the "believes" predicate).

## 5. Technical Comparison: RDF vs. LPG

| Feature | RDF (Resource Description Framework) | LPG (Labeled Property Graph) |
| :--- | :--- | :--- |
| **Standards** | W3C Standard (Interoperable) | Proprietary / Vendor specific |
| **Model** | Atomic Triples | Nodes/Edges with Properties |
| **Schema** | RDFS/OWL (Semantic Inference) | Usually Schema-less / Implicit |
| **Query** | SPARQL | Cypher / Gremlin |
| **Use Case** | Data Integration / Linked Data | Deep Path Analysis / Fraud |

## 6. Summary

RDF provides a mathematically rigorous way to integrate disparate data sources across the web. By utilizing IRIs for identity and the Open World Assumption for extensibility, it enables the creation of global-scale Knowledge Graphs that can be reasoned over using automated agents.
