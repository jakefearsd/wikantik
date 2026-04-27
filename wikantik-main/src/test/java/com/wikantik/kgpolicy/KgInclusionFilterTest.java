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
package com.wikantik.kgpolicy;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class KgInclusionFilterTest {

    @Test
    void node_fragments_join_on_source_page() {
        assertTrue( KgInclusionFilter.NODE_FILTER_JOIN.contains( "kg_excluded_pages" ) );
        assertTrue( KgInclusionFilter.NODE_FILTER_JOIN.contains( "n.source_page" ) );
        assertTrue( KgInclusionFilter.NODE_FILTER_WHERE.contains( "IS NULL" ) );
    }

    @Test
    void edge_fragments_pull_in_source_node_alias() {
        assertTrue( KgInclusionFilter.EDGE_FILTER_JOIN.contains( "kg_nodes ns" ) );
        assertTrue( KgInclusionFilter.EDGE_FILTER_JOIN.contains( "ns.source_page" ) );
        assertTrue( KgInclusionFilter.EDGE_FILTER_WHERE.contains( "IS NULL" ) );
    }

    @Test
    void mention_fragments_route_through_content_chunks() {
        assertTrue( KgInclusionFilter.MENTION_FILTER_JOIN.contains( "kg_content_chunks" ) );
        assertTrue( KgInclusionFilter.MENTION_FILTER_JOIN.contains( "c.page_name" ) );
        assertTrue( KgInclusionFilter.MENTION_FILTER_WHERE.contains( "IS NULL" ) );
    }

    @Test
    void each_scope_uses_a_distinct_alias_so_multiple_filters_can_coexist() {
        // kgxn, kgxe, kgxm — distinct so a query can apply more than one filter.
        assertTrue( KgInclusionFilter.NODE_FILTER_JOIN.contains( "kgxn" ) );
        assertTrue( KgInclusionFilter.EDGE_FILTER_JOIN.contains( "kgxe" ) );
        assertTrue( KgInclusionFilter.MENTION_FILTER_JOIN.contains( "kgxm" ) );

        assertNotEquals(
                KgInclusionFilter.NODE_FILTER_WHERE.trim(),
                KgInclusionFilter.EDGE_FILTER_WHERE.trim() );
        assertNotEquals(
                KgInclusionFilter.NODE_FILTER_WHERE.trim(),
                KgInclusionFilter.MENTION_FILTER_WHERE.trim() );
    }
}
