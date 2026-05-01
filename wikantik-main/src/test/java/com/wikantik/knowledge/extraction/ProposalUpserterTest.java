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
package com.wikantik.knowledge.extraction;

import com.wikantik.api.knowledge.ConsolidatedProposal;
import com.wikantik.api.knowledge.SupportEvidence;
import com.wikantik.knowledge.JdbcKnowledgeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.postgresql.ds.PGSimpleDataSource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named = "wikantik.test.pg.url", matches = ".+",
    disabledReason = "Requires Postgres + V020 schema. Set -Dwikantik.test.pg.url=jdbc:... -Dwikantik.test.pg.user=... -Dwikantik.test.pg.password=...")
class ProposalUpserterTest {

    private DataSource ds;
    private ProposalUpserter upserter;

    @BeforeEach
    void setUp() throws Exception {
        PGSimpleDataSource pg = new PGSimpleDataSource();
        pg.setUrl(System.getProperty("wikantik.test.pg.url"));
        pg.setUser(System.getProperty("wikantik.test.pg.user", "jspwiki"));
        pg.setPassword(System.getProperty("wikantik.test.pg.password", ""));
        ds = pg;
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            st.execute("DELETE FROM kg_proposals WHERE signature LIKE 'sig-test-%'");
        }
        upserter = new ProposalUpserter(new JdbcKnowledgeRepository(ds));
    }

    @Test
    void firstInsertProducesRowWithSupportCountOne() {
        ConsolidatedProposal p = ConsolidatedProposal.newNode(
            "sig-test-001", "Kafka", "Technology",
            List.of(new SupportEvidence("Page1", "Kafka is a streaming platform.", 0.9, "ollama:gemma4")),
            0.9);
        ProposalUpserter.Result r = upserter.upsert(p);
        assertTrue(r.inserted());
        assertEquals(1, r.supportCount());
    }

    @Test
    void secondUpsertDifferentPageMergesSupport() {
        SupportEvidence p1 = new SupportEvidence("Page1", "Kafka is X.", 0.9, "ollama:gemma4");
        SupportEvidence p2 = new SupportEvidence("Page2", "Kafka is Y.", 0.85, "ollama:gemma4");
        upserter.upsert(ConsolidatedProposal.newNode("sig-test-002", "Kafka", "Technology", List.of(p1), 0.9));
        ProposalUpserter.Result r = upserter.upsert(
            ConsolidatedProposal.newNode("sig-test-002", "Kafka", "Technology", List.of(p2), 0.85));
        assertFalse(r.inserted());
        assertEquals(2, r.supportCount());
    }

    @Test
    void reUpsertSamePageDoesNotDoubleSupport() {
        SupportEvidence p1 = new SupportEvidence("Page1", "Kafka is X.", 0.9, "ollama:gemma4");
        upserter.upsert(ConsolidatedProposal.newNode("sig-test-003", "Kafka", "Technology", List.of(p1), 0.9));
        ProposalUpserter.Result r = upserter.upsert(
            ConsolidatedProposal.newNode("sig-test-003", "Kafka", "Technology", List.of(p1), 0.95));
        assertFalse(r.inserted());
        assertEquals(1, r.supportCount());
    }
}
