---
canonical_id: 01KQ0P44PAP95CYA8AW9YMWG9S
title: Data Science NLP
type: article
cluster: machine-learning
status: active
date: '2026-04-26'
summary: Practical NLP for data scientists — preprocessing, classical techniques,
  embeddings, transformer-based approaches, and the workflow for going from text
  data to useful model.
tags:
- nlp
- data-science
- machine-learning
- text-processing
- embeddings
related:
- NLPOverview
- SentimentAnalysisWithMachineLearning
- TransformerArchitecture
hubs:
- MLHub
---
# Data Science NLP

Most data science work touches text eventually — customer feedback, support tickets, descriptions, logs. NLP turns text into features or predictions.

This page covers the practical pipeline from raw text to models.

## The text-data pipeline

1. Collect/load text
2. Clean and normalize
3. Tokenize
4. Convert to numerical representation
5. Apply model
6. Evaluate

Each step has choices that affect downstream quality.

## Cleaning

Text data is messy:
- HTML/XML tags
- URLs, email addresses
- Unicode oddities
- Misspellings
- Emojis
- Multiple languages

Decisions:
- Strip or preserve HTML?
- Lowercase or preserve case?
- Remove punctuation or keep?
- Handle emojis as features or noise?

Context-dependent. Sentiment analysis on tweets cares about emojis; legal document analysis doesn't.

## Tokenization

Splitting text into units (tokens).

### Word tokenization

Split on whitespace, handle punctuation. Simple but breaks for languages without word boundaries.

### Subword tokenization

BPE, WordPiece, SentencePiece — break words into subword units.

Used by all modern transformers. Handles rare words and morphology.

### Character tokenization

Each character is a token. Maximum vocabulary efficiency, longer sequences.

Use the tokenizer matching your model. Don't custom-tokenize for pretrained models.

## Stop words and stemming

Classical preprocessing:
- **Stop words**: remove common words ("the", "is")
- **Stemming**: reduce to root form ("running" → "run")
- **Lemmatization**: smarter version using grammar

For modern transformer models: skip these. The model handles them.

For classical models (TF-IDF + logistic regression): may help.

## Numerical representations

### Bag of words

Each document is a vector of word counts. Simple, interpretable, sparse.

### TF-IDF

Word counts weighted by inverse document frequency. Common words get lower weight.

Strong baseline for many text tasks.

### Word embeddings

Dense vectors per word: word2vec, GloVe, fastText.

Words with similar meanings have similar vectors.

Pre-deep-learning innovation; still useful.

### Contextual embeddings

BERT, RoBERTa, etc. Embeddings depend on context.

"Bank" in "river bank" vs "bank account" gets different vectors.

### Sentence/document embeddings

Sentence-transformers, OpenAI ada-002 — turn whole text into single vector.

Useful for similarity, clustering, retrieval.

### Modern approach

For most tasks: use a pretrained transformer's embedding layer. Sentence-transformers for sentence-level work.

## Common NLP tasks

### Classification

Sentiment, topic, intent, spam.

Modern: fine-tune a transformer on labeled data. Or use embeddings + classifier.

### Named entity recognition (NER)

Find names, places, organizations, dates.

Pretrained models (spaCy, BERT-based) work well out of the box.

### Sequence labeling

Part-of-speech tagging, chunking, parsing.

Mostly solved problems with pretrained models.

### Information extraction

Extract structured info from text. Often combines NER + relation extraction.

### Topic modeling

Discover themes in document collections.

LDA (classical), BERTopic (modern with embeddings).

### Summarization

Abstract or extract summaries.

LLMs handle this well.

### Question answering

Extract answers from documents (extractive) or generate answers (generative).

### Search/retrieval

Find relevant documents for a query.

BM25 (classical), dense retrieval (modern), hybrid (best in practice).

## Practical workflow

### Start simple

TF-IDF + logistic regression is a baseline that works surprisingly well. Establish before going complex.

### Use pretrained when possible

Fine-tuning a pretrained model beats training from scratch with limited data.

### Embeddings + classifier

For many tasks: pre-compute embeddings, train a classifier on top.

Cheaper than fine-tuning. Often comparable quality.

### Consider LLM for prototyping

For prototyping: zero-shot or few-shot LLM may answer the question.

For production: usually fine-tune a smaller model for cost.

## Working with multilingual data

- mBERT, XLM-R: multilingual transformers
- Translation as preprocessing
- Language-specific tokenizers

Be wary of "English-trained model on other language" — quality drops.

## Common pitfalls

### Tokenizer mismatch

Using one tokenizer in training and another in inference. Subtle, often silent.

### Ignoring class imbalance

Spam detection has 99% non-spam. Accuracy 99% by predicting "not spam" always.

### Overfitting on small datasets

Aggressive regularization, data augmentation, or smaller models.

### Distribution shift

Training on news; deploying on tweets. Doesn't transfer well.

### Test contamination

Especially for retrieval evaluation: ensure test queries aren't in training data.

### Treating language as solved

NLP works well for English on common domains. Specialized text (medical, legal, code) needs domain adaptation.

## Tools

- **NLTK / spaCy**: classical NLP, fast preprocessing
- **scikit-learn**: TF-IDF, classical classifiers
- **Hugging Face Transformers**: pretrained models, fine-tuning
- **Sentence Transformers**: embeddings
- **gensim**: word2vec, topic modeling
- **OpenAI/Anthropic APIs**: zero/few-shot

## Evaluation

Beyond accuracy: precision, recall, F1.

For text generation: BLEU, ROUGE, METEOR (imperfect), and human evaluation (gold standard).

For retrieval: NDCG, MRR, recall@k.

Don't trust automated metrics blindly. Sample outputs and read them.

## When to use LLMs vs traditional ML

LLMs:
- Few labeled examples
- Complex reasoning required
- Prototyping
- Diverse, open-ended tasks

Traditional ML:
- Lots of labeled data
- Latency-sensitive
- Cost-sensitive at scale
- Simple, focused tasks

Hybrid: use LLMs to label data; train smaller model.

## Further Reading

- [NLPOverview](NLPOverview) — Broader NLP context
- [SentimentAnalysisWithMachineLearning](SentimentAnalysisWithMachineLearning) — Sentiment specifically
- [TransformerArchitecture](TransformerArchitecture) — The model architecture
- [ML Hub](MLHub) — Cluster index
