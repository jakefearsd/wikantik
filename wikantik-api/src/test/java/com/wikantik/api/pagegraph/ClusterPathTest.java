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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClusterPathTest {

    @Test
    void a_cluster_is_its_own_descendant() {
        assertTrue( ClusterPath.isSelfOrDescendant( "machine-learning", "machine-learning" ) );
    }

    @Test
    void a_sub_cluster_is_a_descendant_of_its_parent() {
        assertTrue( ClusterPath.isSelfOrDescendant( "machine-learning/mlops", "machine-learning" ) );
    }

    /**
     * The invariant the whole design rests on: matching is segment-aware, never
     * string-prefix. `startsWith` would wrongly report true here.
     */
    @Test
    void a_sibling_sharing_a_string_prefix_is_not_a_descendant() {
        assertFalse( ClusterPath.isSelfOrDescendant( "machine-learning-ops", "machine-learning" ) );
    }

    @Test
    void a_parent_is_not_a_descendant_of_its_child() {
        assertFalse( ClusterPath.isSelfOrDescendant( "machine-learning", "machine-learning/mlops" ) );
    }

    @Test
    void parent_of_a_sub_cluster_is_the_leading_segments() {
        assertEquals( "machine-learning", ClusterPath.parent( "machine-learning/mlops" ) );
    }

    @Test
    void a_top_level_cluster_has_no_parent() {
        assertNull( ClusterPath.parent( "machine-learning" ) );
    }

    @Test
    void reparent_renames_the_cluster_itself() {
        assertEquals( "ml", ClusterPath.reparent( "machine-learning", "machine-learning", "ml" ) );
    }

    @Test
    void reparent_carries_sub_clusters_along_with_their_parent() {
        assertEquals( "ml/mlops", ClusterPath.reparent( "machine-learning/mlops", "machine-learning", "ml" ) );
    }

    /** Segment-aware again: a sibling sharing a string prefix must not be dragged into the rename. */
    @Test
    void reparent_leaves_a_sibling_sharing_a_string_prefix_untouched() {
        assertEquals( "machine-learning-ops",
                      ClusterPath.reparent( "machine-learning-ops", "machine-learning", "ml" ) );
    }

    @Test
    void reparent_leaves_an_unrelated_cluster_untouched() {
        assertEquals( "van-life", ClusterPath.reparent( "van-life", "machine-learning", "ml" ) );
    }

    @Test
    void reparent_of_null_is_null() {
        assertNull( ClusterPath.reparent( null, "a", "b" ) );
    }

    // --- Phase 5: multi-membership parsing -------------------------------------------------

    @Test
    void a_scalar_cluster_is_a_single_membership() {
        assertEquals( java.util.List.of( "machine-learning" ),
                      ClusterPath.memberships( "machine-learning" ) );
    }

    @Test
    void a_list_cluster_is_every_membership_in_order() {
        assertEquals( java.util.List.of( "machine-learning", "quantitative-finance" ),
                      ClusterPath.memberships( java.util.List.of( "machine-learning", "quantitative-finance" ) ) );
    }

    /** First entry is primary, so order is meaningful and must survive de-duplication. */
    @Test
    void duplicate_memberships_collapse_keeping_first_position() {
        assertEquals( java.util.List.of( "a", "b" ),
                      ClusterPath.memberships( java.util.List.of( "a", "b", "a" ) ) );
    }

    @Test
    void blank_and_null_entries_are_dropped() {
        final java.util.List< String > raw = new java.util.ArrayList<>();
        raw.add( "a" );
        raw.add( "   " );
        raw.add( null );
        assertEquals( java.util.List.of( "a" ), ClusterPath.memberships( raw ) );
    }

    @Test
    void a_missing_or_blank_cluster_is_no_membership() {
        assertTrue( ClusterPath.memberships( null ).isEmpty() );
        assertTrue( ClusterPath.memberships( "  " ).isEmpty() );
        assertTrue( ClusterPath.memberships( java.util.List.of() ).isEmpty() );
    }

    @Test
    void the_primary_membership_is_the_first_entry() {
        assertEquals( "machine-learning",
                      ClusterPath.primary( java.util.List.of( "machine-learning", "van-life" ) ) );
        assertNull( ClusterPath.primary( null ) );
    }

    @Test
    void null_is_never_a_descendant_and_has_no_parent() {
        assertFalse( ClusterPath.isSelfOrDescendant( null, "machine-learning" ) );
        assertFalse( ClusterPath.isSelfOrDescendant( "machine-learning", null ) );
        assertNull( ClusterPath.parent( null ) );
    }
}
