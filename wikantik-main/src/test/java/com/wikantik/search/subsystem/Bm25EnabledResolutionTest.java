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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the absent-property fallback for {@code wikantik.bundle.bm25.enabled} to the value the
 * shipped {@code ini/wikantik.properties} declares (true since 2026-06-18), so the code literal
 * and the defaults file can never disagree again ({@code ConfigSurfaceDriftTest} compares them).
 * Unit-test fixtures that must skip the BM25 build say so explicitly in the test-jar overlay
 * {@code wikantik-custom.properties}.
 */
class Bm25EnabledResolutionTest {

    @Test
    void absent_property_means_enabled_like_the_shipped_defaults_file() {
        assertTrue( SearchWiringHelper.resolveBm25Enabled( new Properties() ) );
    }

    @Test
    void explicit_false_disables() {
        final Properties p = new Properties();
        p.setProperty( "wikantik.bundle.bm25.enabled", "false" );
        assertFalse( SearchWiringHelper.resolveBm25Enabled( p ) );
    }

    @Test
    void blank_value_means_unset_so_enabled() {
        final Properties p = new Properties();
        p.setProperty( "wikantik.bundle.bm25.enabled", "" );
        assertTrue( SearchWiringHelper.resolveBm25Enabled( p ) );
    }
}
