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

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class RenderedFenceRegistryTest {

    @Test
    void defaultsResolveMermaidCaseInsensitively() {
        final RenderedFenceRegistry r = RenderedFenceRegistry.defaults();
        assertSame( r, RenderedFenceRegistry.defaults() );
        assertTrue( r.forLanguage( "mermaid" ).isPresent() );
        assertTrue( r.forLanguage( "MERMAID" ).isPresent() );
        assertTrue( r.forInfo( FenceInfo.parse( "Mermaid" ) ).isPresent() );
    }

    @Test
    void defaultsHaveMermaidAndPlantuml() {
        final RenderedFenceRegistry r = RenderedFenceRegistry.defaults();
        assertEquals( FenceKind.BROWSER, r.forLanguage( "mermaid" ).orElseThrow().kind() );
        assertEquals( FenceKind.SERVER, r.forLanguage( "plantuml" ).orElseThrow().kind() );
        assertTrue( r.forInfo( FenceInfo.parse( "PlantUML" ) ).isPresent() );
    }

    @Test
    void exampleInfoResolvesToNothing() {
        assertTrue( RenderedFenceRegistry.defaults().forInfo( FenceInfo.parse( "mermaid example" ) ).isEmpty() );
    }

    @Test
    void unregisteredLanguageResolvesToNothing() {
        assertTrue( RenderedFenceRegistry.defaults().forInfo( FenceInfo.parse( "java" ) ).isEmpty() );
        assertTrue( RenderedFenceRegistry.defaults().forLanguage( null ).isEmpty() );
    }

    @Test
    void duplicateLanguageRejected() {
        assertThrows( IllegalArgumentException.class,
                () -> RenderedFenceRegistry.of( new MermaidFenceRenderer(), new MermaidFenceRenderer() ) );
    }

    @Test
    void customRendererRegistered() {
        final RenderedFenceRenderer fake = new RenderedFenceRenderer() {
            public String language() { return "fake"; }
            public FenceKind kind() { return FenceKind.SERVER; }
            public FenceVerdict check( final String s ) { return FenceVerdict.ALLOWED; }
            public Set< String > pageLinks( final String s ) { return Set.of(); }
        };
        assertTrue( RenderedFenceRegistry.of( fake ).forLanguage( "fake" ).isPresent() );
    }
}
