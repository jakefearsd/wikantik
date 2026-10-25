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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class RenderedFenceScannerTest {

    @Test
    void agreesWithFlexmarkOnDocumentsFixture() throws Exception {
        final var docs = JsonParser.parseString( Files.readString(
                Path.of( "..", "wikantik-frontend", "src", "utils", "__fixtures__", "renderedFences.json" ) ) )
                .getAsJsonObject().getAsJsonArray( "documents" );
        assertFalse( docs.isEmpty() );
        final RenderedFenceRegistry reg = RenderedFenceRegistry.defaults();
        for ( final JsonElement e : docs ) {
            final JsonObject c = e.getAsJsonObject();
            final List< String > got = new ArrayList<>();
            for ( final ScannedFence f : RenderedFenceScanner.scan( c.get( "markdown" ).getAsString() ) ) {
                if ( reg.forInfo( f.info() ).isPresent() ) {
                    got.add( f.info().language() + "|" + f.source() );
                }
            }
            final List< String > want = new ArrayList<>();
            for ( final JsonElement f : c.getAsJsonArray( "fences" ) ) {
                want.add( f.getAsJsonObject().get( "language" ).getAsString() + "|"
                        + f.getAsJsonObject().get( "source" ).getAsString() );
            }
            assertEquals( want, got, c.get( "name" ).getAsString() );
        }
    }

    @Test
    void scanReturnsEveryFenceAnyInfoInOrder() {
        final List< ScannedFence > f = RenderedFenceScanner.scan( "```java\nint x;\n```\n\n~~~mermaid example\nA\n~~~\n" );
        assertEquals( 2, f.size() );
        assertEquals( "java", f.get( 0 ).info().language() );
        assertTrue( f.get( 1 ).info().example() );
    }

    @Test
    void nestedListAndBacktickInfoRules() {
        assertEquals( "a\n", RenderedFenceScanner.scan( "1. x\n   - y\n\n     ```mermaid\n     a\n     ```\n" ).get( 0 ).source() );
        assertTrue( RenderedFenceScanner.scan( "``` a`b\nx\n" ).isEmpty() );
        assertEquals( "x\n", RenderedFenceScanner.scan( "- ```mermaid\n  x\n  ```\n" ).get( 0 ).source() );
    }

    @Test
    void pageLinksFromDiagramClicks() {
        final String body = "```mermaid\nflowchart TD\n  A-->B\n  click A \"/wiki/TargetPage\"\n```\n";
        assertEquals( Set.of( "TargetPage" ), RenderedFenceScanner.pageLinks( body, RenderedFenceRegistry.defaults() ) );
    }

    @Test
    void exampleAndRefusedFencesContributeNoLinks() {
        final String ex = "```mermaid example\nflowchart TD\n  A-->B\n  click A \"/wiki/TargetPage\"\n```\n";
        assertTrue( RenderedFenceScanner.pageLinks( ex, RenderedFenceRegistry.defaults() ).isEmpty() );
        final String refused = "```mermaid\nflowchart TD\n  A-->B\n  click A \"/wiki/TargetPage\"\n  click B \"javascript:alert(1)\"\n```\n";
        assertTrue( RenderedFenceScanner.pageLinks( refused, RenderedFenceRegistry.defaults() ).isEmpty() );
        assertTrue( RenderedFenceScanner.pageLinks( "no fences here", RenderedFenceRegistry.defaults() ).isEmpty() );
    }

    @Test
    void linearOnPathologicalInput() {
        final StringBuilder sb = new StringBuilder();
        for ( int i = 0; i < 200_000; i++ ) {
            sb.append( i % 2 == 0 ? "```mermaid\n" : "```\n" );
        }
        final String body = sb.toString();
        org.junit.jupiter.api.Assertions.assertTimeout( Duration.ofSeconds( 1 ), () -> RenderedFenceScanner.scan( body ) );
    }
}
