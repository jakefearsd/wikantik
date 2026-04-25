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

import com.wikantik.api.structure.Relation;
import com.wikantik.api.structure.RelationType;
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

class PageRelationsDaoTest {

    private DataSource ds;
    private PageRelationsDao dao;

    @BeforeEach
    void setUp() throws Exception {
        final JdbcDataSource h2 = new JdbcDataSource();
        h2.setURL( "jdbc:h2:mem:prd;MODE=PostgreSQL;DB_CLOSE_DELAY=-1" );
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
                CREATE TABLE page_relations (
                    source_id CHAR(26) NOT NULL REFERENCES page_canonical_ids(canonical_id) ON DELETE CASCADE,
                    target_id CHAR(26) NOT NULL REFERENCES page_canonical_ids(canonical_id) ON DELETE CASCADE,
                    relation_type VARCHAR(32) NOT NULL,
                    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    PRIMARY KEY (source_id, target_id, relation_type)
                )""" );
            // Seed three pages so FK inserts succeed.
            s.executeUpdate( "INSERT INTO page_canonical_ids (canonical_id, current_slug, title, type) " +
                "VALUES ('01AAAAAAAAAAAAAAAAAAAAAAAA', 'A', 'A', 'article')" );
            s.executeUpdate( "INSERT INTO page_canonical_ids (canonical_id, current_slug, title, type) " +
                "VALUES ('01BBBBBBBBBBBBBBBBBBBBBBBB', 'B', 'B', 'article')" );
            s.executeUpdate( "INSERT INTO page_canonical_ids (canonical_id, current_slug, title, type) " +
                "VALUES ('01CCCCCCCCCCCCCCCCCCCCCCCC', 'C', 'C', 'hub')" );
        }
        this.dao = new PageRelationsDao( ds );
    }

    @AfterEach
    void tearDown() throws Exception {
        try ( Connection c = ds.getConnection(); Statement s = c.createStatement() ) {
            s.executeUpdate( "DROP TABLE page_relations" );
            s.executeUpdate( "DROP TABLE page_canonical_ids" );
        }
    }

    @Test
    void replaceFor_inserts_new_relations() {
        dao.replaceFor( "01AAAAAAAAAAAAAAAAAAAAAAAA", List.of(
                new Relation( "01AAAAAAAAAAAAAAAAAAAAAAAA", "01CCCCCCCCCCCCCCCCCCCCCCCC", RelationType.PART_OF ),
                new Relation( "01AAAAAAAAAAAAAAAAAAAAAAAA", "01BBBBBBBBBBBBBBBBBBBBBBBB", RelationType.EXAMPLE_OF )
        ) );

        final List< Relation > out = dao.listOutgoing( "01AAAAAAAAAAAAAAAAAAAAAAAA", Optional.empty() );
        assertEquals( 2, out.size() );
    }

    @Test
    void replaceFor_replaces_existing_set_atomically() {
        dao.replaceFor( "01AAAAAAAAAAAAAAAAAAAAAAAA", List.of(
                new Relation( "01AAAAAAAAAAAAAAAAAAAAAAAA", "01CCCCCCCCCCCCCCCCCCCCCCCC", RelationType.PART_OF )
        ) );
        dao.replaceFor( "01AAAAAAAAAAAAAAAAAAAAAAAA", List.of(
                new Relation( "01AAAAAAAAAAAAAAAAAAAAAAAA", "01BBBBBBBBBBBBBBBBBBBBBBBB", RelationType.EXAMPLE_OF )
        ) );

        final List< Relation > out = dao.listOutgoing( "01AAAAAAAAAAAAAAAAAAAAAAAA", Optional.empty() );
        assertEquals( 1, out.size() );
        assertEquals( RelationType.EXAMPLE_OF, out.get( 0 ).type() );
        assertEquals( "01BBBBBBBBBBBBBBBBBBBBBBBB", out.get( 0 ).targetId() );
    }

    @Test
    void replaceFor_with_empty_list_clears_existing() {
        dao.replaceFor( "01AAAAAAAAAAAAAAAAAAAAAAAA", List.of(
                new Relation( "01AAAAAAAAAAAAAAAAAAAAAAAA", "01BBBBBBBBBBBBBBBBBBBBBBBB", RelationType.EXAMPLE_OF )
        ) );
        dao.replaceFor( "01AAAAAAAAAAAAAAAAAAAAAAAA", List.of() );
        assertEquals( 0, dao.listOutgoing( "01AAAAAAAAAAAAAAAAAAAAAAAA", Optional.empty() ).size() );
    }

    @Test
    void listIncoming_returns_relations_pointing_at_target() {
        dao.replaceFor( "01AAAAAAAAAAAAAAAAAAAAAAAA", List.of(
                new Relation( "01AAAAAAAAAAAAAAAAAAAAAAAA", "01CCCCCCCCCCCCCCCCCCCCCCCC", RelationType.PART_OF )
        ) );
        dao.replaceFor( "01BBBBBBBBBBBBBBBBBBBBBBBB", List.of(
                new Relation( "01BBBBBBBBBBBBBBBBBBBBBBBB", "01CCCCCCCCCCCCCCCCCCCCCCCC", RelationType.PART_OF )
        ) );
        final List< Relation > inbound = dao.listIncoming( "01CCCCCCCCCCCCCCCCCCCCCCCC", Optional.empty() );
        assertEquals( 2, inbound.size() );
    }

    @Test
    void listOutgoing_filters_by_type() {
        dao.replaceFor( "01AAAAAAAAAAAAAAAAAAAAAAAA", List.of(
                new Relation( "01AAAAAAAAAAAAAAAAAAAAAAAA", "01BBBBBBBBBBBBBBBBBBBBBBBB", RelationType.EXAMPLE_OF ),
                new Relation( "01AAAAAAAAAAAAAAAAAAAAAAAA", "01CCCCCCCCCCCCCCCCCCCCCCCC", RelationType.PART_OF )
        ) );
        final List< Relation > parts = dao.listOutgoing(
                "01AAAAAAAAAAAAAAAAAAAAAAAA", Optional.of( RelationType.PART_OF ) );
        assertEquals( 1, parts.size() );
        assertEquals( RelationType.PART_OF, parts.get( 0 ).type() );
    }

    @Test
    void replaceFor_rejects_relation_with_mismatched_source() {
        assertThrows( IllegalArgumentException.class, () -> dao.replaceFor(
                "01AAAAAAAAAAAAAAAAAAAAAAAA",
                List.of( new Relation( "01BBBBBBBBBBBBBBBBBBBBBBBB", "01CCCCCCCCCCCCCCCCCCCCCCCC", RelationType.PART_OF ) )
        ) );
    }
}
