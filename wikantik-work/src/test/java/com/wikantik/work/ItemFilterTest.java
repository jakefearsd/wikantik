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
package com.wikantik.work;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Type and label criteria of {@link ItemFilter}: normalised once, in the constructor. */
class ItemFilterTest {

    private static ItemFilter filter( final WorkType type, final Set< String > labels ) {
        return new ItemFilter( null, null, null, null, null, null, null, null, 0, null, null, type, labels, null );
    }

    @Test
    void labelsAreNormalised() {
        assertEquals( Set.of( "ui" ), filter( null, Set.of( " UI " ) ).labels() );
    }

    @Test
    void noLabelsIsAnEmptySet() {
        assertEquals( Set.of(), filter( null, null ).labels() );
        assertEquals( Set.of(), filter( null, Set.of() ).labels() );
    }

    @Test
    void aBadFilterLabelIsRefusedNamingLabels() {
        final WorkValidationException e = assertThrows( WorkValidationException.class,
            () -> filter( null, Set.of( "x'; drop table work_item; --" ) ) );
        assertEquals( "labels", e.field() );
    }

    @Test
    void elevenFilterLabelsAreRefused() {
        final Set< String > eleven = new HashSet<>();
        for ( int i = 0; i < 11; i++ ) {
            eleven.add( "l" + i );
        }
        assertEquals( "labels", assertThrows( WorkValidationException.class, () -> filter( null, eleven ) ).field() );
    }

    @Test
    void withAndWithQueueKeepTypeAndLabels() {
        final ItemFilter f = filter( WorkType.BUG, Set.of( "ui", "api" ) );
        for ( final ItemFilter g : List.of( f.with( 7L, "lena" ), f.withQueue( "q" ) ) ) {
            assertEquals( WorkType.BUG, g.type() );
            assertEquals( Set.of( "api", "ui" ), g.labels() );
        }
    }
}
