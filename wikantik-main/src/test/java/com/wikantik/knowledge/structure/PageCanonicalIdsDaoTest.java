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

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class PageCanonicalIdsDaoTest {

    private DataSource ds;
    private PageCanonicalIdsDao dao;

    @BeforeEach
    void setUp() throws Exception {
        final JdbcDataSource h2 = new JdbcDataSource();
        h2.setURL( "jdbc:h2:mem:pci;MODE=PostgreSQL;DB_CLOSE_DELAY=-1" );
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
                CREATE TABLE page_slug_history (
                    canonical_id CHAR(26) NOT NULL,
                    previous_slug VARCHAR(512) NOT NULL,
                    renamed_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    PRIMARY KEY (canonical_id, previous_slug)
                )""" );
        }
        this.dao = new PageCanonicalIdsDao( ds );
    }

    @AfterEach
    void tearDown() throws Exception {
        try ( Connection c = ds.getConnection(); Statement s = c.createStatement() ) {
            s.executeUpdate( "DROP TABLE page_slug_history" );
            s.executeUpdate( "DROP TABLE page_canonical_ids" );
        }
    }

    @Test
    void upsert_inserts_new_row() {
        dao.upsert( "01H8G3Z1K6Q5W7P9X2V4R0T8MN", "HybridRetrieval", "Hybrid Retrieval",
                    "article", "wikantik-development" );

        final Optional< PageCanonicalIdsDao.Row > row =
                dao.findByCanonicalId( "01H8G3Z1K6Q5W7P9X2V4R0T8MN" );
        assertTrue( row.isPresent() );
        assertEquals( "HybridRetrieval",       row.get().currentSlug() );
        assertEquals( "article",               row.get().type() );
        assertEquals( "wikantik-development",  row.get().cluster() );
    }

    @Test
    void upsert_updates_slug_and_records_history() {
        dao.upsert( "01H8G3Z1K6Q5W7P9X2V4R0T8MN", "HybridRetrieval", "Hybrid Retrieval",
                    "article", "wikantik-development" );

        dao.upsert( "01H8G3Z1K6Q5W7P9X2V4R0T8MN", "HybridSearch", "Hybrid Search",
                    "article", "wikantik-development" );

        final var row = dao.findByCanonicalId( "01H8G3Z1K6Q5W7P9X2V4R0T8MN" ).orElseThrow();
        assertEquals( "HybridSearch", row.currentSlug() );

        final List< String > history = dao.slugHistory( "01H8G3Z1K6Q5W7P9X2V4R0T8MN" );
        assertEquals( List.of( "HybridRetrieval" ), history );
    }

    @Test
    void findBySlug_returns_row_for_current_slug() {
        dao.upsert( "01H8G3Z1K6Q5W7P9X2V4R0T8MN", "HybridRetrieval", "Hybrid Retrieval",
                    "article", "wikantik-development" );
        assertTrue( dao.findBySlug( "HybridRetrieval" ).isPresent() );
        assertTrue( dao.findBySlug( "ThisDoesNotExist" ).isEmpty() );
    }

    @Test
    void findAll_returns_rows_in_stable_order() {
        dao.upsert( "01ABCDEFGHJKMNPQRSTVWXYZ12", "AA", "AA", "article", null );
        dao.upsert( "01ABCDEFGHJKMNPQRSTVWXYZ13", "BB", "BB", "hub",     null );
        final List< PageCanonicalIdsDao.Row > all = dao.findAll();
        assertEquals( 2, all.size() );
    }

    @Test
    void delete_removes_canonical_and_cascades_history() {
        dao.upsert( "01H8G3Z1K6Q5W7P9X2V4R0T8MN", "A", "A", "article", null );
        dao.upsert( "01H8G3Z1K6Q5W7P9X2V4R0T8MN", "B", "B", "article", null );
        assertEquals( 1, dao.slugHistory( "01H8G3Z1K6Q5W7P9X2V4R0T8MN" ).size() );

        dao.delete( "01H8G3Z1K6Q5W7P9X2V4R0T8MN" );
        assertTrue( dao.findByCanonicalId( "01H8G3Z1K6Q5W7P9X2V4R0T8MN" ).isEmpty() );
    }
}
