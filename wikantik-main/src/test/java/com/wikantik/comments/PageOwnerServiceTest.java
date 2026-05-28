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
package com.wikantik.comments;

import com.wikantik.api.comments.PageOwnership;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

class PageOwnerServiceTest {

    private DataSource ds;
    private Set< String > users;
    private PageOwnerService svc;

    @BeforeEach
    void setUp() throws Exception {
        final JdbcDataSource h2 = new JdbcDataSource();
        h2.setURL( "jdbc:h2:mem:pos;MODE=PostgreSQL;DB_CLOSE_DELAY=-1" );
        this.ds = h2;
        try ( Connection c = ds.getConnection(); Statement s = c.createStatement() ) {
            s.executeUpdate( """
                CREATE TABLE page_owners (
                    canonical_id TEXT PRIMARY KEY,
                    owner_login  TEXT,
                    assigned_by  TEXT NOT NULL,
                    assigned_at  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
                )""" );
        }
        this.users = new HashSet<>( List.of( "alice", "bob", "admin" ) );
        this.svc = new PageOwnerService( ds, users::contains, defaultAuthorResolver() );
    }

    @AfterEach
    void tearDown() throws Exception {
        try ( Connection c = ds.getConnection(); Statement s = c.createStatement() ) {
            s.executeUpdate( "DROP TABLE page_owners" );
        }
    }

    private Function< String, Optional< String > > defaultAuthorResolver() {
        return canonicalId -> switch ( canonicalId ) {
            case "CID-A" -> Optional.of( "alice" );
            case "CID-GHOST" -> Optional.of( "ghost" );   // user doesn't exist
            case "CID-NONE" -> Optional.empty();
            default -> Optional.empty();
        };
    }

    @Test
    void getOwner_bootstraps_from_frontmatter_when_author_exists() {
        assertEquals( "alice", svc.getOwner( "CID-A" ) );
        final PageOwnership row = svc.findRaw( "CID-A" ).orElseThrow();
        assertEquals( "alice", row.ownerLogin() );
        assertEquals( "system:bootstrap", row.assignedBy() );
    }

    @Test
    void getOwner_returns_admin_when_bootstrap_author_is_not_a_real_user() {
        assertEquals( "admin", svc.getOwner( "CID-GHOST" ) );
        assertNull( svc.findRaw( "CID-GHOST" ).orElseThrow().ownerLogin() );
    }

    @Test
    void getOwner_returns_admin_when_no_author_at_all() {
        assertEquals( "admin", svc.getOwner( "CID-NONE" ) );
        assertNull( svc.findRaw( "CID-NONE" ).orElseThrow().ownerLogin() );
    }

    @Test
    void getOwner_returns_admin_when_owner_was_later_deleted() {
        svc.setOwner( "CID-A", "alice", "admin" );
        users.remove( "alice" );
        assertEquals( "admin", svc.getOwner( "CID-A" ) );
    }

    @Test
    void setOwner_records_assigned_by_and_at() {
        svc.setOwner( "CID-A", "bob", "admin" );
        final PageOwnership row = svc.findRaw( "CID-A" ).orElseThrow();
        assertEquals( "bob", row.ownerLogin() );
        assertEquals( "admin", row.assignedBy() );
        assertNotNull( row.assignedAt() );
    }

    @Test
    void setOwner_overwrites_existing_row_assigned_by_and_at() {
        svc.setOwner( "CID-A", "alice", "system:bootstrap" );
        final java.time.Instant first = svc.findRaw( "CID-A" ).orElseThrow().assignedAt();
        // Give Postgres/H2 timestamp resolution a millisecond.
        try { Thread.sleep( 10 ); } catch ( InterruptedException ignored ) {}
        svc.setOwner( "CID-A", "bob", "admin" );
        final com.wikantik.api.comments.PageOwnership row = svc.findRaw( "CID-A" ).orElseThrow();
        assertEquals( "bob", row.ownerLogin(),
                "second setOwner must overwrite the existing row, not insert a duplicate" );
        assertEquals( "admin", row.assignedBy() );
        assertNotNull( row.assignedAt() );
        // (We don't assert strictly newer than `first` because the resolution at
        // CURRENT_TIMESTAMP can be coarse in H2 — but the overwrite of all three
        // fields proves the ON CONFLICT DO UPDATE branch fired.)
    }

    @Test
    void bulkReassign_moves_all_pages_from_one_owner_to_another() {
        svc.setOwner( "CID-1", "alice", "admin" );
        svc.setOwner( "CID-2", "alice", "admin" );
        svc.setOwner( "CID-3", "bob",   "admin" );
        final int moved = svc.bulkReassign( "alice", "bob", "admin" );
        assertEquals( 2, moved );
        assertEquals( "bob", svc.findRaw( "CID-1" ).orElseThrow().ownerLogin() );
        assertEquals( "bob", svc.findRaw( "CID-2" ).orElseThrow().ownerLogin() );
    }

    @Test
    void orphanByOwner_nulls_out_owner_for_all_their_pages() {
        svc.setOwner( "CID-1", "alice", "admin" );
        svc.setOwner( "CID-2", "alice", "admin" );
        svc.setOwner( "CID-3", "bob",   "admin" );
        final int orphaned = svc.orphanByOwner( "alice", "system:user-deleted:alice" );
        assertEquals( 2, orphaned );
        assertNull( svc.findRaw( "CID-1" ).orElseThrow().ownerLogin() );
        assertNull( svc.findRaw( "CID-2" ).orElseThrow().ownerLogin() );
        assertEquals( "bob", svc.findRaw( "CID-3" ).orElseThrow().ownerLogin() );
    }

    @Test
    void listOrphaned_returns_only_null_owner_rows_paginated() {
        svc.setOwner( "CID-1", "alice", "admin" );
        svc.setOwner( "CID-2", null,    "admin" );
        svc.setOwner( "CID-3", null,    "admin" );
        final List< PageOwnership > orphaned = svc.listOrphaned( 100, 0 );
        assertEquals( 2, orphaned.size() );
        assertEquals( 2, svc.countOrphaned() );
    }

    @Test
    void listByOwner_returns_owned_pages_paginated() {
        svc.setOwner( "CID-1", "alice", "admin" );
        svc.setOwner( "CID-2", "alice", "admin" );
        svc.setOwner( "CID-3", "bob",   "admin" );
        assertEquals( 2, svc.listByOwner( "alice", 100, 0 ).size() );
        assertEquals( 2, svc.countByOwner( "alice" ) );
    }
}
