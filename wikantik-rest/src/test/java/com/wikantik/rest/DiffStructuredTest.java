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
package com.wikantik.rest;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** D31: structured diff helper. */
class DiffStructuredTest {

    @Test
    void identicalTextProducesEmptyDiff() {
        final Map< String, Object > diff = DiffResource.computeStructuredDiff( "alpha\nbeta", "alpha\nbeta" );
        @SuppressWarnings( "unchecked" )
        final List< String > added = (List< String >) diff.get( "added" );
        @SuppressWarnings( "unchecked" )
        final List< String > removed = (List< String >) diff.get( "removed" );
        assertTrue( added.isEmpty() );
        assertTrue( removed.isEmpty() );
    }

    @Test
    void addedAndRemovedLinesAreCaptured() {
        final Map< String, Object > diff = DiffResource.computeStructuredDiff(
                "alpha\nbeta\ngamma",
                "alpha\nbeta-changed\ngamma" );
        @SuppressWarnings( "unchecked" )
        final List< String > added = (List< String >) diff.get( "added" );
        @SuppressWarnings( "unchecked" )
        final List< String > removed = (List< String >) diff.get( "removed" );
        assertEquals( List.of( "beta-changed" ), added );
        assertEquals( List.of( "beta" ), removed );
    }

    @Test
    void resultDoesNotContainHtml() {
        final Map< String, Object > diff = DiffResource.computeStructuredDiff(
                "old line\n", "new line\n" );
        for ( final Object v : diff.values() ) {
            assertFalse( v.toString().contains( "<" ),
                    "Structured diff must not include HTML markup: " + v );
        }
    }
}
