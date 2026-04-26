---
title: Multimodal Embeddings
type: article
cluster: agentic-ai
status: active
date: '2026-04-25'
tags:
- multimodal
- embeddings
- clip
- vision-language
- cross-modal-retrieval
summary: Embedding models that share a vector space across modalities (text,
  image, audio) — CLIP, ImageBind, JinaCLIP, Cohere multimodal — and the
  retrieval / classification patterns they enable.
related:
- EmbeddingsVectorDB
- VectorDatabases
- AiPoweredSearch
- MultiModalAiApplications
- HybridRetrieval
hubs:
- AgenticAi Hub
---
# Multimodal Embeddings

A multimodal embedding model maps inputs from multiple modalities — text, image, audio, video — into a single vector space. A picture of a dog and the text "a dog" land at nearby vectors. This shared space is what makes "search images by text query" and "classify with zero training examples" trivial.

CLIP (OpenAI, 2021) was the first one to land. Successors (ImageBind, JinaCLIP, Cohere multimodal, recent open variants) have expanded the modality coverage and accuracy. By 2026, multimodal embedding is a routine production capability.

## What they enable

The shared embedding space lets you do:

- **Text-to-image retrieval.** Embed text query; embed image gallery; nearest-neighbour search. "Find images matching this description."
- **Image-to-text retrieval.** Reverse direction. Caption suggestion, alt-text generation.
- **Zero-shot classification.** Embed candidate labels as text; embed image; nearest label is the prediction. No labelled training data needed.
- **Cross-modal clustering.** Group documents that include text and images.
- **Vision-language pretraining.** Foundation for downstream vision-language models (LLaVA, BLIP, etc.).

## CLIP, briefly

CLIP (Contrastive Language-Image Pretraining):

- Two encoders: a text encoder (transformer) and an image encoder (ViT or ResNet).
- Trained contrastively: image-caption pairs are positive, mismatched are negative.
- Output: 512-dim vectors (varies by model size).

Released in 2021; public weights; the foundation of much subsequent work.

Variants:

- **OpenAI CLIP** — ViT-B/32, ViT-L/14. Original.
- **OpenCLIP** — community reimplementation; trained on LAION-5B; multiple sizes.
- **EVA-CLIP** — improved scaling; stronger benchmark results.
- **SigLIP** (Google) — sigmoid loss instead of softmax; more efficient training; competitive performance.

For most use cases, OpenCLIP ViT-L/14 or SigLIP-large are strong open-weight defaults.

## Beyond CLIP: bigger and broader

### ImageBind (Meta, 2023)

Joint embedding across six modalities: image, text, audio, depth, thermal, IMU. Trained with image as the binding hub.

Practical use: limited; few applications need all six. The pattern (one model spanning multiple modalities) is more important than the specific implementation.

### Cohere Embed v3 / v4 multimodal

Commercial multimodal embeddings. Strong on multilingual; good on document images.

### Jina CLIP / Jina Embeddings v2/v3

Open-weights multilingual multimodal embeddings. Permissive license; good performance; hosted API option.

### LLM-derived embeddings

Recent: embeddings extracted from large multimodal models. The model's intermediate representations serve as embeddings; often higher quality than dedicated embedding models. Cost: bigger model to run.

## Retrieval patterns

### Text → image search

Standard pattern:

```python
text_emb = model.encode_text(query)
image_embs = preindexed_image_embeddings  # ANN-indexed
results = ann_search(image_embs, text_emb, k=10)
```

Build the image index once; query with text. Sub-millisecond retrieval at scale (with HNSW or equivalent).

### Multi-modal RAG

For documents with both text and images, embed each chunk in its appropriate modality but in the shared space. Retrieval returns the right chunks regardless of modality.

```
[
  {"id": 1, "type": "text", "content": "quarterly revenue chart shows..."},
  {"id": 2, "type": "image", "content": [chart.png]},
  {"id": 3, "type": "text", "content": "..."},
]
```

User query "what was Q3 revenue" can retrieve both the text discussion and the image of the chart.

