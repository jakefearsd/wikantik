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
package com.wikantik.api.briefing;

import com.wikantik.api.bundle.BundleCoverage;
import com.wikantik.api.querylog.SourceSurface;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BriefingTypesTest {

    @Test
    void scopeModeFromWire() {
        assertEquals( ScopeMode.PREFER, ScopeMode.fromWire( null ) );
        assertEquals( ScopeMode.PREFER, ScopeMode.fromWire( "  " ) );
        assertEquals( ScopeMode.PREFER, ScopeMode.fromWire( "prefer" ) );
        assertEquals( ScopeMode.STRICT, ScopeMode.fromWire( "STRICT" ) );
        assertThrows( IllegalArgumentException.class, () -> ScopeMode.fromWire( "bogus" ) );
    }

    @Test
    void requestDefaultsAndHasAnySource() {
        final BriefingRequest empty = new BriefingRequest( null, null, null, null, null );
        assertEquals( List.of(), empty.pins() );
        assertEquals( List.of(), empty.clusters() );
        assertEquals( ScopeMode.PREFER, empty.scopeMode() );
        assertFalse( empty.hasAnySource() );
        assertTrue( new BriefingRequest( List.of( "A" ), null, null, null, null ).hasAnySource() );
        assertTrue( new BriefingRequest( null, List.of( "c" ), null, null, null ).hasAnySource() );
        assertTrue( new BriefingRequest( null, null, "why?", null, null ).hasAnySource() );
        assertFalse( new BriefingRequest( null, null, "  ", null, null ).hasAnySource() );
    }

    @Test
    void itemRequiresSlugAndModelsPointer() {
        assertThrows( IllegalArgumentException.class,
            () -> new BriefingItem( " ", null, "t", "s", "pin", true, "body" ) );
        final BriefingItem ptr = new BriefingItem( "Q3Goals", "01Q3", "Q3 Goals", "sum", "cluster", false, null );
        assertFalse( ptr.included() );
        assertNull( ptr.content() );
    }

    @Test
    void briefingDefaultsNullCollections() {
        final ContextBriefing b = new ContextBriefing( null, null, null, null, null, 6000, 0 );
        assertEquals( List.of(), b.sections() );
        assertEquals( List.of(), b.items() );
        assertEquals( List.of(), b.warnings() );
        assertEquals( BundleCoverage.empty(), b.coverage() );
    }

    @Test
    void sourceSurfaceWireValues() {
        assertEquals( "api_briefing", SourceSurface.API_BRIEFING.wire() );
        assertEquals( "mcp_get_briefing", SourceSurface.MCP_GET_BRIEFING.wire() );
        assertEquals( SourceSurface.API_BRIEFING, SourceSurface.fromWire( "api_briefing" ) );
    }
}
