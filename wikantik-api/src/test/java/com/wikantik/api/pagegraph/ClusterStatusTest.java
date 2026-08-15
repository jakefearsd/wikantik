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
import static org.junit.jupiter.api.Assertions.assertNull;

class ClusterStatusTest {

    private static PageDescriptor page( final String slug, final PageType type, final String cluster ) {
        return new PageDescriptor( "01H8G3Z1K6Q5W7P9X2V4R0T8M" + slug.length(), slug, slug, type,
                                    cluster, List.of(), null, Instant.now(), Optional.empty(), false );
    }

    private static ClusterDetails details( final String name, final PageDescriptor hub, final int members ) {
        final List< PageDescriptor > articles = new java.util.ArrayList<>();
        for ( int i = 0; i < members; i++ ) {
            articles.add( page( "Member" + i, PageType.ARTICLE, name ) );
        }
        return new ClusterDetails( name, hub, articles, Map.of(), Instant.now() );
    }

    @Test
    void a_page_with_no_cluster_has_no_status() {
        assertNull( ClusterStatus.of( null, null ) );
        assertNull( ClusterStatus.of( "  ", null ) );
    }

    @Test
    void reports_the_declaring_hub_and_member_count() {
        final var hub = page( "MLHub", PageType.HUB, "machine-learning" );
        final var status = ClusterStatus.of( "machine-learning", details( "machine-learning", hub, 3 ) );
        assertEquals( "machine-learning", status.path() );
        assertEquals( "MLHub", status.hubSlug() );
        assertEquals( 3, status.memberCount() );
    }

    @Test
    void a_sub_cluster_reports_its_parent_path() {
        final var status = ClusterStatus.of( "machine-learning/mlops", details( "machine-learning/mlops", null, 1 ) );
        assertEquals( "machine-learning", status.parent() );
    }

    @Test
    void a_top_level_cluster_reports_no_parent() {
        final var status = ClusterStatus.of( "machine-learning", details( "machine-learning", null, 1 ) );
        assertNull( status.parent() );
    }

    /** An undeclared cluster still yields a status — with a null hub, which is the signal the UI renders. */
    @Test
    void an_undeclared_cluster_reports_a_null_hub() {
        final var status = ClusterStatus.of( "van-life", details( "van-life", null, 2 ) );
        assertNull( status.hubSlug() );
        assertEquals( 2, status.memberCount() );
    }

    /** The structural index can be absent (early boot, degraded mode) — never throw. */
    @Test
    void an_unavailable_index_still_yields_the_declared_path() {
        final var status = ClusterStatus.of( "van-life", null );
        assertEquals( "van-life", status.path() );
        assertNull( status.hubSlug() );
        assertEquals( 0, status.memberCount() );
    }
}
