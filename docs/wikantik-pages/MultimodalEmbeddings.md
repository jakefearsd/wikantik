---
canonical_id: 01KQEKGDDDCQYHZN00W3ZGN3RS
title: Multimodal Embeddings
type: article
cluster: agentic-ai
status: active
date: '2026-05-15'
tags:
- multimodal
- embeddings
- clip
- vision-language
- cross-modal-retrieval
auto-generated: false
summary: Unified vector spaces across text, image, and audio. Technical deep dive
  into CLIP architecture, SigLIP efficiency, and production cross-modal retrieval
  pipelines.
related:
- EmbeddingsVectorDB
- VectorDatabases
- AiPoweredSearch
- MultiModalAiApplications
- HybridRetrieval
hubs:
- AgenticAiHub
---
# Multimodal Embeddings

Multimodal embeddings map inputs from distinct sensors (cameras, microphones, text streams) into a single, shared $d$-dimensional vector space. In this space, the semantic distance between the string `"firewall logs showing exfiltration"` and a screenshot of a Grafana dashboard showing a spike in outbound traffic is minimized.

## 1. Architecture: The Projection Layer

Modern multimodal models (CLIP, SigLIP, ImageBind) consist of independent encoders for each modality, followed by a **projection layer** that aligns their outputs.

### CLIP (Contrastive Language-Image Pretraining)
CLIP uses a dual-encoder architecture. The training objective is to maximize the cosine similarity of $N$ correct pairs in a batch while minimizing the similarity of the $N^2 - N$ incorrect pairs.

$$ \mathcal{L} = \frac{1}{2N} \sum_{i=1}^N \left( \log \frac{\exp(\cos(\mathbf{t}_i, \mathbf{v}_i)/\tau)}{\sum_{j=1}^N \exp(\cos(\mathbf{t}_i, \mathbf{v}_j)/\tau)} + \log \frac{\exp(\cos(\mathbf{t}_i, \mathbf{v}_i)/\tau)}{\sum_{j=1}^N \exp(\cos(\mathbf{t}_j, \mathbf{v}_i)/\tau)} \right) $$

where $\mathbf{t}$ is text, $\mathbf{v}$ is vision, and $\tau$ is a learnable temperature parameter.

### SigLIP (Sigmoid Loss)
SigLIP (Google, 2023) replaces the softmax over the whole batch with a simple pairwise sigmoid loss. This allows for much larger batch sizes and better stability on small GPUs.

## 2. Implementation: Zero-Shot Classification

The primary advantage of multimodal embeddings is classification without retraining.

```python
import torch
from open_clip import create_model_and_transforms, get_tokenizer

model, _, preprocess = create_model_and_transforms('ViT-B-32', pretrained='laion2b_s34b_b79k')
tokenizer = get_tokenizer('ViT-B-32')

def classify_image(image, labels):
    # 1. Preprocess and Encode Image
    image_input = preprocess(image).unsqueeze(0)
    with torch.no_grad():
        image_features = model.encode_image(image_input)
        image_features /= image_features.norm(dim=-1, keepdim=True)

    # 2. Encode Text Labels
    text_inputs = tokenizer(labels)
    with torch.no_grad():
        text_features = model.encode_text(text_inputs)
        text_features /= text_features.norm(dim=-1, keepdim=True)

    # 3. Compute Probabilities
    # Similarity is the dot product (cosine similarity since normalized)
    similarity = (100.0 * image_features @ text_features.T).softmax(dim=-1)
    return {label: prob.item() for label, prob in zip(labels, similarity[0])}

# Usage
labels = ["a network diagram", "a code snippet", "a natural landscape", "a security alert"]
results = classify_image(img, labels)
```

## 3. Production Retrieval Patterns

### Cross-Modal RAG
For technical documentation, retrieval must span text and diagrams. 
*   **Ingestion:** For every image/table in a document, generate a multimodal embedding.
*   **Storage:** Store in `pgvector` or `Qdrant` using HNSW.
*   **Query:** The user asks `"show me the load balancer configuration"`. We embed this text query and search across *both* the text chunks and the image embeddings.

### The "Late Fusion" Trap
Avoid separate text and image search results that are merely concatenated. Use **Reciprocal Rank Fusion (RRF)** to combine the dense multimodal scores with traditional BM25 text scores if the query contains specific identifiers (like UUIDs or filenames).

## 4. Design Trade-offs

| Feature | Low Dimension (256-512) | High Dimension (1024+) |
|---|---|---|
| **Memory** | Efficient (Fits in RAM) | High (Requires SSD-backed index) |
| **Search Speed** | Sub-millisecond | Linear with dimensionality |
| **Nuance** | Coarse (Good for general topics) | High (Can distinguish subtle UI changes) |
| **Model Size** | Mobile-friendly | Data-center required |

## 5. Deployment Checklist
1.  **Normalization:** Always normalize vectors to unit length ($L_2$) before indexing if using Cosine Similarity.
2.  **Quantization:** Use `int8` or Binary Quantization for the vector index to reduce memory footprint by 4x-32x with $<1\%$ recall drop.
3.  **OCR Pre-processing:** Multimodal models like CLIP are notoriously bad at reading small text within images. For screenshots, append OCR-extracted text to the image metadata for hybrid search.

## Further Reading
* [EmbeddingsVectorDB](EmbeddingsVectorDB)
* [VectorDatabases](VectorDatabases)
* [AiPoweredSearch](AiPoweredSearch)
* [HybridRetrieval](HybridRetrieval)
