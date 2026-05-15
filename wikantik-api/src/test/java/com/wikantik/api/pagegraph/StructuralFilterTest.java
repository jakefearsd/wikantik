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
package com.wikantik.api.pagegraph;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

class StructuralFilterTest {

    @Test
    void noneFactoryHasNoConstraintsAndDefaultLimit() {
        final StructuralFilter f = StructuralFilter.none();
        assertTrue( f.type().isEmpty() );
        assertTrue( f.cluster().isEmpty() );
        assertTrue( f.tags().isEmpty() );
        assertTrue( f.updatedSince().isEmpty() );
        assertTrue( f.cursor().isEmpty() );
        assertEquals( 100, f.limit() );
    }

    @Test
    void compactConstructorNormalizesNullsToEmpties() {
        final StructuralFilter f = new StructuralFilter( null, null, null, null, 50, null );
        assertTrue( f.type().isEmpty() );
        assertTrue( f.cluster().isEmpty() );
        assertTrue( f.tags().isEmpty() );
        assertTrue( f.updatedSince().isEmpty() );
        assertTrue( f.cursor().isEmpty() );
        assertEquals( 50, f.limit() );
    }

    @Test
    void limitIsClampedToOneThousandAndFlooredAtDefault() {
        assertEquals( 1000, new StructuralFilter( null, null, null, null, 5000, null ).limit() );
        assertEquals( 100, new StructuralFilter( null, null, null, null, 0, null ).limit() );
        assertEquals( 100, new StructuralFilter( null, null, null, null, -7, null ).limit() );
        assertEquals( 1000, new StructuralFilter( null, null, null, null, 1000, null ).limit() );
    }

    @Test
    void tagsAreDefensivelyCopiedAndImmutable() {
        final List< String > mutable = new ArrayList<>( List.of( "a", "b" ) );
        final StructuralFilter f = new StructuralFilter( null, null, mutable, null, 10, null );
        mutable.add( "c" );
        assertEquals( List.of( "a", "b" ), f.tags() );
        assertThrows( UnsupportedOperationException.class, () -> f.tags().add( "x" ) );
    }

    @Test
    void preservesSuppliedValues() {
        final Instant when = Instant.parse( "2026-01-01T00:00:00Z" );
        final StructuralFilter f = new StructuralFilter(
                Optional.of( PageType.HUB ), Optional.of( "Reference" ),
                List.of( "tag1" ), Optional.of( when ), 25, Optional.of( "cur-42" ) );
        assertEquals( PageType.HUB, f.type().orElseThrow() );
        assertEquals( "Reference", f.cluster().orElseThrow() );
        assertEquals( List.of( "tag1" ), f.tags() );
        assertEquals( when, f.updatedSince().orElseThrow() );
        assertEquals( 25, f.limit() );
        assertEquals( "cur-42", f.cursor().orElseThrow() );
    }
}
