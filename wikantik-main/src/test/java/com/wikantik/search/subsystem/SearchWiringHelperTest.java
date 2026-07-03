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

import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pins the dense retrieval backend default. Regression guard for the perf
 * flip away from the brute-force {@code inmemory} scan (O(corpus) per query
 * and per save) to the RAM-backed, true-ANN {@code lucene-hnsw} backend,
 * which is also the prod (docker1) choice.
 */
class SearchWiringHelperTest {

    @Test
    void resolveDenseBackend_defaultsToLuceneHnswWhenPropertyAbsent() {
        final Properties props = new Properties();
        assertEquals( "lucene-hnsw", SearchWiringHelper.resolveDenseBackend( props ) );
    }

    @Test
    void resolveDenseBackend_respectsExplicitPropertyCaseInsensitively() {
        final Properties props = new Properties();
        props.setProperty( "wikantik.search.dense.backend", "PgVector" );
        assertEquals( "pgvector", SearchWiringHelper.resolveDenseBackend( props ) );
    }

    @Test
    void resolveDenseBackend_respectsExplicitInmemory() {
        final Properties props = new Properties();
        props.setProperty( "wikantik.search.dense.backend", "inmemory" );
        assertEquals( "inmemory", SearchWiringHelper.resolveDenseBackend( props ) );
    }
}
