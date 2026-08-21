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
package com.wikantik.search.subsystem;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the contract BETWEEN the two places that resolve
 * {@code wikantik.search.dense.backend}, which is why this lives in its own class
 * rather than in either {@code SearchWiringHelperTest} or
 * {@code SearchSubsystemFactoryTest} — neither owns the relationship.
 *
 * <p>The two resolvers deliberately disagree when the property is absent:
 * <ul>
 *   <li>{@link SearchWiringHelper#resolveDenseBackend} answers {@code lucene-hnsw} —
 *       the real wiring path, matching production.</li>
 *   <li>{@link SearchSubsystemFactory#resolveFallbackDenseBackend} answers
 *       {@code inmemory} — the degraded path taken only when nothing was wired
 *       (embeddings disabled, wiring failed, or a bare mocked Engine in a test).
 *       It is the one backend that never needs a {@code DataSource}, so it is the
 *       only safe answer when properties may not even be present.</li>
 * </ul>
 *
 * <p>That difference is intentional but was previously undocumented at one site and
 * unenforced at both, so a future "let's make these consistent" cleanup could quietly
 * make the fallback require a {@code DataSource} it is not guaranteed to have. These
 * tests make the divergence a stated contract instead of an accident of history.
 *
 * <p>Note it is masked in production: {@code ini/wikantik.properties} ships
 * {@code wikantik.search.dense.backend = lucene-hnsw} and custom properties merge over
 * that, so a real engine always has the property set and both paths agree. The
 * divergence is reachable only from partial/absent properties.
 */
class DenseBackendResolutionTest {

    private static final String PROP = "wikantik.search.dense.backend";

    private static Properties props( final String backend ) {
        final Properties p = new Properties();
        if ( backend != null ) {
            p.setProperty( PROP, backend );
        }
        return p;
    }

    @Test
    void absentProperty_theTwoResolversDeliberatelyDisagree() {
        assertEquals( "lucene-hnsw", SearchWiringHelper.resolveDenseBackend( props( null ) ),
            "The wiring path must default to the production backend." );
        assertEquals( "inmemory", SearchSubsystemFactory.resolveFallbackDenseBackend( props( null ) ),
            "The fallback path must default to the only backend that needs no DataSource. "
          + "If you are changing this to match the wiring default, read this class's javadoc first." );
    }

    @Test
    void nullProperties_fallbackStillAnswersInMemory() {
        // The bare-mocked-Engine case: there may be no Properties object at all.
        assertEquals( "inmemory", SearchSubsystemFactory.resolveFallbackDenseBackend( null ),
            "The fallback must survive absent Properties without throwing." );
    }

    /**
     * The production case. Once the property is actually set — as the shipped
     * ini/wikantik.properties always does — the two paths must not diverge for ANY
     * supported value, or a process whose wiring failed would silently retrieve
     * against a different index than the one it was configured for.
     */
    @Test
    void explicitProperty_bothResolversAgreeForEverySupportedBackend() {
        for ( final String backend : DenseBackends.SUPPORTED ) {
            assertEquals( SearchWiringHelper.resolveDenseBackend( props( backend ) ),
                          SearchSubsystemFactory.resolveFallbackDenseBackend( props( backend ) ),
                          "Both resolvers must agree on an explicitly configured backend: " + backend );
            assertEquals( backend, SearchSubsystemFactory.resolveFallbackDenseBackend( props( backend ) ),
                          "Resolution must return the configured backend verbatim: " + backend );
        }
    }

    @Test
    void explicitProperty_bothResolversNormaliseCaseIdentically() {
        assertEquals( "lucene-hnsw", SearchWiringHelper.resolveDenseBackend( props( "LUCENE-HNSW" ) ) );
        assertEquals( "lucene-hnsw", SearchSubsystemFactory.resolveFallbackDenseBackend( props( "LUCENE-HNSW" ) ) );
    }

    // ------------------------------------------------------------------
    // The supported-backend list and its rejection message
    // ------------------------------------------------------------------

    @Test
    void supportedList_isTheSingleSourceOfTruthForBothSwitches() {
        assertEquals( List.of( "inmemory", "pgvector", "lucene-hnsw" ), DenseBackends.SUPPORTED,
            "Both dense-backend switches branch on exactly these names; adding a backend "
          + "means adding it here and to both switches." );
    }

    /**
     * The rejection message used to be a hand-copied string literal in two files. Building
     * it from {@link DenseBackends#SUPPORTED} means a new backend cannot leave one copy
     * of the message stale.
     */
    @Test
    void unsupportedBackend_messageNamesEverySupportedBackendAndTheOffendingValue() {
        final IllegalArgumentException e = DenseBackends.unsupported( "redis-vector" );
        for ( final String backend : DenseBackends.SUPPORTED ) {
            assertTrue( e.getMessage().contains( backend ),
                "Rejection message must name supported backend '" + backend + "': " + e.getMessage() );
        }
        assertTrue( e.getMessage().contains( "redis-vector" ),
            "Rejection message must quote the offending value: " + e.getMessage() );
    }
}
