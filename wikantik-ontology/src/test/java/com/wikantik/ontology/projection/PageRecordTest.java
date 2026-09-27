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
package com.wikantik.ontology.projection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/** Exercises the canonical constructor's scalar-vs-list reconciliation directly. */
class PageRecordTest {

    @Test
    void nullClustersListFallsBackToScalarCluster() {
        // clusters passed as null (not just empty) must still be derived from the scalar.
        final PageRecord p = new PageRecord( "cid", "Slug", "Title", "article",
                "graph-databases", null, List.of(), "summary", "2026-01-01", "author" );
        assertEquals( List.of( "graph-databases" ), p.clusters() );
        assertEquals( "graph-databases", p.cluster() );
    }

    @Test
    void emptyClustersListAndNullScalarYieldNoMembership() {
        final PageRecord p = new PageRecord( "cid", "Slug", "Title", "article",
                null, List.of(), List.of(), "summary", "2026-01-01", "author" );
        assertTrue( p.clusters().isEmpty() );
        assertNull( p.cluster() );
    }

    @Test
    void explicitClustersListWinsAndReDerivesPrimary() {
        final PageRecord p = new PageRecord( "cid", "Slug", "Title", "article",
                "ignored-stale-primary", List.of( "a", "b" ), List.of(), "summary", "2026-01-01", "author" );
        assertEquals( List.of( "a", "b" ), p.clusters() );
        assertEquals( "a", p.cluster(), "primary is re-derived from clusters(), never trusts a stale scalar" );
    }

    @Test
    void nullTagsBecomeEmptyList() {
        final PageRecord p = new PageRecord( "cid", "Slug", "Title", "article",
                "graph-databases", List.of(), null, "summary", "2026-01-01", "author" );
        assertTrue( p.tags().isEmpty() );
    }
}
