/*
    Licensed to the Apache Software Foundation (ASF) under one
    or more contributor license agreements.  See the NOTICE file
    distributed with this work for additional information
    regarding copyright ownership.  The ASF licenses this file
    to you under the Apache License, Version 2.0 (the
    "License"); you may not use this file except in compliance
    with the License.  You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

    Unless required by applicable law or agreed to in writing,
    software distributed under the License is distributed on an
    "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
    KIND, either express or implied.  See the License for the
    specific language governing permissions and limitations
    under the License.
 */
package com.wikantik.knowledge.embedding;

import com.wikantik.api.knowledge.KgNode;
import com.wikantik.search.embedding.EmbeddingClient;
import com.wikantik.search.embedding.EmbeddingKind;
import com.wikantik.search.embedding.TextEmbeddingClient;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Warms the {@code kg_node_embeddings} cache. For each KG node, compares the
 * cached content_hash against the live one; only re-embeds on mismatch (or
 * absence). Result counts feed the indexer's start-up log line.
 */
public final class KgNodeEmbeddingService {

    private static final Logger LOG = LogManager.getLogger(KgNodeEmbeddingService.class);

    private final KgNodeEmbeddingRepository repo;
    private final EmbeddingClient client;
    /** Batched seam; when present warmUp embeds the whole stale set per round-trip. */
    private final TextEmbeddingClient batchedClient;
    private final String modelTag;

    /**
     * Single-text client. Retained for callers that genuinely have one, but warmUp
     * then issues one HTTP call per node: measured at 59.9 ms/item against the GPU
     * host versus 9.5 ms/item batched. Prefer the {@link TextEmbeddingClient} ctor.
     */
    public KgNodeEmbeddingService(final KgNodeEmbeddingRepository repo,
                                   final EmbeddingClient client,
                                   final String modelTag) {
        this.repo = repo;
        this.client = client;
        this.batchedClient = null;
        this.modelTag = modelTag;
    }

    /** Batched client: warmUp embeds every stale node per backend round-trip. */
    public KgNodeEmbeddingService(final KgNodeEmbeddingRepository repo,
                                   final TextEmbeddingClient batchedClient,
                                   final String modelTag) {
        this.repo = repo;
        this.client = null;
        this.batchedClient = batchedClient;
        this.modelTag = modelTag;
    }

    public Result warmUp(final List<KgNode> nodes) {
        if (batchedClient != null) {
            return warmUpBatched(nodes);
        }
        int cached = 0, reEmbedded = 0, errors = 0;
        for (final KgNode n : nodes) {
            final String hash = contentHashOf(n);
            final Optional<KgNodeEmbeddingRepository.Cached> existing;
            try {
                existing = repo.findById(n.id(), modelTag);
            } catch (final RuntimeException e) {
                LOG.warn("findById failed for node {}: {}", n.id(), e.getMessage());
                errors++;
                continue;
            }
            if (existing.isPresent() && hash.equals(existing.get().contentHash())) {
                cached++;
                continue;
            }
            final String text = embeddingTextOf(n);
            final float[] vec;
            try {
                vec = client.embed(text);
            } catch (final RuntimeException e) {
                LOG.warn("embed failed for node '{}' ({}): {}", n.name(), modelTag, e.getMessage());
                errors++;
                continue;
            }
            try {
                repo.upsert(n.id(), modelTag, hash, vec);
                reEmbedded++;
            } catch (final RuntimeException e) {
                LOG.warn("upsert embedding failed for node '{}': {}", n.name(), e.getMessage());
                errors++;
            }
        }
        return new Result(cached, reEmbedded, errors);
    }

    /** The model tag this service writes (and reads back) embeddings under.
     * Callers that bypass the service for queries (e.g. the indexer's top-K
     * dictionary lookup) thread this into the repository so they hit the
     * same model slice as the warmer wrote. */
    public String modelTag() {
        return modelTag;
    }

    /**
     * Batched warmUp: partition the nodes into cached vs stale exactly as the
     * per-node path does, then embed the whole stale set through the batching
     * client (which splits on its own configured batch size) in input order.
     *
     * <p>A backend failure fails the whole batch — the per-node path could isolate
     * one bad node, so the counts fall back to marking every stale node an error
     * rather than silently reporting success.</p>
     */
    private Result warmUpBatched(final List<KgNode> nodes) {
        int cached = 0, errors = 0;
        final List<KgNode> stale = new ArrayList<>();
        final List<String> texts = new ArrayList<>();
        final List<String> hashes = new ArrayList<>();

        for (final KgNode n : nodes) {
            final String hash = contentHashOf(n);
            final Optional<KgNodeEmbeddingRepository.Cached> existing;
            try {
                existing = repo.findById(n.id(), modelTag);
            } catch (final RuntimeException e) {
                LOG.warn("findById failed for node {}: {}", n.id(), e.getMessage());
                errors++;
                continue;
            }
            if (existing.isPresent() && hash.equals(existing.get().contentHash())) {
                cached++;
                continue;
            }
            stale.add(n);
            texts.add(embeddingTextOf(n));
            hashes.add(hash);
        }

        if (stale.isEmpty()) {
            return new Result(cached, 0, errors);
        }

        final List<float[]> vectors;
        try {
            // DOCUMENT, not QUERY: node text is corpus-side, and the wrong kind applies
            // the wrong model prefix, silently degrading similarity against the index.
            vectors = batchedClient.embed(texts, EmbeddingKind.DOCUMENT);
        } catch (final RuntimeException e) {
            LOG.warn("batched embed of {} node(s) failed ({}): {}", stale.size(), modelTag, e.getMessage());
            return new Result(cached, 0, errors + stale.size());
        }
        if (vectors == null || vectors.size() != stale.size()) {
            LOG.warn("batched embed returned {} vector(s) for {} node(s) — discarding batch",
                vectors == null ? 0 : vectors.size(), stale.size());
            return new Result(cached, 0, errors + stale.size());
        }

        int reEmbedded = 0;
        for (int i = 0; i < stale.size(); i++) {
            try {
                repo.upsert(stale.get(i).id(), modelTag, hashes.get(i), vectors.get(i));
                reEmbedded++;
            } catch (final RuntimeException e) {
                LOG.warn("upsert embedding failed for node '{}': {}", stale.get(i).name(), e.getMessage());
                errors++;
            }
        }
        return new Result(cached, reEmbedded, errors);
    }

    static String embeddingTextOf(final KgNode n) {
        final String type = n.nodeType() == null ? "Concept" : n.nodeType();
        final String sp   = n.sourcePage() == null ? n.name() : n.sourcePage();
        return n.name() + " :: " + type + " :: " + sp;
    }

    public static String contentHashOf(final KgNode n) {
        try {
            final MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(embeddingTextOf(n).getBytes(StandardCharsets.UTF_8)));
        } catch (final NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    public record Result(int cached, int reEmbedded, int errors) {}
}
