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
package com.wikantik.knowledge.structure;

import com.wikantik.api.structure.Audience;
import com.wikantik.api.structure.Confidence;
import com.wikantik.api.structure.Verification;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class PageVerificationDaoTest {

    private DataSource ds;
    private PageVerificationDao dao;

    @BeforeEach
    void setUp() throws Exception {
        final JdbcDataSource h2 = new JdbcDataSource();
        h2.setURL( "jdbc:h2:mem:pvd;MODE=PostgreSQL;DB_CLOSE_DELAY=-1" );
        this.ds = h2;
        try ( Connection c = ds.getConnection(); Statement s = c.createStatement() ) {
            s.executeUpdate( """
                CREATE TABLE page_canonical_ids (
                    canonical_id CHAR(26) PRIMARY KEY,
                    current_slug VARCHAR(512) NOT NULL UNIQUE,
                    title VARCHAR(512) NOT NULL,
                    type VARCHAR(32) NOT NULL,
                    cluster VARCHAR(128),
                    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
                )""" );
            s.executeUpdate( """
                CREATE TABLE page_verification (
                    canonical_id CHAR(26) PRIMARY KEY REFERENCES page_canonical_ids(canonical_id) ON DELETE CASCADE,
                    verified_at TIMESTAMP WITH TIME ZONE,
                    verified_by VARCHAR(64),
                    confidence VARCHAR(16) NOT NULL DEFAULT 'provisional',
                    audience VARCHAR(32) NOT NULL DEFAULT 'humans-and-agents',
                    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
                )""" );
            s.executeUpdate( "INSERT INTO page_canonical_ids (canonical_id, current_slug, title, type) " +
                "VALUES ('01AAAAAAAAAAAAAAAAAAAAAAAA', 'A', 'A', 'article')" );
            s.executeUpdate( "INSERT INTO page_canonical_ids (canonical_id, current_slug, title, type) " +
                "VALUES ('01BBBBBBBBBBBBBBBBBBBBBBBB', 'B', 'B', 'article')" );
        }
        this.dao = new PageVerificationDao( ds );
    }

    @AfterEach
    void tearDown() throws Exception {
        try ( Connection c = ds.getConnection(); Statement s = c.createStatement() ) {
            s.executeUpdate( "DROP TABLE page_verification" );
            s.executeUpdate( "DROP TABLE page_canonical_ids" );
        }
    }

    @Test
    void upsert_inserts_when_missing() {
        final Instant now = Instant.parse( "2026-04-25T12:00:00Z" );
        dao.upsert( "01AAAAAAAAAAAAAAAAAAAAAAAA",
                new Verification( now, "alice", Confidence.AUTHORITATIVE, Audience.HUMANS_AND_AGENTS ) );

        final var loaded = dao.findByCanonicalId( "01AAAAAAAAAAAAAAAAAAAAAAAA" ).orElseThrow();
        assertEquals( "alice", loaded.verifiedBy() );
        assertEquals( Confidence.AUTHORITATIVE, loaded.confidence() );
        assertEquals( Audience.HUMANS_AND_AGENTS, loaded.audience() );
        assertNotNull( loaded.verifiedAt() );
    }

    @Test
    void upsert_updates_when_present() {
        dao.upsert( "01AAAAAAAAAAAAAAAAAAAAAAAA",
                new Verification( Instant.parse( "2026-01-01T00:00:00Z" ), "alice",
                        Confidence.PROVISIONAL, Audience.HUMANS_AND_AGENTS ) );
        dao.upsert( "01AAAAAAAAAAAAAAAAAAAAAAAA",
                new Verification( Instant.parse( "2026-04-25T00:00:00Z" ), "bob",
                        Confidence.AUTHORITATIVE, Audience.AGENTS ) );

        final var loaded = dao.findByCanonicalId( "01AAAAAAAAAAAAAAAAAAAAAAAA" ).orElseThrow();
        assertEquals( "bob", loaded.verifiedBy() );
        assertEquals( Confidence.AUTHORITATIVE, loaded.confidence() );
        assertEquals( Audience.AGENTS, loaded.audience() );
    }

    @Test
    void unverified_record_persists_with_null_timestamp() {
        dao.upsert( "01AAAAAAAAAAAAAAAAAAAAAAAA", Verification.unverified() );
        final var loaded = dao.findByCanonicalId( "01AAAAAAAAAAAAAAAAAAAAAAAA" ).orElseThrow();
        assertNull( loaded.verifiedAt() );
        assertNull( loaded.verifiedBy() );
        assertEquals( Confidence.PROVISIONAL, loaded.confidence() );
    }

    @Test
    void listCanonicalIdsByConfidence_filters_correctly() {
        dao.upsert( "01AAAAAAAAAAAAAAAAAAAAAAAA",
                new Verification( Instant.now(), "a", Confidence.AUTHORITATIVE, Audience.HUMANS_AND_AGENTS ) );
        dao.upsert( "01BBBBBBBBBBBBBBBBBBBBBBBB",
                new Verification( null, null, Confidence.STALE, Audience.HUMANS_AND_AGENTS ) );

        final List< String > stale = dao.listCanonicalIdsByConfidence( Confidence.STALE );
        assertEquals( 1, stale.size() );
        assertEquals( "01BBBBBBBBBBBBBBBBBBBBBBBB", stale.get( 0 ) );
    }

    @Test
    void deleteByCanonicalId_removes_row() {
        dao.upsert( "01AAAAAAAAAAAAAAAAAAAAAAAA", Verification.unverified() );
        dao.deleteByCanonicalId( "01AAAAAAAAAAAAAAAAAAAAAAAA" );
        assertEquals( Optional.empty(), dao.findByCanonicalId( "01AAAAAAAAAAAAAAAAAAAAAAAA" ) );
    }
}
