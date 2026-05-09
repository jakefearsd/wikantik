---
cluster: wikantik-development
canonical_id: 01KQ0P44V3CF88SP9QA9VGC4W7
title: GenAI Tools Architecture and Pipeline
type: article
tags:
- python
- ollama
- rag
- content-extraction
- architecture
summary: Documentation for the GenAI toolset powering Wikantik content generation, detailing the Python architecture, Ollama integration, and the RAG-based content extraction pipeline.
auto-generated: false
date: 2025-01-24
---
# GenAI Tools: Architecture and Content Pipeline

The Wikantik project utilizes a specialized suite of Python-based GenAI tools to automate article drafting, knowledge graph extraction, and cross-reference linking. These tools are designed to run locally, prioritizing privacy and data sovereignty by leveraging **Ollama** for LLM inference.

---

## 1. Python Architecture: Modular and Async

The toolset is organized as a modular Python package (`genaitools`), designed for extensibility and high-throughput batch processing.

### 1.1 Core Components
*   **`LLMClient` (Abstraction Layer):** A unified interface that routes requests to either a local **Ollama** instance or an OpenAI-compatible API (e.g., vLLM or OpenWebUI).
*   **`OllamaClient` (Native Implementation):** Communicates with the Ollama `/api/generate` and `/api/embeddings` endpoints. It includes automatic word counting and token budget management to prevent context window overflows.
*   **`StructuralSpinePageFilter`:** A specialized utility that ensures all generated Markdown files adhere to the Wikantik "Structural Spine" (mandatory YAML frontmatter, canonical IDs, and valid relative links).

### 1.2 Async Concurrency
To maximize GPU utilization, tools like `link_builder.py` and `batch_builder.py` utilize asynchronous execution patterns. While LLM generation is often sequential per topic, the **Embedding generation** and **Web fetching** phases are fully concurrent, significantly reducing the bottleneck of RAG (Retrieval-Augmented Generation) operations.

---

## 2. The Content Extraction Pipeline (Deep Research)

The most advanced feature of the toolset is the `Deep Research` pipeline, which transforms raw web search results into high-signal LLM context.

### 2.1 The RAG Workflow
1.  **Search:** Queries are dispatched via DuckDuckGo to identify relevant authoritative sources.
2.  **Extraction:** For each URL, the tool fetches the raw HTML. It then employs a "Content Stripping" pass using libraries like `BeautifulSoup` or `Trafilatura` to remove:
    *   Navigational menus and footers.
    *   Scripts, styles, and advertisements.
    *   Boilerplate privacy notices.
3.  **Summarization:** Instead of feeding raw text (which wastes context tokens), an LLM generates a focused, 200-400 word summary of the extracted content.
4.  **Context Injection:** These summaries are injected into the final generation prompt as "Grounding Context," ensuring the generated article is rooted in up-to-date, factual information.

### 2.2 Semantic Linking
The `link_builder.py` tool uses this same extraction pipeline to create an internal knowledge web. It computes cosine similarity between the embeddings of the current article and the existing wiki corpus, automatically inserting `[Relative Links](PageName)` for highly correlated concepts.

---

## 3. Ollama Integration and Hardware Optimization

The tools are optimized for local execution on commodity GPU hardware (16GB+ VRAM recommended).

### 3.1 Model Selection
*   **Generation:** Defaults to `qwen3:14b` or `qwen3:32b` for superior reasoning and adherence to complex Markdown schemas.
*   **Embeddings:** Uses `nomic-embed-text` (768 dimensions) for efficient semantic search.

### 3.2 Performance Tuning
*   **`num_gpu`:** Controlled offloading of model layers to the GPU.
*   **`num_ctx`:** Dynamic context window adjustment (typically 16k or 32k) to balance memory usage with document length.
*   **`think` blocks:** Supports Chain-of-Thought (CoT) models, allowing the tool to "reason" through a document outline before generating the actual prose.

---

## 4. Usage Summary

The tools are invoked via a CLI interface:

```bash
# Generate a high-quality article with deep research
python simple_publisher.py -t "Topic" --deep-research -o Topic.md

# Build a massive tutorial from a YAML outline
python document_builder.py -i outline.yaml -o Tutorial.md --smooth

# Run a semantic linking pass across the whole wiki
python link_builder.py --dir ./docs/wikantik-pages --similarity 0.7
```

By combining a clean Python architecture with the local power of Ollama and a robust content extraction pipeline, Wikantik maintains a high bar for "Human-Vetted" quality while scaling content production to match the needs of a modern knowledge base.
