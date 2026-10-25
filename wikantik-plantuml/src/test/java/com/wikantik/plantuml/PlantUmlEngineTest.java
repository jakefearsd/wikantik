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
package com.wikantik.plantuml;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlantUmlEngineTest {

    private static void assertSvg( final String svg ) {
        assertTrue( svg.startsWith( "<svg" ) || svg.startsWith( "<?xml" ), () -> "not svg: " + svg.substring( 0, Math.min( 80, svg.length() ) ) );
    }

    @Test
    void classDiagramDraws() {
        final RenderOutcome out = PlantUmlEngine.render(
            "interface Shape\nabstract class Base<T> implements Shape {\n  +T value\n}\nclass Circle extends Base\nCircle --> Shape : draws\n" );
        assertTrue( out.isDrawn(), () -> "reason=" + out.reason() + " msg=" + out.message() );
        assertSvg( out.drawing().lightSvg() );
        assertSvg( out.drawing().darkSvg() );
        assertNotEquals( out.drawing().lightSvg(), out.drawing().darkSvg() );
    }

    @Test
    void activityForkJoinDraws() {
        final RenderOutcome out = PlantUmlEngine.render( "start\nfork\n:a;\nfork again\n:b;\nend fork\nstop\n" );
        assertTrue( out.isDrawn(), () -> "reason=" + out.reason() + " msg=" + out.message() );
    }

    @Test
    void syntaxErrorReportsAuthorLine() {
        final RenderOutcome wrapped = PlantUmlEngine.render( "class A\nclass B\nclass {\nclass D" );
        assertFalse( wrapped.isDrawn() );
        assertEquals( "syntax-error", wrapped.reason() );
        assertEquals( 3, wrapped.line() );

        final RenderOutcome explicit = PlantUmlEngine.render( "@startuml\nclass A\nclass B\nclass {\n@enduml" );
        assertEquals( "syntax-error", explicit.reason() );
        assertEquals( 4, explicit.line() );
    }

    @Test
    void c4StdlibIncludeDraws() {
        final RenderOutcome out = PlantUmlEngine.render(
            "!include <C4/C4_Container>\nContainer(web, \"Web\", \"Java\", \"The site\")\n" );
        assertTrue( out.isDrawn(), () -> "reason=" + out.reason() + " msg=" + out.message() );
    }

    @Test
    void deepRecursionIsTooComplex() throws Exception {
        final StringBuilder sb = new StringBuilder();
        final int n = 300;
        for( int i = 0; i < n; i++ ) {
            sb.append( "class C" ).append( i ).append( " {\n + f" ).append( i ).append( " : int\n + m(x : String) : List<C" )
              .append( i ).append( ">\n}\n" );
        }
        for( int i = 1; i < n; i++ ) {
            sb.append( "C" ).append( ( i - 1 ) / 3 ).append( " <|-- C" ).append( i ).append( '\n' );
        }
        final java.util.Random r = new java.util.Random( 42 );
        for( int i = 0; i < 600; i++ ) {
            sb.append( 'C' ).append( r.nextInt( n ) ).append( " --> C" ).append( r.nextInt( n ) ).append( " : uses\n" );
        }
        final AtomicReference< RenderOutcome > result = new AtomicReference<>();
        final Thread t = new Thread( null, () -> result.set( PlantUmlEngine.render( sb.toString() ) ), "small-stack", 512 * 1024 );
        t.setDaemon( true );
        t.start();
        t.join( 120_000 );
        assertFalse( t.isAlive() );
        assertEquals( "too-complex", result.get().reason() );
    }
}
