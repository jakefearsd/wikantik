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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.postgresql.ds.PGSimpleDataSource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named = "wikantik.test.pg.url", matches = ".+",
    disabledReason = "Requires Postgres + V021 schema. Set -Dwikantik.test.pg.url=jdbc:... -Dwikantik.test.pg.user=... -Dwikantik.test.pg.password=...")
class KgNodeEmbeddingRepositoryTest {

    private KgNodeEmbeddingRepository repo;
    private DataSource ds;

    @BeforeEach
    void setUp() throws Exception {
        PGSimpleDataSource pg = new PGSimpleDataSource();
        pg.setUrl(System.getProperty("wikantik.test.pg.url"));
        pg.setUser(System.getProperty("wikantik.test.pg.user", "jspwiki"));
        pg.setPassword(System.getProperty("wikantik.test.pg.password", ""));
        ds = pg;
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            st.execute("DELETE FROM kg_node_embeddings WHERE content_hash LIKE 'test-%'");
        }
        repo = new KgNodeEmbeddingRepository(ds);
    }

    @Test
    void upsertThenFindReturnsSameVector() throws Exception {
        UUID nodeId = anyExistingNodeId();
        float[] vec = new float[1024];
        for (int i = 0; i < 1024; i++) vec[i] = (float)(Math.sin(i) * 0.1);
        repo.upsert(nodeId, "test-hash-1", vec);
        Optional<KgNodeEmbeddingRepository.Cached> got = repo.findById(nodeId);
        assertTrue(got.isPresent());
        assertEquals("test-hash-1", got.get().contentHash());
        assertArrayEquals(vec, got.get().embedding(), 1e-6f);
    }

    @Test
    void upsertReplacesExistingRow() throws Exception {
        UUID nodeId = anyExistingNodeId();
        float[] v1 = new float[1024];
        v1[0] = 0.25f;
        repo.upsert(nodeId, "test-hash-old", v1);
        float[] v2 = new float[1024];
        v2[0] = 0.75f;
        repo.upsert(nodeId, "test-hash-new", v2);
        Optional<KgNodeEmbeddingRepository.Cached> got = repo.findById(nodeId);
        assertTrue(got.isPresent());
        assertEquals("test-hash-new", got.get().contentHash());
        assertEquals(0.75f, got.get().embedding()[0], 1e-6f);
    }

    @Test
    void findByIdReturnsEmptyForUnknownNode() {
        UUID phantom = UUID.randomUUID();
        assertTrue(repo.findById(phantom).isEmpty());
    }

    @Test
    void findTopKReturnsClosestNodes() throws Exception {
        UUID a = anyExistingNodeId();
        float[] vec = new float[1024];
        vec[0] = 1.0f;
        repo.upsert(a, "test-hash-2", vec);
        List<KgNode> top = repo.findTopKByPageEmbedding(vec, 10);
        assertFalse(top.isEmpty());
        assertEquals(a, top.get(0).id());
    }

    private UUID anyExistingNodeId() throws Exception {
        try (Connection c = ds.getConnection();
             Statement st = c.createStatement();
             var rs = st.executeQuery("SELECT id FROM kg_nodes LIMIT 1")) {
            assertTrue(rs.next(), "test requires at least one row in kg_nodes");
            return UUID.fromString(rs.getString(1));
        }
    }
}
