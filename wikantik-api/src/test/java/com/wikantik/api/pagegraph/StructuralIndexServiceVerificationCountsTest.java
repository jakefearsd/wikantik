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
package com.wikantik.api.pagegraph;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Exercises the default {@link StructuralIndexService#verificationCounts()}
 * tally against a minimal fake that implements only the two methods the
 * default delegates to ({@code listPagesByFilter} + {@code verificationOf}).
 */
class StructuralIndexServiceVerificationCountsTest {

    private static PageDescriptor desc( final String id ) {
        return new PageDescriptor( id, id, id, PageType.ARTICLE,
                null, List.of(), "summary", Instant.EPOCH, Optional.empty(), false );
    }

    private static Verification at( final Confidence c ) {
        return new Verification( Instant.EPOCH, "alice", c, Audience.HUMANS_AND_AGENTS );
    }

    /** Minimal fake — only the seams the default method needs are real. */
    private static StructuralIndexService fake(
            final List< PageDescriptor > pages,
            final Map< String, Verification > verifications ) {
        return new StubStructuralIndexService() {
            @Override
            public List< PageDescriptor > listPagesByFilter( final StructuralFilter filter ) {
                return pages;
            }

            @Override
            public Optional< Verification > verificationOf( final String canonicalId ) {
                return Optional.ofNullable( verifications.get( canonicalId ) );
            }
        };
    }

    @Test
    void tallies_mixed_pages_with_no_verification_counted_separately() {
        final List< PageDescriptor > pages = List.of(
                desc( "AUTH1" ), desc( "AUTH2" ),
                desc( "PROV1" ),
                desc( "STALE1" ), desc( "STALE2" ),
                desc( "NONE1" ), desc( "NONE2" ), desc( "NONE3" ) );
        final Map< String, Verification > v = Map.of(
                "AUTH1", at( Confidence.AUTHORITATIVE ),
                "AUTH2", at( Confidence.AUTHORITATIVE ),
                "PROV1", at( Confidence.PROVISIONAL ),
                "STALE1", at( Confidence.STALE ),
                "STALE2", at( Confidence.STALE ) );
        // NONE1..3 have no verification row.

        final VerificationCounts counts = fake( pages, v ).verificationCounts();

        assertEquals( 2, counts.authoritative() );
        // PROV1 explicitly provisional + 3 missing rows fall back to unverified() (PROVISIONAL).
        assertEquals( 4, counts.provisional() );
        assertEquals( 2, counts.stale() );
        assertEquals( 3, counts.noVerification() );
        // authoritative + provisional + stale sums to total pages.
        assertEquals( pages.size(),
                counts.authoritative() + counts.provisional() + counts.stale() );
    }

    @Test
    void empty_index_yields_all_zero() {
        final VerificationCounts counts = fake( List.of(), Map.of() ).verificationCounts();
        assertEquals( 0, counts.authoritative() );
        assertEquals( 0, counts.provisional() );
        assertEquals( 0, counts.stale() );
        assertEquals( 0, counts.noVerification() );
    }

    /**
     * Throws for every interface method except the two the default tally uses;
     * subclasses override only those.
     */
    private abstract static class StubStructuralIndexService implements StructuralIndexService {
        @Override public List< ClusterSummary > listClusters() { throw unsupported(); }
        @Override public Optional< ClusterDetails > getCluster( final String name ) { throw unsupported(); }
        @Override public List< TagSummary > listTags( final int minPages ) { throw unsupported(); }
        @Override public List< PageDescriptor > listPagesByType( final PageType type ) { throw unsupported(); }
        @Override public Sitemap sitemap() { throw unsupported(); }
        @Override public Optional< PageDescriptor > getByCanonicalId( final String canonicalId ) { throw unsupported(); }
        @Override public Optional< String > resolveSlugFromCanonicalId( final String canonicalId ) { throw unsupported(); }
        @Override public Optional< String > resolveCanonicalIdFromSlug( final String slug ) { throw unsupported(); }
        @Override public void rebuild() { throw unsupported(); }
        @Override public IndexHealth health() { throw unsupported(); }
        @Override public List< StructuralConflict > conflicts() { throw unsupported(); }
        @Override public StructuralProjectionSnapshot snapshot() { throw unsupported(); }

        private static UnsupportedOperationException unsupported() {
            return new UnsupportedOperationException( "not needed for verificationCounts() test" );
        }
    }
}
