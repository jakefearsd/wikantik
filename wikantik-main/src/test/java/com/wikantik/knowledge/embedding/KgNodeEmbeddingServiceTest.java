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
            Provenance.HUMAN_AUTHORED, Map.of(), Instant.now(), Instant.now());

        final String expectedHash = KgNodeEmbeddingService.contentHashOf(node);
        when(repo.findById(id)).thenReturn(
            Optional.of(new KgNodeEmbeddingRepository.Cached(expectedHash, new float[1024])));

        final KgNodeEmbeddingService svc = new KgNodeEmbeddingService(repo, client, "bge-m3:latest");
        final KgNodeEmbeddingService.Result r = svc.warmUp(List.of(node));
        assertEquals(1, r.cached());
        assertEquals(0, r.reEmbedded());
        assertEquals(0, r.errors());
        verify(client, never()).embed(any());
        verify(repo, never()).upsert(any(), any(), any());
    }

    @Test
    void hashMismatchTriggersReEmbed() {
        final KgNodeEmbeddingRepository repo = mock(KgNodeEmbeddingRepository.class);
        final EmbeddingClient client = mock(EmbeddingClient.class);
        final UUID id = UUID.randomUUID();
        final KgNode node = new KgNode(id, "Kafka", "Technology", "Kafka",
            Provenance.HUMAN_AUTHORED, Map.of(), Instant.now(), Instant.now());
        when(repo.findById(id)).thenReturn(
            Optional.of(new KgNodeEmbeddingRepository.Cached("stale-hash", new float[1024])));
        final float[] vec = new float[1024];
        vec[0] = 0.5f;
        when(client.embed("Kafka :: Technology :: Kafka")).thenReturn(vec);

        final KgNodeEmbeddingService svc = new KgNodeEmbeddingService(repo, client, "bge-m3:latest");
        final KgNodeEmbeddingService.Result r = svc.warmUp(List.of(node));
        assertEquals(0, r.cached());
        assertEquals(1, r.reEmbedded());
        verify(repo).upsert(eq(id), eq(KgNodeEmbeddingService.contentHashOf(node)), eq(vec));
    }

    @Test
    void missingCacheTriggersEmbedAndUpsert() {
        final KgNodeEmbeddingRepository repo = mock(KgNodeEmbeddingRepository.class);
        final EmbeddingClient client = mock(EmbeddingClient.class);
        final UUID id = UUID.randomUUID();
        final KgNode node = new KgNode(id, "Kafka", "Technology", "Kafka",
            Provenance.HUMAN_AUTHORED, Map.of(), Instant.now(), Instant.now());
        when(repo.findById(id)).thenReturn(Optional.empty());
        final float[] vec = new float[1024];
        vec[0] = 0.5f;
        when(client.embed("Kafka :: Technology :: Kafka")).thenReturn(vec);

        final KgNodeEmbeddingService svc = new KgNodeEmbeddingService(repo, client, "bge-m3:latest");
        final KgNodeEmbeddingService.Result r = svc.warmUp(List.of(node));
        assertEquals(0, r.cached());
        assertEquals(1, r.reEmbedded());
        verify(repo).upsert(eq(id), eq(KgNodeEmbeddingService.contentHashOf(node)), eq(vec));
    }

    @Test
    void embedFailureContinuesProcessing() {
        final KgNodeEmbeddingRepository repo = mock(KgNodeEmbeddingRepository.class);
        final EmbeddingClient client = mock(EmbeddingClient.class);
        final UUID id1 = UUID.randomUUID();
        final UUID id2 = UUID.randomUUID();
        final KgNode bad  = new KgNode(id1, "X", "Concept", "X",
            Provenance.HUMAN_AUTHORED, Map.of(), Instant.now(), Instant.now());
        final KgNode good = new KgNode(id2, "Y", "Concept", "Y",
            Provenance.HUMAN_AUTHORED, Map.of(), Instant.now(), Instant.now());
        when(repo.findById(any())).thenReturn(Optional.empty());
        when(client.embed("X :: Concept :: X")).thenThrow(new RuntimeException("boom"));
        when(client.embed("Y :: Concept :: Y")).thenReturn(new float[1024]);

        final KgNodeEmbeddingService svc = new KgNodeEmbeddingService(repo, client, "bge-m3:latest");
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
            Provenance.HUMAN_AUTHORED, Map.of(), Instant.now(), Instant.now());
        when(repo.findById(id)).thenReturn(Optional.empty());
        when(client.embed(any())).thenReturn(new float[1024]);
        doThrow(new RuntimeException("db down")).when(repo).upsert(any(), any(), any());

        final KgNodeEmbeddingService svc = new KgNodeEmbeddingService(repo, client, "bge-m3:latest");
        final KgNodeEmbeddingService.Result r = svc.warmUp(List.of(node));
        assertEquals(1, r.errors());
        assertEquals(0, r.reEmbedded());
    }

    @Test
    void nullNodeTypeAndSourcePageFallBackInEmbeddingText() {
        final KgNodeEmbeddingRepository repo = mock(KgNodeEmbeddingRepository.class);
        final EmbeddingClient client = mock(EmbeddingClient.class);
        final UUID id = UUID.randomUUID();
        final KgNode node = new KgNode(id, "Solo", null, null,
            Provenance.HUMAN_AUTHORED, Map.of(), Instant.now(), Instant.now());
        when(repo.findById(id)).thenReturn(Optional.empty());
        when(client.embed("Solo :: Concept :: Solo")).thenReturn(new float[1024]);

        final KgNodeEmbeddingService svc = new KgNodeEmbeddingService(repo, client, "bge-m3:latest");
        final KgNodeEmbeddingService.Result r = svc.warmUp(List.of(node));
        assertEquals(1, r.reEmbedded());
        verify(client).embed("Solo :: Concept :: Solo");
    }
}
