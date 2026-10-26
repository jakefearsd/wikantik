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

import net.sourceforge.plantuml.dot.GraphvizRuntimeEnvironment;
import org.junit.jupiter.api.Test;

import java.io.File;
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
    void saltWireframeDraws() {
        final RenderOutcome out = PlantUmlEngine.render( "@startsalt\n{\n  Login | \"MyName   \"\n  [Cancel] | [  OK   ]\n}\n@endsalt\n" );
        assertTrue( out.isDrawn(), () -> "reason=" + out.reason() + " msg=" + out.message() );
        assertSvg( out.drawing().lightSvg() );
        assertTrue( out.drawing().lightSvg().contains( "Login" ) );
        assertNotEquals( out.drawing().lightSvg(), out.drawing().darkSvg() );
    }

    @Test
    void nwdiagDraws() {
        final RenderOutcome out = PlantUmlEngine.render(
            "@startnwdiag\nnwdiag {\n  network dmz {\n    address = \"210.x.x.x/24\"\n    web01 [address = \"210.x.x.1\"];\n    web02;\n  }\n}\n@endnwdiag\n" );
        assertTrue( out.isDrawn(), () -> "reason=" + out.reason() + " msg=" + out.message() );
        assertTrue( out.drawing().lightSvg().contains( "web01" ) );
    }

    @Test
    void brokenSaltIsRefusedNotDrawn() {
        final RenderOutcome out = PlantUmlEngine.render( "@startsalt\n{\n  Login\n  {{{{ ]]\n@endsalt\n" );
        // Salt has no parse-error diagram: PlantUML draws its crash image, which is refused, never shown.
        assertFalse( out.isDrawn() );
        assertEquals( PlantUmlService.RENDER_CRASHED, out.reason() );
    }

    @Test
    void emptySaltIsRefusedNotDrawn() {
        assertEquals( "syntax-error", PlantUmlEngine.render( "@startsalt\n@endsalt\n" ).reason() );
        assertEquals( "syntax-error", PlantUmlEngine.render( "@startnwdiag\n\n@endnwdiag" ).reason() );
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

    /**
     * An embedded {@code {{ }}} sub-diagram does not inherit the forced Smetana pragma, so PlantUML would lay it out
     * with the Graphviz binary. The policy refuses it ({@code embedded-diagram}); this pins the second line of defence
     * with the policy bypassed: PlantUML's resolved {@code dot} executable does not exist, and the sub-diagram's nested
     * {@code image/svg+xml} image never survives the scrub.
     */
    @Test
    void embeddedSubDiagramCannotRunGraphvizAndItsImageIsScrubbed() throws Exception {
        final RenderOutcome out = PlantUmlEngine.render( "Alice -> Bob\nnote right\n{{\nclass A\nclass B\nA <|-- B\n}}\nend note" );

        final File dot = GraphvizRuntimeEnvironment.getInstance().getDotExe();
        assertTrue( dot == null || !dot.exists(), () -> "PlantUML would execute " + dot );
        assertEquals( PlantUmlSandbox.NO_GRAPHVIZ, System.getProperty( "GRAPHVIZ_DOT" ) );
        assertTrue( out.isDrawn(), () -> "reason=" + out.reason() + " msg=" + out.message() );
        assertTrue( out.drawing().lightSvg().contains( "data:image/svg+xml;base64," ), "the raw drawing nests the sub-diagram" );
        final String scrubbed = SvgScrubber.scrub( out.drawing().lightSvg(), "p-" );
        assertFalse( scrubbed.contains( "<image" ), scrubbed );
        assertFalse( scrubbed.contains( "svg+xml" ), scrubbed );
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

    @org.junit.jupiter.api.Test
    void crashStatusIsRefusedEvenWithoutMarkerText() {
        final RenderOutcome out = PlantUmlEngine.crashOutcome( new PlantUmlEngine.Export( "<svg/>", 503 ) );
        assertFalse( out.transientFailure(), "a PlantUML crash is deterministic for its source" );
        assertEquals( "render-crashed", out.reason() );
    }

    /** An SVG sprite makes PlantUML 1.2026.8 crash (an NPE inside its SVG sprite parser) on every render. */
    @Test
    void deterministicPlantUmlCrashIsRenderCrashed() {
        final RenderOutcome out = PlantUmlEngine.render( "sprite $foo <svg viewBox=\"0 0 10 10\"><circle r=\"5\"/></svg>\nA -> B : <$foo>" );
        assertFalse( out.isDrawn() );
        assertEquals( "render-crashed", out.reason() );
        assertFalse( out.transientFailure() );
    }

    @org.junit.jupiter.api.Test
    void crashTextIsRefusedEvenWithZeroStatus() {
        final String svg = "<svg>An error has occurred : java.lang.StackOverflowError PlantUML (1) has crashed.</svg>";
        assertEquals( "too-complex", PlantUmlEngine.crashOutcome( new PlantUmlEngine.Export( svg, 0 ) ).reason() );
    }

    @org.junit.jupiter.api.Test
    void healthyExportIsNotACrash() {
        assertEquals( null, PlantUmlEngine.crashOutcome( new PlantUmlEngine.Export( "<svg/>", 0 ) ) );
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource( strings = { "", "   \n ", "@startuml\n@enduml", "version", "license", "donors", "listfonts",
                                                                "colors", "stdlib", "emoji all", "skinparameters", "charlie" } )
    void infoAndBlankDiagramsAreNeverDrawn( final String source ) {
        final RenderOutcome out = PlantUmlEngine.render( source );
        assertFalse( out.isDrawn(), () -> "drawn: " + source );
        assertTrue( out.reason().equals( "syntax-error" ) || out.reason().equals( "unsupported-diagram" ), out.reason() );
    }

    @Test
    void infoDiagramReasonIsUnsupported() {
        assertEquals( "unsupported-diagram", PlantUmlEngine.render( "version" ).reason() );
        assertEquals( "unsupported-diagram", PlantUmlEngine.render( "license" ).reason() );
        assertEquals( "syntax-error", PlantUmlEngine.render( "   " ).reason() );
        assertEquals( 0, PlantUmlEngine.render( "   " ).line() );
    }
}
