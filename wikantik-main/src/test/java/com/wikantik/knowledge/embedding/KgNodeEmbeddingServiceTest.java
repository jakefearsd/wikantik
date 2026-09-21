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
import com.wikantik.api.knowledge.Provenance;
import com.wikantik.search.embedding.EmbeddingClient;
import com.wikantik.search.embedding.EmbeddingKind;
import com.wikantik.search.embedding.TextEmbeddingClient;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class KgNodeEmbeddingServiceTest {

    @Test
    void cachedHashSkipsEmbedding() {
        final KgNodeEmbeddingRepository repo = mock(KgNodeEmbeddingRepository.class);
        final EmbeddingClient client = mock(EmbeddingClient.class);
        final UUID id = UUID.randomUUID();
        final KgNode node = new KgNode(id, "Kafka", "Technology", "Kafka",
            Provenance.HUMAN_AUTHORED, Map.of(), Instant.now(), Instant.now(), "human", null);

        final String expectedHash = KgNodeEmbeddingService.contentHashOf(node);
        when(repo.findById(eq(id), eq("qwen3-embedding:0.6b"))).thenReturn(
            Optional.of(new KgNodeEmbeddingRepository.Cached(expectedHash, new float[1024])));

        final KgNodeEmbeddingService svc = new KgNodeEmbeddingService(repo, client, "qwen3-embedding:0.6b");
        final KgNodeEmbeddingService.Result r = svc.warmUp(List.of(node));
        assertEquals(1, r.cached());
        assertEquals(0, r.reEmbedded());
        assertEquals(0, r.errors());
        verify(client, never()).embed(any());
        verify(repo, never()).upsert(any(), any(), any(), any());
    }

    @Test
    void hashMismatchTriggersReEmbed() {
        final KgNodeEmbeddingRepository repo = mock(KgNodeEmbeddingRepository.class);
        final EmbeddingClient client = mock(EmbeddingClient.class);
        final UUID id = UUID.randomUUID();
        final KgNode node = new KgNode(id, "Kafka", "Technology", "Kafka",
            Provenance.HUMAN_AUTHORED, Map.of(), Instant.now(), Instant.now(), "human", null);
        when(repo.findById(eq(id), eq("qwen3-embedding:0.6b"))).thenReturn(
            Optional.of(new KgNodeEmbeddingRepository.Cached("stale-hash", new float[1024])));
        final float[] vec = new float[1024];
        vec[0] = 0.5f;
        when(client.embed("Kafka :: Technology :: Kafka")).thenReturn(vec);

        final KgNodeEmbeddingService svc = new KgNodeEmbeddingService(repo, client, "qwen3-embedding:0.6b");
        final KgNodeEmbeddingService.Result r = svc.warmUp(List.of(node));
        assertEquals(0, r.cached());
        assertEquals(1, r.reEmbedded());
        verify(repo).upsert(eq(id), eq("qwen3-embedding:0.6b"),
            eq(KgNodeEmbeddingService.contentHashOf(node)), eq(vec));
    }

    @Test
    void missingCacheTriggersEmbedAndUpsert() {
        final KgNodeEmbeddingRepository repo = mock(KgNodeEmbeddingRepository.class);
        final EmbeddingClient client = mock(EmbeddingClient.class);
        final UUID id = UUID.randomUUID();
        final KgNode node = new KgNode(id, "Kafka", "Technology", "Kafka",
            Provenance.HUMAN_AUTHORED, Map.of(), Instant.now(), Instant.now(), "human", null);
        when(repo.findById(eq(id), eq("qwen3-embedding:0.6b"))).thenReturn(Optional.empty());
        final float[] vec = new float[1024];
        vec[0] = 0.5f;
        when(client.embed("Kafka :: Technology :: Kafka")).thenReturn(vec);

        final KgNodeEmbeddingService svc = new KgNodeEmbeddingService(repo, client, "qwen3-embedding:0.6b");
        final KgNodeEmbeddingService.Result r = svc.warmUp(List.of(node));
        assertEquals(0, r.cached());
        assertEquals(1, r.reEmbedded());
        verify(repo).upsert(eq(id), eq("qwen3-embedding:0.6b"),
            eq(KgNodeEmbeddingService.contentHashOf(node)), eq(vec));
    }

    @Test
    void embedFailureContinuesProcessing() {
        final KgNodeEmbeddingRepository repo = mock(KgNodeEmbeddingRepository.class);
        final EmbeddingClient client = mock(EmbeddingClient.class);
        final UUID id1 = UUID.randomUUID();
        final UUID id2 = UUID.randomUUID();
        final KgNode bad  = new KgNode(id1, "X", "Concept", "X",
            Provenance.HUMAN_AUTHORED, Map.of(), Instant.now(), Instant.now(), "human", null);
        final KgNode good = new KgNode(id2, "Y", "Concept", "Y",
            Provenance.HUMAN_AUTHORED, Map.of(), Instant.now(), Instant.now(), "human", null);
        when(repo.findById(any(), eq("qwen3-embedding:0.6b"))).thenReturn(Optional.empty());
        when(client.embed("X :: Concept :: X")).thenThrow(new RuntimeException("boom"));
        when(client.embed("Y :: Concept :: Y")).thenReturn(new float[1024]);

        final KgNodeEmbeddingService svc = new KgNodeEmbeddingService(repo, client, "qwen3-embedding:0.6b");
        final KgNodeEmbeddingService.Result r = svc.warmUp(List.of(bad, good));
        assertEquals(1, r.errors());
        assertEquals(1, r.reEmbedded());
        assertEquals(0, r.cached());
    }

    @Test
    void upsertFailureCountsAsError() {
        final KgNodeEmbeddingRepository repo = mock(KgNodeEmbeddingRepository.class);
        final EmbeddingClient client = mock(EmbeddingClient.class);
        final UUID id = UUID.randomUUID();
        final KgNode node = new KgNode(id, "Z", "Concept", "Z",
            Provenance.HUMAN_AUTHORED, Map.of(), Instant.now(), Instant.now(), "human", null);
        when(repo.findById(eq(id), eq("qwen3-embedding:0.6b"))).thenReturn(Optional.empty());
        when(client.embed(any())).thenReturn(new float[1024]);
        doThrow(new RuntimeException("db down")).when(repo).upsert(any(), any(), any(), any());

        final KgNodeEmbeddingService svc = new KgNodeEmbeddingService(repo, client, "qwen3-embedding:0.6b");
        final KgNodeEmbeddingService.Result r = svc.warmUp(List.of(node));
        assertEquals(1, r.errors());
        assertEquals(0, r.reEmbedded());
    }

    @Test
    void modelTagIsThreadedToRepositoryLookupAndUpsert() {
        // Regression: before V022, findById/upsert didn't filter by model_code,
        // so swapping the embedder silently reused the previous model's
        // vectors. The service must thread its modelTag through both calls.
        final KgNodeEmbeddingRepository repo = mock(KgNodeEmbeddingRepository.class);
        final EmbeddingClient client = mock(EmbeddingClient.class);
        final UUID id = UUID.randomUUID();
        final KgNode node = new KgNode(id, "Kafka", "Technology", "Kafka",
            Provenance.HUMAN_AUTHORED, Map.of(), Instant.now(), Instant.now(), "human", null);

        // Repo has a row for bge-m3 but not for qwen3 — the qwen3-tagged
        // service should miss-and-re-embed instead of reusing the bge-m3 row.
        when(repo.findById(eq(id), eq("bge-m3:latest"))).thenReturn(
            Optional.of(new KgNodeEmbeddingRepository.Cached(
                KgNodeEmbeddingService.contentHashOf(node), new float[1024])));
        when(repo.findById(eq(id), eq("qwen3-embedding:0.6b"))).thenReturn(Optional.empty());
        when(client.embed("Kafka :: Technology :: Kafka")).thenReturn(new float[1024]);

        final KgNodeEmbeddingService qwen = new KgNodeEmbeddingService(repo, client, "qwen3-embedding:0.6b");
        final KgNodeEmbeddingService.Result r = qwen.warmUp(List.of(node));

        assertEquals(0, r.cached(),     "qwen3 service must not see bge-m3 cache hit");
        assertEquals(1, r.reEmbedded(), "qwen3 service must produce a fresh embedding");
        verify(repo).findById(eq(id), eq("qwen3-embedding:0.6b"));
        verify(repo, never()).findById(eq(id), eq("bge-m3:latest"));
        verify(repo).upsert(eq(id), eq("qwen3-embedding:0.6b"),
            eq(KgNodeEmbeddingService.contentHashOf(node)), any());
    }

    @Test
    void batchedClientEmbedsAllStaleNodesInOneRoundTrip() {
        // warmUp embedded one node per HTTP call. Measured against the live host that
        // is 59.9 ms/item sequential vs 9.5 ms/item at batch 64 — a 6.3x difference
        // on a 6,599-node cache. The batched path must issue ONE call for the whole
        // stale set, preserving input order when mapping vectors back to nodes.
        final KgNodeEmbeddingRepository repo = mock(KgNodeEmbeddingRepository.class);
        final TextEmbeddingClient batched = mock(TextEmbeddingClient.class);

        final UUID id1 = UUID.randomUUID();
        final UUID id2 = UUID.randomUUID();
        final UUID id3 = UUID.randomUUID();
        final KgNode a = new KgNode(id1, "Kafka", "Technology", "Kafka",
            Provenance.HUMAN_AUTHORED, Map.of(), Instant.now(), Instant.now(), "human", null);
        final KgNode b = new KgNode(id2, "Raft", "Concept", "Raft",
            Provenance.HUMAN_AUTHORED, Map.of(), Instant.now(), Instant.now(), "human", null);
        final KgNode c = new KgNode(id3, "Paxos", "Concept", "Paxos",
            Provenance.HUMAN_AUTHORED, Map.of(), Instant.now(), Instant.now(), "human", null);

        // b is already cached: it must be excluded from the batch entirely.
        when(repo.findById(any(), eq("qwen3-embedding:0.6b"))).thenReturn(Optional.empty());
        when(repo.findById(eq(id2), eq("qwen3-embedding:0.6b"))).thenReturn(
            Optional.of(new KgNodeEmbeddingRepository.Cached(
                KgNodeEmbeddingService.contentHashOf(b), new float[1024])));

        final float[] vecA = new float[1024]; vecA[0] = 1f;
        final float[] vecC = new float[1024]; vecC[0] = 3f;
        when(batched.embed(eq(List.of("Kafka :: Technology :: Kafka", "Paxos :: Concept :: Paxos")),
                           any(EmbeddingKind.class))).thenReturn(List.of(vecA, vecC));

        final KgNodeEmbeddingService svc =
            new KgNodeEmbeddingService(repo, batched, "qwen3-embedding:0.6b");
        final KgNodeEmbeddingService.Result r = svc.warmUp(List.of(a, b, c));

        assertEquals(1, r.cached());
        assertEquals(2, r.reEmbedded());
        assertEquals(0, r.errors());
        // Exactly one round-trip for the two stale nodes.
        verify(batched, times(1)).embed(any(), any(EmbeddingKind.class));
        // Vectors map back to the right nodes, in input order.
        verify(repo).upsert(eq(id1), eq("qwen3-embedding:0.6b"),
            eq(KgNodeEmbeddingService.contentHashOf(a)), eq(vecA));
        verify(repo).upsert(eq(id3), eq("qwen3-embedding:0.6b"),
            eq(KgNodeEmbeddingService.contentHashOf(c)), eq(vecC));
        verify(repo, never()).upsert(eq(id2), any(), any(), any());
    }

    @Test
    void modelTagAccessorReturnsConstructorValue() {
        final KgNodeEmbeddingService svc = new KgNodeEmbeddingService(
            mock(KgNodeEmbeddingRepository.class),
            mock(EmbeddingClient.class),
            "qwen3-embedding:0.6b");
        assertEquals("qwen3-embedding:0.6b", svc.modelTag());
    }

    @Test
    void nullNodeTypeAndSourcePageFallBackInEmbeddingText() {
        final KgNodeEmbeddingRepository repo = mock(KgNodeEmbeddingRepository.class);
        final EmbeddingClient client = mock(EmbeddingClient.class);
        final UUID id = UUID.randomUUID();
        final KgNode node = new KgNode(id, "Solo", null, null,
            Provenance.HUMAN_AUTHORED, Map.of(), Instant.now(), Instant.now(), "human", null);
        when(repo.findById(eq(id), eq("qwen3-embedding:0.6b"))).thenReturn(Optional.empty());
        when(client.embed("Solo :: Concept :: Solo")).thenReturn(new float[1024]);

        final KgNodeEmbeddingService svc = new KgNodeEmbeddingService(repo, client, "qwen3-embedding:0.6b");
        final KgNodeEmbeddingService.Result r = svc.warmUp(List.of(node));
        assertEquals(1, r.reEmbedded());
        verify(client).embed("Solo :: Concept :: Solo");
    }
}