### Hybrid retrieval

Combine multimodal dense retrieval with text BM25 (for exact-string queries on text components). Same RRF pattern as monomodal hybrid retrieval. See [HybridRetrieval].

### Captioning via retrieval

Image → text retrieval against a corpus of human-written captions produces decent zero-shot captions. Caption quality limited by the corpus; doesn't generate new text but retrieves apt existing ones.

For genuine generation, use a vision-language model (LLaVA, GPT-4V, Claude, Gemini).

## Zero-shot classification

The classic CLIP demo:

```python
candidate_labels = ["a cat", "a dog", "a horse", "a bird"]
label_embs = [model.encode_text(l) for l in candidate_labels]
image_emb = model.encode_image(image)
scores = [cosine(image_emb, l) for l in label_embs]
prediction = candidate_labels[argmax(scores)]
```

No training; just label phrasing. For categories with limited labelled data, often outperforms supervised classifiers.

Tips:

- Phrase labels naturally ("a photograph of a cat" beats "cat").
- Use prompt ensembles ("a photo of a {label}", "a picture of a {label}", average the embeddings).
- For unbalanced or specialised domains, fine-tuning the encoder helps.

## Limitations

CLIP-style models inherit specific failure modes:

- **Bias from training data.** Trained on web-scraped image-text pairs; reflects the statistical patterns of the web (over-representation, stereotypes). Audit before deploying for sensitive applications.
- **Short-text bias.** CLIP was trained on captions; long text gets compressed weirdly. SigLIP and recent models are better at longer text.
- **Compositional understanding.** "A cat on top of a dog" might land near "a dog on top of a cat" — relational structure isn't always captured.
- **OCR weakness.** CLIP doesn't read text in images well. Specialised OCR-aware models (DocCLIP, JinaCLIP v3) are better.
- **Out-of-distribution images.** Performance drops on domains not represented in training (medical imaging, satellite, technical diagrams).

For specialised domains, train or fine-tune on domain-specific image-text pairs. Even small fine-tunes (10k pairs) can dramatically improve quality.

## Production stack

- **Inference**: ONNX export of CLIP / SigLIP for CPU; TensorRT or vLLM for GPU.
- **Index**: pgvector (Postgres extension), Qdrant, Milvus, or LanceDB. Use HNSW.
- **Hybrid**: BM25 + dense (Reciprocal Rank Fusion).
- **Caching**: image embedding is expensive (model inference per image); cache by image hash.
- **Pipeline**: ingestion-time embedding (offline batch); query-time embedding (online).

For most use cases, a 1B-image catalog at 512-dim cosine ~= 2 TB of vectors. HNSW serves sub-millisecond at this scale on a modest server.

## When to use a multimodal model vs separate ones

- **Cross-modal retrieval/classification needed?** Multimodal model.
- **Each modality has its own scoring; combine later?** Separate specialised models often win.
- **Long documents with mixed content?** Separate text embedder + image embedder + late fusion sometimes beats joint embedding.

The shared space is powerful but not always the best. For text-only retrieval, a text-specialised model (BGE, e5, Voyage) usually beats CLIP's text encoder.

## A pragmatic baseline

For a new multimodal retrieval / classification feature in 2026:

1. **Use OpenCLIP ViT-L/14 or SigLIP-large** as the embedder (open-weights).
2. **Index in pgvector or Qdrant** with HNSW.
3. **Embed text and images at ingestion**.
4. **Query with text; rank by cosine.**
5. **Add BM25 hybrid** if exact-string matching matters.
6. **Fine-tune** if domain-specific quality matters (a few hundred to thousands of in-domain image-text pairs).

A week to a working baseline; weeks to fine-tune for specialised domains.

## Further reading

- [EmbeddingsVectorDB] — broader embedding context
- [VectorDatabases] — substrate
- [AiPoweredSearch] — broader search context
- [MultiModalAiApplications] — multimodal applications beyond retrieval
- [HybridRetrieval] — fusing dense and sparse
