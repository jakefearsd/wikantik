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
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 *  ClusterDeclarationDesign Phase 5 — a page may belong to several clusters.
 *
 *  <p>{@code cluster()} keeps meaning "the primary", so every existing consumer
 *  (breadcrumbs, JSON-LD, embedding prefix, sidebar) is correct without changing;
 *  {@code clusters()} is the full membership set, for the consumers that genuinely
 *  ask about membership.</p>
 */
class PageDescriptorClustersTest {

    private static PageDescriptor withClusters( final List< String > clusters ) {
        return new PageDescriptor( "01HAA00000000000000000000", "InferenceServing", "Inference Serving",
                PageType.ARTICLE, null, clusters, List.of(), null, Instant.EPOCH, Optional.empty(), false );
    }

    /** The 10-arg form is the whole existing corpus of call sites; it must keep working. */
    @Test
    void a_scalar_cluster_becomes_a_single_membership() {
        final PageDescriptor p = new PageDescriptor( "01HAA00000000000000000000", "MLHub", "ML Hub",
                PageType.HUB, "machine-learning", List.of(), null, Instant.EPOCH, Optional.empty(), false );

        assertEquals( "machine-learning", p.cluster() );
        assertEquals( List.of( "machine-learning" ), p.clusters() );
    }

    @Test
    void the_primary_cluster_is_the_first_membership() {
        final PageDescriptor p = withClusters( List.of( "machine-learning", "quantitative-finance" ) );

        assertEquals( "machine-learning", p.cluster() );
        assertEquals( List.of( "machine-learning", "quantitative-finance" ), p.clusters() );
    }

    /**
     * The two views cannot be allowed to disagree: a caller passing both gets the primary
     * re-derived from the list, so `cluster()` is always `clusters().get(0)`.
     */
    @Test
    void an_inconsistent_primary_is_re_derived_from_the_memberships() {
        final PageDescriptor p = new PageDescriptor( "01HAA00000000000000000000", "P", "P",
                PageType.ARTICLE, "stale-value", List.of( "real-primary", "second" ),
                List.of(), null, Instant.EPOCH, Optional.empty(), false );

        assertEquals( "real-primary", p.cluster() );
    }

    @Test
    void a_page_with_no_cluster_has_no_memberships() {
        final PageDescriptor p = withClusters( null );

        assertNull( p.cluster() );
        assertTrue( p.clusters().isEmpty() );
    }
}
