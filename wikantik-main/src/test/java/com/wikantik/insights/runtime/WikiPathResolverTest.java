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
package com.wikantik.insights.runtime;

import com.wikantik.jdbc.testing.PostgresTestDb;
import com.wikantik.jdbc.testing.RequiresPostgres;
import com.wikantik.pagegraph.spine.PageCanonicalIdsDao;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

@RequiresPostgres
class WikiPathResolverTest {

    private WikiPathResolver resolver;

    @BeforeEach
    void setUp() {
        final var ds = PostgresTestDb.createDataSource();
        PostgresTestDb.truncate( "page_canonical_ids", "page_slug_history" );
        final PageCanonicalIdsDao dao = new PageCanonicalIdsDao( ds );
        dao.upsert( "01MAIN", "Main", "Main", "hub", null );
        dao.upsert( "01IT", "OldTrees", "Interval Trees", "article", null );
        dao.upsert( "01IT", "IntervalTrees", "Interval Trees", "article", null ); // records OldTrees as history
        resolver = new WikiPathResolver( dao, "Main" );
    }

    private Optional< String > resolve( final String path ) {
        return resolver.canonicalIdFor( path );
    }

    @Test
    void currentSlugResolvesWithTrailingSlashAndPercentEncoding() {
        assertEquals( Optional.of( "01IT" ), resolve( "/wiki/IntervalTrees" ) );
        assertEquals( Optional.of( "01IT" ), resolve( "/wiki/IntervalTrees/" ) );
        assertEquals( Optional.of( "01IT" ), resolve( "/wiki/Interval%54rees" ) );
    }

    @Test
    void queryStringIsIgnored() {
        assertEquals( Optional.of( "01IT" ), resolve( "/wiki/IntervalTrees?x=1" ) );
    }

    @Test
    void rootIsTheFrontPage() {
        assertEquals( Optional.of( "01MAIN" ), resolve( "/" ) );
    }

    @Test
    void historicalSlugResolvesToTheRenamedPage() {
        assertEquals( Optional.of( "01IT" ), resolve( "/wiki/OldTrees" ) );
    }

    @Test
    void nonPagePathsAndUnknownSlugsAreEmpty() {
        assertEquals( Optional.empty(), resolve( "/search" ) );
        assertEquals( Optional.empty(), resolve( "/wiki/Nope" ) );
        assertEquals( Optional.empty(), resolve( "/login" ) );
        assertEquals( Optional.empty(), resolve( null ) );
    }
}
