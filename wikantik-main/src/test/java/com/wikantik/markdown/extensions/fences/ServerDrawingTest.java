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
package com.wikantik.markdown.extensions.fences;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServerDrawingTest {

    @Test
    void drawnCarriesBothVariantsAndNoRefusal() {
        final ServerDrawing d = ServerDrawing.drawn( "<svg>l</svg>", "<svg>d</svg>" );
        assertTrue( d.isDrawn() );
        assertNull( d.reason() );
        assertNull( d.message() );
        assertEquals( 0, d.line() );
        assertFalse( d.transientFailure() );
    }

    @Test
    void refusedCarriesTheReasonAndNoSvg() {
        final ServerDrawing d = ServerDrawing.refused( "syntax-error", "bad", 4, true );
        assertFalse( d.isDrawn() );
        assertNull( d.lightSvg() );
        assertNull( d.darkSvg() );
        assertEquals( "syntax-error", d.reason() );
        assertEquals( "bad", d.message() );
        assertEquals( 4, d.line() );
        assertTrue( d.transientFailure() );
    }

    @Test
    void aDrawingMissingEitherVariantIsNotDrawn() {
        assertFalse( new ServerDrawing( "<svg/>", null, null, null, 0, false ).isDrawn() );
        assertFalse( new ServerDrawing( null, "<svg/>", null, null, 0, false ).isDrawn() );
        assertFalse( new ServerDrawing( null, null, null, null, 0, false ).isDrawn() );
        assertTrue( new ServerDrawing( "", "", null, null, 0, false ).isDrawn(), "empty text is still present" );
    }
}
