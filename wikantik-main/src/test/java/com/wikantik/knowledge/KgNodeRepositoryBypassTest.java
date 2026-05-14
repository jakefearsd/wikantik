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
package com.wikantik.knowledge;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that the SQL strings produced by the package-private builders in
 * {@link KgNodeRepository} respect the {@code adminBypass} flag without
 * requiring a live database connection.
 */
public class KgNodeRepositoryBypassTest {

    @Test
    void queryNodesSqlOmitsExcludedJoinWhenBypassIsTrue() {
        final String sql = KgNodeRepository.buildQueryNodesSql( null, null, true );
        assertFalse( sql.contains( "kg_excluded_pages" ),
                "Admin bypass must omit the kg_excluded_pages join: " + sql );
        assertTrue( sql.contains( " TRUE " ), "Bypass should splice in TRUE: " + sql );
    }

    @Test
    void queryNodesSqlIncludesExcludedJoinByDefault() {
        final String sql = KgNodeRepository.buildQueryNodesSql( null, null, false );
        assertTrue( sql.contains( "kg_excluded_pages" ),
                "Non-bypass query must apply the exclusion filter: " + sql );
        assertTrue( sql.contains( "kgxn.page_name IS NULL" ),
                "Non-bypass query must keep the NULL predicate: " + sql );
    }

    @Test
    void searchNodesSqlRespectsBypass() {
        final String byp = KgNodeRepository.buildSearchNodesSql( null, true );
        final String std = KgNodeRepository.buildSearchNodesSql( null, false );
        assertFalse( byp.contains( "kg_excluded_pages" ),
                "Admin bypass search SQL must omit kg_excluded_pages: " + byp );
        assertTrue( std.contains( "kg_excluded_pages" ),
                "Non-bypass search SQL must include kg_excluded_pages: " + std );
    }
}
