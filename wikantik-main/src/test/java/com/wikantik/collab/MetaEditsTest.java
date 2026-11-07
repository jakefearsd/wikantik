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
package com.wikantik.collab;

import com.wikantik.collab.model.MetaEdit;
import com.wikantik.collab.model.StoredMetaOp;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A14's edit rules in isolation: validation, set semantics, normalised comparison and request-order resolution. */
class MetaEditsTest {

    @Test
    void addAndRemoveTakeANonBlankString() {
        assertThrows( IllegalArgumentException.class, () -> MetaEdit.add( "tags", " " ) );
        assertThrows( IllegalArgumentException.class, () -> MetaEdit.remove( "tags", null ) );
        assertThrows( IllegalArgumentException.class, () -> new MetaEdit( "tags", MetaEdit.Kind.ADD, 3 ) );
        assertThrows( NullPointerException.class, () -> new MetaEdit( "tags", null, "x" ) );
        assertEquals( new MetaEdit( "summary", MetaEdit.Kind.SET, null ), MetaEdit.set( "summary", null ) );
    }

    @Test
    void invalidNamesTheProblemOrIsNull() {
        assertNull( MetaEdits.invalid( MetaEdit.set( "summary", "x" ) ) );
        assertNull( MetaEdits.invalid( MetaEdit.add( "aliases", "x" ) ) );
        assertNotNull( MetaEdits.invalid( MetaEdit.set( null, "x" ) ) );
        assertNotNull( MetaEdits.invalid( MetaEdit.set( "", "x" ) ) );
        assertNotNull( MetaEdits.invalid( MetaEdit.set( "__yaml__", "x" ) ) );
        assertNotNull( MetaEdits.invalid( MetaEdit.set( "x".repeat( 129 ), "x" ) ) );
        assertEquals( "add and remove are allowed only on tags, related and aliases",
            MetaEdits.invalid( MetaEdit.remove( "status", "x" ) ) );
    }

    @Test
    void resolveFollowsSetSemantics() {
        assertEquals( List.of( "x" ), MetaEdits.resolve( null, MetaEdit.add( "tags", "x" ) ), "absent is []" );
        assertEquals( List.of( "a", "x" ), MetaEdits.resolve( "a", MetaEdit.add( "tags", "x" ) ), "scalar is [a]" );
        assertEquals( "a", MetaEdits.resolve( "a", MetaEdit.add( "tags", "a" ) ), "already present: unchanged" );
        assertEquals( List.of(), MetaEdits.resolve( "a", MetaEdit.remove( "tags", "a" ) ) );
        assertEquals( List.of( "b" ), MetaEdits.resolve( List.of( "a", "b", "a" ), MetaEdit.remove( "tags", "a" ) ) );
        assertNull( MetaEdits.resolve( null, MetaEdit.remove( "tags", "a" ) ), "absent item: unchanged" );
        assertEquals( 3L, MetaEdits.resolve( "old", MetaEdit.set( "n", 3 ) ), "SET normalises" );
        assertEquals( "2026-01-15", MetaEdits.resolve( null,
            MetaEdit.set( "date", Date.from( Instant.parse( "2026-01-15T00:00:00Z" ) ) ) ) );
    }

    @Test
    void itemsCompareNormalised() {
        final Date d = Date.from( Instant.parse( "2026-01-15T00:00:00Z" ) );
        assertEquals( List.of( "2026-01-15" ), MetaEdits.resolve( List.of( d ), MetaEdit.add( "tags", "2026-01-15" ) ) );
        assertEquals( List.of(), MetaEdits.resolve( List.of( d ), MetaEdit.remove( "tags", "2026-01-15" ) ) );
    }

    @Test
    void opsResolveInRequestOrderAndDropNoOps() {
        final Map< String, Object > meta = new LinkedHashMap<>();
        meta.put( "tags", List.of( "a" ) );
        meta.put( "n", 3L );
        final List< StoredMetaOp > ops = MetaEdits.ops( meta, List.of( MetaEdit.add( "tags", "x" ),
            MetaEdit.add( "tags", "x" ), MetaEdit.set( "n", 3 ), MetaEdit.remove( "tags", "a" ),
            MetaEdit.set( "gone", null ) ), 7, "ada" );
        assertEquals( List.of( new StoredMetaOp( 8, "ada", "tags", List.of( "a", "x" ) ),
            new StoredMetaOp( 9, "ada", "tags", List.of( "x" ) ) ), ops );
        assertEquals( List.of( "a" ), meta.get( "tags" ), "the live meta is not touched" );
        assertTrue( MetaEdits.ops( meta, List.of(), 0, "ada" ).isEmpty() );
    }
}
