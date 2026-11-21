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

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LabelsTest {

    private static final String SHAPE = "A label is 1 to 40 lowercase letters, digits, dots, dashes or underscores, "
        + "starting with a letter or digit: ";

    private static WorkValidationException refused( final List<String> raw ) {
        return assertThrows( WorkValidationException.class, () -> Labels.normalise( raw ) );
    }

    @Test
    void trimsLowerCasesDeduplicatesAndSorts() {
        assertEquals( List.of( "billing", "ui" ), Labels.normalise( List.of( " UI ", "ui", "Billing" ) ) );
    }

    @Test
    void nullAndEmptyAreNoLabels() {
        assertEquals( List.of(), Labels.normalise( null ) );
        assertEquals( List.of(), Labels.normalise( List.of() ) );
    }

    @Test
    void theResultIsUnmodifiable() {
        final List<String> out = Labels.normalise( List.of( "ui" ) );
        assertThrows( UnsupportedOperationException.class, () -> out.add( "x" ) );
    }

    @Test
    void aLabelMayUseDotsDashesAndUnderscores() {
        assertEquals( List.of( "front-end", "needs_design", "v2.1" ),
            Labels.normalise( List.of( "v2.1", "front-end", "needs_design" ) ) );
    }

    @Test
    void aLabelSpelledNullIsAnOrdinaryLabel() {
        assertEquals( List.of( "null" ), Labels.normalise( List.of( "NULL" ) ) );
    }

    @Test
    void refusesASpace() {
        final WorkValidationException e = refused( List.of( "a b" ) );
        assertEquals( "labels", e.field() );
        assertEquals( SHAPE + "a b", e.getMessage() );
    }

    @Test
    void refusesEmpty() {
        final WorkValidationException e = refused( List.of( "" ) );
        assertEquals( "labels", e.field() );
        assertTrue( e.getMessage().startsWith( SHAPE ) );
    }

    @Test
    void refusesALeadingDash() {
        final WorkValidationException e = refused( List.of( "-x" ) );
        assertEquals( "labels", e.field() );
        assertEquals( SHAPE + "-x", e.getMessage() );
    }

    @Test
    void refusesFortyOneCharacters() {
        final String label = "a".repeat( 41 );
        final WorkValidationException e = refused( List.of( label ) );
        assertEquals( "labels", e.field() );
        assertEquals( SHAPE + label, e.getMessage() );
    }

    @Test
    void fortyCharactersIsAllowed() {
        final String label = "a".repeat( 40 );
        assertEquals( List.of( label ), Labels.normalise( List.of( label ) ) );
    }

    @Test
    void refusesANullElement() {
        final WorkValidationException e = refused( Arrays.asList( "ui", null ) );
        assertEquals( "labels", e.field() );
        assertEquals( SHAPE + "null", e.getMessage() );
    }

    @Test
    void theRefusedElementIsTruncatedToFiftyCharacters() {
        final String label = "B".repeat( 80 );
        final WorkValidationException e = refused( List.of( label ) );
        assertEquals( SHAPE + "B".repeat( 50 ), e.getMessage() );
    }

    @Test
    void elevenDistinctLabelsAreRefused() {
        final List<String> raw = List.of( "l1", "l2", "l3", "l4", "l5", "l6", "l7", "l8", "l9", "l10", "l11" );
        final WorkValidationException e = refused( raw );
        assertEquals( "labels", e.field() );
        assertEquals( "An item has at most 10 labels.", e.getMessage() );
    }

    @Test
    void elevenThatCollapseToTenAreAllowed() {
        final List<String> raw = List.of( "a", "A", "b", "c", "d", "e", "f", "g", "h", "i", "j" );
        assertEquals( 10, Labels.normalise( raw ).size() );
    }
}
