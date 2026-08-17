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
package com.wikantik.insights.runtime;

import com.wikantik.api.pagegraph.Confidence;
import com.wikantik.api.pagegraph.PageDescriptor;
import com.wikantik.api.pagegraph.PageType;
import com.wikantik.api.pagegraph.StructuralIndexService;
import com.wikantik.api.pagegraph.Verification;
import com.wikantik.insights.PageFacts;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link WikiPageFacts}, the {@code wikantik-main} adapter for the
 * {@code wikantik-insights} {@link PageFacts} port (content-intelligence design D5).
 */
class WikiPageFactsTest {

    private StructuralIndexService structuralIndex;
    private WikiPageFacts facts;

    @BeforeEach
    void setUp() {
        structuralIndex = mock( StructuralIndexService.class );
        facts = new WikiPageFacts( structuralIndex );
    }

    private static PageDescriptor descriptor( final String title, final String cluster,
                                              final List<String> tags, final String summary ) {
        return new PageDescriptor( "01HAA00000000000000000001", "PhilosophyOfMind", title,
                PageType.ARTICLE, cluster, tags, summary, Instant.now(), Optional.empty(), false );
    }

    @Test
    void constructorRejectsNullStructuralIndex() {
        assertThrows( IllegalArgumentException.class, () -> new WikiPageFacts( null ) );
    }

    // --- happy path ---------------------------------------------------------------------------

    @Test
    void resolvesFullFactsForAKnownPage() {
        when( structuralIndex.resolveCanonicalIdFromSlug( "PhilosophyOfMind" ) )
                .thenReturn( Optional.of( "01HAA00000000000000000001" ) );
        when( structuralIndex.getByCanonicalId( "01HAA00000000000000000001" ) )
                .thenReturn( Optional.of( descriptor( "Philosophy of Mind", "philosophy",
                        List.of( "consciousness", "dualism" ), "An overview of the mind-body problem." ) ) );
        final Instant verifiedAt = Instant.parse( "2026-01-15T00:00:00Z" );
        when( structuralIndex.verificationOf( "01HAA00000000000000000001" ) )
                .thenReturn( Optional.of( new Verification( verifiedAt, "jake", Confidence.AUTHORITATIVE,
                        null ) ) );

        final Optional<PageFacts.PageFact> result = facts.lookup( "/wiki/PhilosophyOfMind" );

        assertTrue( result.isPresent() );
        final PageFacts.PageFact fact = result.get();
        assertEquals( "/wiki/PhilosophyOfMind", fact.pagePath() );
        assertEquals( "Philosophy of Mind", fact.title() );
        assertEquals( "An overview of the mind-body problem.", fact.summary() );
        assertEquals( List.of( "consciousness", "dualism" ), fact.tags() );
        assertEquals( "philosophy", fact.cluster() );
        assertEquals( verifiedAt, fact.verifiedAt() );
        assertEquals( "authoritative", fact.confidence() );
    }

    @Test
    void noExplicitVerificationRowFallsBackToUnverified() {
        when( structuralIndex.resolveCanonicalIdFromSlug( "PhilosophyOfMind" ) )
                .thenReturn( Optional.of( "01HAA00000000000000000001" ) );
        when( structuralIndex.getByCanonicalId( "01HAA00000000000000000001" ) )
                .thenReturn( Optional.of( descriptor( "Philosophy of Mind", "philosophy", List.of(), null ) ) );
        when( structuralIndex.verificationOf( "01HAA00000000000000000001" ) ).thenReturn( Optional.empty() );

        final PageFacts.PageFact fact = facts.lookup( "/wiki/PhilosophyOfMind" ).orElseThrow();

        assertNull( fact.verifiedAt() );
        assertEquals( "provisional", fact.confidence() );
    }

    @Test
    void aPageWithNoTagsReturnsAnEmptyListNotNull() {
        when( structuralIndex.resolveCanonicalIdFromSlug( "PhilosophyOfMind" ) )
                .thenReturn( Optional.of( "01HAA00000000000000000001" ) );
        when( structuralIndex.getByCanonicalId( "01HAA00000000000000000001" ) )
                .thenReturn( Optional.of( descriptor( "Philosophy of Mind", null, null, null ) ) );
        when( structuralIndex.verificationOf( "01HAA00000000000000000001" ) ).thenReturn( Optional.empty() );

        final PageFacts.PageFact fact = facts.lookup( "/wiki/PhilosophyOfMind" ).orElseThrow();

        assertEquals( List.of(), fact.tags() );
    }

    // --- unknown page → empty, never a throw --------------------------------------------------

    @Test
    void unresolvableSlugReturnsEmpty() {
        when( structuralIndex.resolveCanonicalIdFromSlug( "GhostPage" ) ).thenReturn( Optional.empty() );

        assertTrue( facts.lookup( "/wiki/GhostPage" ).isEmpty() );
    }

    @Test
    void resolvedSlugWithNoDescriptorReturnsEmpty() {
        when( structuralIndex.resolveCanonicalIdFromSlug( "GhostPage" ) )
                .thenReturn( Optional.of( "01HAA00000000000000000002" ) );
        when( structuralIndex.getByCanonicalId( "01HAA00000000000000000002" ) ).thenReturn( Optional.empty() );

        assertTrue( facts.lookup( "/wiki/GhostPage" ).isEmpty() );
    }

    @Test
    void aPathOutsideTheWikiPrefixReturnsEmpty() {
        assertTrue( facts.lookup( "/notes/GhostPage" ).isEmpty() );
    }

    @Test
    void nullPagePathReturnsEmpty() {
        assertTrue( facts.lookup( null ).isEmpty() );
    }

    // --- fail-soft: a throwing structural index degrades to empty, never propagates -----------

    @Test
    void aStructuralIndexLookupFailureReturnsEmptyRatherThanThrowing() {
        when( structuralIndex.resolveCanonicalIdFromSlug( anyString() ) )
                .thenThrow( new IllegalStateException( "structural index unavailable" ) );

        final Optional<PageFacts.PageFact> result = facts.lookup( "/wiki/PhilosophyOfMind" );

        assertTrue( result.isEmpty(), "a lookup failure must degrade to empty, not propagate" );
    }
}
