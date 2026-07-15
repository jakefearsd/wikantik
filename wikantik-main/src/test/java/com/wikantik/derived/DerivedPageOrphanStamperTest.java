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
package com.wikantik.derived;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class DerivedPageOrphanStamperTest {

    private static final String AUTHOR = "connector-sync";

    private Map< String, Object > pageReaderResult = null;   // null = page absent

    private final DerivedPageIngestionService.PageReader pageReader =
        pageName -> Optional.ofNullable( pageReaderResult );

    private final java.util.function.Function< String, String > bodyReader =
        pageName -> "unchanged-body";

    private final List< String > writtenPageNames = new ArrayList<>();
    private final List< String > writtenBodies = new ArrayList<>();
    private final List< Map< String, Object > > writtenMetadata = new ArrayList<>();
    private final List< String > writtenAuthors = new ArrayList<>();

    private final DerivedPageIngestionService.PageWriter pageWriter =
        ( pageName, body, metadata, author ) -> {
            writtenPageNames.add( pageName );
            writtenBodies.add( body );
            writtenMetadata.add( new HashMap<>( metadata ) );
            writtenAuthors.add( author );
        };

    private final DerivedPageOrphanStamper stamper =
        new DerivedPageOrphanStamper( pageReader, bodyReader, pageWriter, AUTHOR );

    @Test void stampsDerivedPage() {
        pageReaderResult = new HashMap<>( Map.of( "derived_from", "x" ) );

        stamper.accept( "PageA" );

        assertEquals( 1, writtenPageNames.size() );
        assertEquals( "PageA", writtenPageNames.get( 0 ) );
        assertEquals( "unchanged-body", writtenBodies.get( 0 ) );          // body passes through untouched
        assertEquals( Boolean.TRUE, writtenMetadata.get( 0 ).get( "derived_orphaned" ) );
        assertEquals( "x", writtenMetadata.get( 0 ).get( "derived_from" ) );  // existing metadata preserved
        assertEquals( AUTHOR, writtenAuthors.get( 0 ) );
    }

    @Test void skipsNonDerivedPage() {
        pageReaderResult = new HashMap<>( Map.of() );   // present, but no derived_from

        stamper.accept( "PageB" );

        assertTrue( writtenPageNames.isEmpty(), "writer must not be called for a non-derived page" );
    }

    @Test void skipsMissingPage() {
        pageReaderResult = null;   // page absent

        stamper.accept( "PageC" );

        assertTrue( writtenPageNames.isEmpty(), "writer must not be called for a missing page" );
    }
}
