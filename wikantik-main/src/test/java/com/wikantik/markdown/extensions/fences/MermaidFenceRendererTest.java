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

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class MermaidFenceRendererTest {

    private final MermaidFenceRenderer renderer = new MermaidFenceRenderer();

    @Test
    void matchesSharedPolicyFixture() throws Exception {
        final JsonObject fixture = JsonParser.parseString( Files.readString(
                Path.of( "..", "wikantik-frontend", "src", "utils", "__fixtures__", "renderedFences.json" ) ) ).getAsJsonObject();
        assertTrue( fixture.getAsJsonArray( "mermaidPolicy" ).size() >= 14 );
        for ( final JsonElement e : fixture.getAsJsonArray( "mermaidPolicy" ) ) {
            final JsonObject c = e.getAsJsonObject();
            final String source = c.get( "source" ).getAsString();
            final FenceVerdict v = renderer.check( source );
            assertEquals( c.get( "allowed" ).getAsBoolean(), v.allowed(), "allowed: " + source );
            assertEquals( c.get( "reason" ).isJsonNull() ? null : c.get( "reason" ).getAsString(), v.reason(), "reason: " + source );
            final Set< String > links = new HashSet<>();
            c.getAsJsonArray( "pageLinks" ).forEach( l -> links.add( l.getAsString() ) );
            assertEquals( links, renderer.pageLinks( source ), "pageLinks: " + source );
        }
    }

    @Test
    void refusedSourceReportsNoLinks() {
        final String src = "flowchart TD\n click A \"/wiki/Foo\"\n click B call f()";
        assertFalse( renderer.check( src ).allowed() );
        assertTrue( renderer.pageLinks( src ).isEmpty() );
    }

    @Test
    void kindIsBrowserAndRenderDrawingThrows() {
        assertEquals( "mermaid", renderer.language() );
        assertEquals( FenceKind.BROWSER, renderer.kind() );
        assertThrows( UnsupportedOperationException.class, () -> renderer.renderDrawing( "x" ) );
    }

    @Test
    void nullSourceIsHarmless() {
        assertTrue( renderer.check( null ).allowed() );
        assertTrue( renderer.pageLinks( null ).isEmpty() );
    }
}
