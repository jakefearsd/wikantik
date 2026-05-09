---
cluster: generative-ai
canonical_id: 01KQ0P44RGS372VM7798R97Z3R
title: Knowledge Extraction From Text
type: article
tags:
- generative-ai
- nlp
- entity-recognition
- relation-extraction
- text-mining
status: active
date: 2025-05-15
summary: Technical analysis of Knowledge Extraction (KE) workflows. Covers NER, Relation Extraction (RE), and event extraction using Transformer models.
auto-generated: false
---

# Knowledge Extraction: From Text to Triples

Knowledge Extraction (KE) is the multi-stage process of transforming unstructured text into structured, machine-readable facts, typically represented as triples: `(Subject, Predicate, Object)`.

## 1. Named Entity Recognition (NER)

The first step is identifying the "entities" (nodes).
*   **Models:** Modern NER uses Transformer encoders (e.g., SpanBERT or RoBERTa-large).
*   **BIO Tagging:** The standard sequence labeling format. `B-PER` (Begin Person), `I-PER` (Inside Person), `O` (Outside).
*   **Entity Linking (EL):** Mapping "Apple" to the correct Wikidata ID (`Q312`) based on context (e.g., fruit vs. company).

## 2. Relation Extraction (RE)

The second step is identifying the "relationship" (edges).
*   **Sentence-Level RE:** Predicting the relation between two entities in a single sentence.
*   **Concrete Example:** From "Elon Musk founded SpaceX," the system extracts `(Elon Musk, founded, SpaceX)`.
*   **Model Approach:** Concatenate the entity embeddings with the sentence embedding and use a classification head (e.g., `softmax`) over a set of known relations (`works_at`, `located_in`, `author_of`).

## 3. Event Extraction

Events are more complex than static relations; they include **triggers** and **arguments**.
*   **Trigger:** The word indicating the event (e.g., "acquired").
*   **Arguments:** The participants (e.g., Buyer, Target, Price, Date).
*   **Schema:** `Acquisition(Buyer: Google, Target: Fitbit, Date: 2019)`.

## 4. LLM-Based Extraction

With Large Language Models, the multi-stage pipeline can be collapsed into a single prompt using **JSON Schema enforcement**.
*   **Prompt Pattern:** *"Extract all company acquisitions from the text. Return a JSON list of objects with keys: buyer, target, year."*
*   **Validation:** Use libraries like `instructor` or `pydantic` to validate that the LLM output conforms to the expected data types before saving to the Knowledge Graph.

---
**See Also:**
- [Knowledge Graph Construction Pipeline](KnowledgeGraphConstructionPipeline) — Assembling the extracted triples.
- [Natural Language Processing](NaturalLanguageProcessing) — The core linguistic toolset.
- [Embeddings In Gen AI](EmbeddingsInGenAI) — Vectorizing extracted entities.
