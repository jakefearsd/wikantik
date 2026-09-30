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
package com.wikantik.export;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.wikantik.api.pagegraph.PageType;

import static org.junit.jupiter.api.Assertions.*;

class ExportSelectionTest {
    @Test void hopsBelowRangeThrows() {
        final IllegalArgumentException ex = assertThrows( IllegalArgumentException.class,
                () -> new ExportSelection( List.of(), true, List.of(), Optional.empty(), Optional.empty(), -1, UnresolvedLinkMode.KEEP ) );
        assertEquals( "hops must be 0-2", ex.getMessage() );
    }

    @Test void hopsAboveRangeThrows() {
        final IllegalArgumentException ex = assertThrows( IllegalArgumentException.class,
                () -> new ExportSelection( List.of(), true, List.of(), Optional.empty(), Optional.empty(), 3, UnresolvedLinkMode.KEEP ) );
        assertEquals( "hops must be 0-2", ex.getMessage() );
    }

    @Test void hopsAtBoundsAreAccepted() {
        assertEquals( 0, new ExportSelection( List.of(), true, List.of(), Optional.empty(), Optional.empty(), 0, UnresolvedLinkMode.KEEP ).hops() );
        assertEquals( ExportSelection.MAX_HOPS, new ExportSelection( List.of(), true, List.of(), Optional.empty(), Optional.empty(), ExportSelection.MAX_HOPS, UnresolvedLinkMode.KEEP ).hops() );
    }

    @Test void nullListsNormaliseToEmpty() {
        final ExportSelection sel = new ExportSelection( null, true, null, null, null, 0, null );
        assertEquals( List.of(), sel.clusters() );
        assertEquals( List.of(), sel.tags() );
        assertEquals( Optional.empty(), sel.type() );
        assertEquals( Optional.empty(), sel.status() );
        assertEquals( UnresolvedLinkMode.KEEP, sel.unresolved() );
    }

    @Test void blankClusterAndTagEntriesAreDropped() {
        final ExportSelection sel = new ExportSelection( Arrays.asList( "finance", "  ", "", null, " math " ), true,
                Arrays.asList( "tax", " ", "" ), Optional.empty(), Optional.empty(), 0, UnresolvedLinkMode.KEEP );
        assertEquals( List.of( "finance", "math" ), sel.clusters() );
        assertEquals( List.of( "tax" ), sel.tags() );
    }

    @Test void hasFiltersReflectsAnyFilterPresence() {
        assertFalse( new ExportSelection( List.of(), true, List.of(), Optional.empty(), Optional.empty(), 0, UnresolvedLinkMode.KEEP ).hasFilters() );
        assertTrue( new ExportSelection( List.of( "finance" ), true, List.of(), Optional.empty(), Optional.empty(), 0, UnresolvedLinkMode.KEEP ).hasFilters() );
        assertTrue( new ExportSelection( List.of(), true, List.of( "tax" ), Optional.empty(), Optional.empty(), 0, UnresolvedLinkMode.KEEP ).hasFilters() );
        assertTrue( new ExportSelection( List.of(), true, List.of(), Optional.of( PageType.HUB ), Optional.empty(), 0, UnresolvedLinkMode.KEEP ).hasFilters() );
        assertTrue( new ExportSelection( List.of(), true, List.of(), Optional.empty(), Optional.of( "draft" ), 0, UnresolvedLinkMode.KEEP ).hasFilters() );
    }
}
