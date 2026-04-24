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
package com.wikantik.knowledge.mcp;

import com.wikantik.api.knowledge.Provenance;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the Gson + argument-parsing helpers in
 * {@link KnowledgeMcpUtils}. These paths are exercised indirectly by the
 * tool tests, but direct coverage here pins the invariants (null safety,
 * ISO-8601 round-trip, provenance parsing).
 */
class KnowledgeMcpUtilsTest {

    @Test
    void instantAdapter_roundTripsIsoString() {
        final Instant value = Instant.parse( "2026-04-24T12:00:00Z" );
        final String json = KnowledgeMcpUtils.GSON.toJson( value );
        assertEquals( "\"2026-04-24T12:00:00Z\"", json );
        final Instant back = KnowledgeMcpUtils.GSON.fromJson( json, Instant.class );
        assertEquals( value, back );
    }

    @Test
    void instantAdapter_writesNullAsJsonNull() {
        final String json = KnowledgeMcpUtils.GSON.toJson( null, Instant.class );
        assertEquals( "null", json );
    }

    @Test
    void instantAdapter_readsNullFromJsonNull() {
        assertNull( KnowledgeMcpUtils.GSON.fromJson( "null", Instant.class ) );
    }

    @Test
    void parseProvenanceFilter_returnsNullForMissingArg() {
        assertNull( KnowledgeMcpUtils.parseProvenanceFilter( Map.of() ) );
    }

    @Test
    void parseProvenanceFilter_returnsNullForEmptyList() {
        final Map< String, Object > args = new HashMap<>();
        args.put( "provenance_filter", List.of() );
        assertNull( KnowledgeMcpUtils.parseProvenanceFilter( args ) );
    }

    @Test
    void parseProvenanceFilter_returnsNullForNonListValue() {
        final Map< String, Object > args = new HashMap<>();
        args.put( "provenance_filter", "human-authored" );
        assertNull( KnowledgeMcpUtils.parseProvenanceFilter( args ),
            "non-list values are treated as absent rather than throwing" );
    }

    @Test
    void parseProvenanceFilter_parsesWireValues() {
        final Map< String, Object > args = new HashMap<>();
        args.put( "provenance_filter", List.of( "human-authored", "ai-reviewed" ) );
        final Set< Provenance > result = KnowledgeMcpUtils.parseProvenanceFilter( args );
        assertEquals( Set.of( Provenance.HUMAN_AUTHORED, Provenance.AI_REVIEWED ), result );
    }

    @Test
    void parseProvenanceFilter_rejectsUnknownValue() {
        final Map< String, Object > args = new HashMap<>();
        args.put( "provenance_filter", List.of( "bogus-wire-value" ) );
        assertThrows( IllegalArgumentException.class,
            () -> KnowledgeMcpUtils.parseProvenanceFilter( args ) );
    }

    @Test
    void asString_nullReturnsNull() {
        assertNull( KnowledgeMcpUtils.asString( null ) );
    }

    @Test
    void asString_nonNullReturnsToString() {
        assertEquals( "42", KnowledgeMcpUtils.asString( 42 ) );
        assertEquals( "hello", KnowledgeMcpUtils.asString( "hello" ) );
    }

    @Test
    void asStringList_nullReturnsNull() {
        assertNull( KnowledgeMcpUtils.asStringList( null ) );
    }

    @Test
    void asStringList_nonListReturnsNull() {
        assertNull( KnowledgeMcpUtils.asStringList( "not-a-list" ) );
    }

    @Test
    void asStringList_coercesAndStripsNulls() {
        final List< String > out = KnowledgeMcpUtils.asStringList( Arrays.asList( "a", null, 7 ) );
        assertEquals( List.of( "a", "7" ), out );
    }

    @Test
    void asInstant_nullReturnsNull() {
        assertNull( KnowledgeMcpUtils.asInstant( null ) );
    }

    @Test
    void asInstant_nonStringReturnsNull() {
        assertNull( KnowledgeMcpUtils.asInstant( 42 ) );
    }

    @Test
    void asInstant_blankReturnsNull() {
        assertNull( KnowledgeMcpUtils.asInstant( "   " ) );
    }

    @Test
    void asInstant_parsesIsoValue() {
        assertEquals( Instant.parse( "2026-04-24T00:00:00Z" ),
            KnowledgeMcpUtils.asInstant( "2026-04-24T00:00:00Z" ) );
    }

    @Test
    void asInstant_rejectsInvalidString() {
        assertThrows( IllegalArgumentException.class,
            () -> KnowledgeMcpUtils.asInstant( "not-a-date" ) );
    }
}
