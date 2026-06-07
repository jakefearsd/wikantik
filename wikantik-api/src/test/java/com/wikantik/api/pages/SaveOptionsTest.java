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
package com.wikantik.api.pages;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SaveOptionsTest {

    @Test
    void builderDefaultsAreEmpty() {
        final SaveOptions o = SaveOptions.builder().build();
        assertNull( o.author() );
        assertNull( o.changeNote() );
        assertNull( o.markupSyntax() );
        assertEquals( -1, o.expectedVersion() );
        assertNull( o.expectedContentHash() );
        assertNull( o.metadata() );
        assertFalse( o.replaceMetadata() );
    }

    @Test
    void builderCarriesEveryField() {
        final SaveOptions o = SaveOptions.builder()
            .author( "alice" )
            .changeNote( "fix typo" )
            .markupSyntax( "markdown" )
            .expectedVersion( 9 )
            .expectedContentHash( "deadbeef" )
            .metadata( Map.of( "k", "v" ) )
            .replaceMetadata( true )
            .build();

        assertEquals( "alice", o.author() );
        assertEquals( "fix typo", o.changeNote() );
        assertEquals( "markdown", o.markupSyntax() );
        assertEquals( 9, o.expectedVersion() );
        assertEquals( "deadbeef", o.expectedContentHash() );
        assertEquals( Map.of( "k", "v" ), o.metadata() );
        assertTrue( o.replaceMetadata() );
    }

    @Test
    void nullMetadataIsPreservedDistinctFromEmpty() {
        // PageSaveHelper relies on null ("not provided") vs empty ("provided, clear it").
        assertNull( SaveOptions.builder().metadata( null ).build().metadata() );
        assertEquals( Map.of(), SaveOptions.builder().metadata( Map.of() ).build().metadata() );
    }

    @Test
    void metadataIsDefensivelyCopiedFromCaller() {
        final Map<String, Object> src = new HashMap<>();
        src.put( "k", "v" );

        final SaveOptions o = SaveOptions.builder().metadata( src ).build();
        src.put( "injected", "after-build" );   // mutate caller's map post-build

        assertEquals( 1, o.metadata().size() );
        assertFalse( o.metadata().containsKey( "injected" ) );
    }

    @Test
    void metadataMapIsImmutable() {
        final SaveOptions o = SaveOptions.builder().metadata( Map.of( "k", "v" ) ).build();
        assertThrows( UnsupportedOperationException.class, () -> o.metadata().put( "x", "y" ) );
    }

    @Test
    void canonicalConstructorAlsoDefensivelyCopies() {
        final Map<String, Object> src = new HashMap<>();
        src.put( "k", "v" );

        final SaveOptions o = new SaveOptions( "a", null, null, -1, null, src, false );
        src.put( "injected", "x" );

        assertFalse( o.metadata().containsKey( "injected" ) );
        assertThrows( UnsupportedOperationException.class, () -> o.metadata().put( "z", "1" ) );
    }
}
