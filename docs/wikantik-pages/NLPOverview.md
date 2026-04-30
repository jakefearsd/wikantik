---
canonical_id: 01KQ0P44SWGE7CTTHH27QVTCRK
title: NLP Overview
type: article
cluster: machine-learning
status: active
date: '2026-04-26'
summary: A survey of natural language processing — the major tasks, the historical
  evolution from rules to statistics to deep learning to LLMs, and the current state
  of practice.
tags:
- nlp
- machine-learning
- text-processing
- transformers
- llm
related:
- DataScienceNLP
- TransformerArchitecture
- SentimentAnalysisWithMachineLearning
hubs:
- MLHub
---
# NLP Overview

Natural language processing is teaching computers to understand and generate human language. The field has gone through dramatic shifts, with the latest (LLMs) reshaping what's possible.

This page surveys the field.

## Historical eras

### Rule-based (1950s-1990s)

Hand-written rules for syntax and semantics. Brittle but interpretable. Worked for limited domains.

### Statistical (1990s-2010s)

Hidden Markov Models, Conditional Random Fields, Support Vector Machines. Required hand-engineered features.

Machine translation, named entity recognition, sentiment analysis worked acceptably.

### Word embeddings (2013+)

word2vec, GloVe — dense vector representations. Captured semantic similarity.

Enabled deep learning to flourish in NLP.

### Sequence models (2014+)

RNNs, LSTMs, GRUs handled sequences. Became standard for many NLP tasks.

### Transformers (2017+)

"Attention is All You Need" introduced transformers. Replaced RNNs by 2019.

Architecture choice for nearly all modern NLP.

### Pretrained transformers (2018+)

BERT, GPT-2 — pretrain on huge text corpora, fine-tune for tasks.

Set new state-of-the-art across NLP tasks.

### Large language models (2020+)

GPT-3, GPT-4, Claude, Gemini. Few-shot and zero-shot capabilities.

Reshaped how NLP problems are approached.

## Major tasks

### Text classification

Assign categories to text:
- Spam detection
- Sentiment analysis
- Topic classification
- Intent recognition

Approaches:
- TF-IDF + logistic regression (still a strong baseline)
- Fine-tuned BERT
- LLM zero-shot

### Named entity recognition (NER)

Find entity mentions: people, places, organizations, dates, etc.

Pretrained models (spaCy, BERT-NER) are strong out of the box.

### Information extraction

Extract structured information from text.

Often combines NER with relation extraction.

LLMs handle this well with prompting.

### Sequence labeling

Per-token labels: part-of-speech, chunking, dependency parsing.

Mostly solved problems.

### Coreference resolution

Determine which expressions refer to the same entity. ("Alice... she...")

Hard; getting better with modern models.

### Machine translation

Translate between languages.

Transformer-based (Google Translate, DeepL) dominates. LLMs are competitive.

### Summarization

Condense long text into shorter form.

Extractive: pick important sentences.
Abstractive: generate new text.

LLMs handle this naturally.

### Question answering

Extractive: find span in document that answers question.
Generative: produce free-form answer.

Modern: retrieval + LLM (RAG).

### Dialogue / conversation

Multi-turn interaction. Once specialized; now general LLM capability.

### Text generation

Produce text from prompt or condition.

LLMs dominate.

### Search and retrieval

Find relevant documents for a query.

Approaches:
- BM25 (classical, still excellent)
- Dense retrieval (sentence-transformers)
- Hybrid (best in practice)
- Cross-encoders for reranking

### Speech (related)

Speech recognition and synthesis are NLP-adjacent. Now end-to-end deep learning.

## Building blocks

### Tokenization

Splitting text into units. Modern: subword (BPE, WordPiece, SentencePiece).

Different tokenizers give different segmentations. Match the tokenizer to the model.

### Embeddings

Dense vectors for tokens, words, sentences, or documents.

Semantic similarity = vector similarity (cosine).

### Attention

Weighted combination of representations. Allows the model to focus on relevant parts.

The breakthrough enabling transformers.

### Pretraining

Train on huge unlabeled text. Predict masked tokens (BERT) or next tokens (GPT).

Captures language patterns; transferable to many tasks.

### Fine-tuning

Adapt pretrained model to a specific task with labeled data.

Less data needed than training from scratch.

## Current landscape

### Open models

- Llama (Meta): foundation models in many sizes
- Mistral / Mixtral: strong open weights
- Qwen, Gemma, Phi: alternative families
- Specialized: code (Codestral), embedding (BGE, E5)

Quality has caught up to closed models for many use cases.

### Closed APIs

- GPT-4, GPT-4o (OpenAI)
- Claude (Anthropic)
- Gemini (Google)

Best raw quality; pay per use; no data privacy guarantees by default.

### Embeddings

- OpenAI ada, text-embedding-3-*
- Cohere Embed
- Open source: BGE, E5, sentence-transformers

For most retrieval, modern open embeddings are competitive.

## Practical patterns

### RAG (Retrieval-Augmented Generation)

Retrieve relevant docs; pass to LLM. Standard for question answering on private data.

### Few-shot prompting

Show examples in the prompt; LLM generalizes. Powerful for prototyping.

### Fine-tuning small model

For high-volume tasks: fine-tune small specialized model rather than calling large API per request. Cheaper, faster.

### Distillation from LLM

Use LLM to label training data; train smaller model.

### Structured output

For information extraction: ask LLM for JSON; parse.

Tools like Outlines, Instructor help reliability.

### Agents

LLMs that can call tools. Useful for complex multi-step workflows.

## Common pitfalls

### Tokenization mismatches

Subtle bugs from different tokenizers in training vs inference.

### Hallucinations

LLMs generate plausible falsehoods. Always verify factual claims.

### Prompt injection

User input that overrides instructions. Major security concern for production.

### Distribution shift

Models trained on general English may fail on specialized text (medical, legal, code).

### Underestimating data work

NLP success often depends on data quality more than model choice.

### Overusing LLMs

LLMs are expensive. For high-volume simple tasks, smaller models work.

## Evaluation

Metrics depend on task:
- **Classification**: accuracy, F1
- **Generation**: BLEU, ROUGE, METEOR (imperfect)
- **Retrieval**: NDCG, recall@k
- **End-to-end**: human evaluation

Don't trust automated generation metrics. Always sample outputs and read.

## Where NLP is going

- Multimodal models (text + image + audio)
- Longer context (millions of tokens)
- Better calibration and reduced hallucinations
- Better small models
- Domain specialization
- Tool use / agents

## Further Reading

- [DataScienceNLP](DataScienceNLP) — Practical NLP for data science
- [TransformerArchitecture](TransformerArchitecture) — Modern foundation
- [SentimentAnalysisWithMachineLearning](SentimentAnalysisWithMachineLearning) — A specific task
- [ML Hub](MLHub) — Cluster index
