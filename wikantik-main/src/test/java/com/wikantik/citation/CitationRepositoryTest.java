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
package com.wikantik.citation;

import static org.junit.jupiter.api.Assertions.*;
import com.wikantik.api.citation.CitationStatus;
import java.sql.Connection;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CitationRepositoryTest {

    private DataSource ds;
    private CitationRepository repo;

    @BeforeEach
    void setUp() throws Exception {
        // Mirror DriftSnapshotRepositoryTest: build an H2 DataSource and CREATE the table.
        ds = TestCitationDb.h2DataSource();
        try ( Connection c = ds.getConnection() ) { TestCitationDb.createSchema( c ); }
        repo = new CitationRepository( ds );
    }

    private static CitationRow newRow( final String src, final String tgt, final String head,
                                       final String span, final CitationStatus st ) {
        final String hash = Spans.hash( Spans.normalize( span ) );
        return new CitationRow( 0, src, tgt, head, span, hash, "claim", 0, 7, st, null, null, null );
    }

    @Test
    void replaceForSourceInsertsRows() {
        repo.replaceForSource( "s1", List.of( newRow( "s1", "t1", "H", "span", CitationStatus.CURRENT ) ) );
        final List< CitationRow > rows = repo.findBySource( "s1" );
        assertEquals( 1, rows.size() );
        assertEquals( "t1", rows.get( 0 ).targetCanonicalId() );
        assertEquals( CitationStatus.CURRENT, rows.get( 0 ).status() );
        assertEquals( 7, rows.get( 0 ).pinnedTargetVersion() );
        assertNotNull( rows.get( 0 ).firstSeen() );
    }

    @Test
    void replaceForSourcePreservesPinAndFirstSeenForSurvivingIdentity() {
        repo.replaceForSource( "s1", List.of( newRow( "s1", "t1", "H", "span", CitationStatus.CURRENT ) ) );
        final CitationRow first = repo.findBySource( "s1" ).get( 0 );
        // Re-save: same identity, new pinned version (12) and new status — pin & first_seen must stick.
        final CitationRow again = new CitationRow( 0, "s1", "t1", "H", "span",
            Spans.hash( Spans.normalize( "span" ) ), "edited claim", 0, 12, CitationStatus.STALE, null, null, null );
        repo.replaceForSource( "s1", List.of( again ) );
        final CitationRow now = repo.findBySource( "s1" ).get( 0 );
        assertEquals( first.id(), now.id() );                       // same row
        assertEquals( 7, now.pinnedTargetVersion() );               // pin preserved
        assertEquals( first.firstSeen(), now.firstSeen() );         // first_seen preserved
        assertEquals( CitationStatus.STALE, now.status() );         // status re-graded
        assertEquals( "edited claim", now.claimText() );            // claim updated
    }

    @Test
    void replaceForSourceDeletesVanishedRows() {
        repo.replaceForSource( "s1", List.of(
            newRow( "s1", "t1", "H", "span", CitationStatus.CURRENT ),
            newRow( "s1", "t2", "H", "other", CitationStatus.CURRENT ) ) );
        repo.replaceForSource( "s1", List.of( newRow( "s1", "t1", "H", "span", CitationStatus.CURRENT ) ) );
        assertEquals( 1, repo.findBySource( "s1" ).size() );
    }

    @Test
    void findByTargetAndUpdateStatus() {
        repo.replaceForSource( "s1", List.of( newRow( "s1", "t1", "H", "span", CitationStatus.CURRENT ) ) );
        final CitationRow r = repo.findByTarget( "t1" ).get( 0 );
        repo.updateStatus( r.id(), CitationStatus.TARGET_MISSING, java.time.Instant.now() );
        assertEquals( CitationStatus.TARGET_MISSING, repo.findBySource( "s1" ).get( 0 ).status() );
    }

    @Test
    void countsByStatus() {
        repo.replaceForSource( "s1", List.of(
            newRow( "s1", "t1", "H", "a", CitationStatus.CURRENT ),
            newRow( "s1", "t2", "H", "b", CitationStatus.STALE ) ) );
        assertEquals( 1, repo.countsByStatus().get( CitationStatus.STALE ) );
    }
}
