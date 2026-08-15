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
    void null_is_never_a_descendant_and_has_no_parent() {
        assertFalse( ClusterPath.isSelfOrDescendant( null, "machine-learning" ) );
        assertFalse( ClusterPath.isSelfOrDescendant( "machine-learning", null ) );
        assertNull( ClusterPath.parent( null ) );
    }
}
