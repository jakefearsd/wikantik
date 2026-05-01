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
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
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
    private final String modelTag;

    public KgNodeEmbeddingService(final KgNodeEmbeddingRepository repo,
                                   final EmbeddingClient client,
                                   final String modelTag) {
        this.repo = repo;
        this.client = client;
        this.modelTag = modelTag;
    }

    public Result warmUp(final List<KgNode> nodes) {
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
