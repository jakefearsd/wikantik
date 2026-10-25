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

class PlantUmlFenceRendererTest {

    private final PlantUmlFenceRenderer renderer = new PlantUmlFenceRenderer();

    @Test
    void identity() {
        assertEquals( "plantuml", renderer.language() );
        assertEquals( FenceKind.SERVER, renderer.kind() );
    }

    @Test
    void allowsSimpleDiagram() {
        assertTrue( renderer.check( "Alice -> Bob : hi\n" ).allowed() );
    }

    @Test
    void refusesInclude() {
        final FenceVerdict v = renderer.check( "!include /etc/passwd\nA -> B\n" );
        assertFalse( v.allowed() );
        assertEquals( "include", v.reason() );
    }

    @Test
    void pageLinksFromWikiTargets() {
        final String src = "A -> B\nnote right of A : [[/wiki/PageOne]]\nnote left of B : [[/wiki/Page%20Two label]]\n";
        assertEquals( Set.of( "PageOne", "Page Two" ), renderer.pageLinks( src ) );
    }

    @Test
    void noLinksWhenRefused() {
        assertEquals( Set.of(), renderer.pageLinks( "!include x\nA -> B : [[/wiki/PageOne]]\n" ) );
    }

    @Test
    void checkNeverThrowsOnNull() {
        assertDoesNotThrow( () -> renderer.check( null ) );
        assertDoesNotThrow( () -> renderer.pageLinks( null ) );
    }
}
