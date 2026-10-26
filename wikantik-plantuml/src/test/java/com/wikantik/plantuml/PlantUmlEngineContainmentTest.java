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

import net.sourceforge.plantuml.BlockUml;
import net.sourceforge.plantuml.SourceStringReader;
import net.sourceforge.plantuml.TitledDiagram;
import net.sourceforge.plantuml.core.ImageData;
import net.sourceforge.plantuml.dot.GraphvizRuntimeEnvironment;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import org.mockito.Mockito;

import java.io.File;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;

/**
 * Engine paths that real PlantUML sources cannot reach deterministically (renderer failures, a crash in only one colour
 * variant) via a mocked PlantUML front door, plus the containment the {@link PlantUmlPolicy} Javadoc relies on: sources
 * that slip past the policy still never run Graphviz and never yield unscrubbed script-capable output.
 */
class PlantUmlEngineContainmentTest {

    private static void assertGraphvizCannotRun() {
        final File dot = GraphvizRuntimeEnvironment.getInstance().getDotExe();
        assertTrue( dot == null || !dot.exists(), () -> "PlantUML would execute " + dot );
        assertEquals( PlantUmlSandbox.NO_GRAPHVIZ, System.getProperty( "GRAPHVIZ_DOT" ) );
    }

    @Test
    void preprocessorBuiltOpenBraceNeverRunsGraphvizAndNeverYieldsUnsafeSvg() {
        final String[] builtSources = {
            "class A\nnote right of A: %chr(123)%chr(123)\nclass B\nA <|-- B\n%chr(125)%chr(125)\nend note",
            "!$o = \"{\"\n!$c = \"}\"\nclass A\nnote right of A\n$o$o\nclass B\nA <|-- B\n$c$c\nend note",
            "!define OPEN {\n!define CLOSE }\nAlice -> Bob\nnote right\nOPENOPEN\nclass A\nclass B\nA <|-- B\nCLOSECLOSE\nend note" };
        for( final String source : builtSources ) {
            assertTrue( PlantUmlPolicy.analyse( source ).allowed(), "premise: the policy does not see a constructed {{ : " + source );
            final RenderOutcome out = PlantUmlEngine.render( source );
            assertGraphvizCannotRun();
            if( out.isDrawn() ) {
                final String scrubbed = SvgScrubber.scrub( out.drawing().lightSvg(), "p-" );
                assertFalse( scrubbed.contains( "<image" ) || scrubbed.contains( "svg+xml" ) || scrubbed.contains( "<script" ), scrubbed );
            } else {
                assertNotNull( out.reason(), "a refusal always carries a reason code" );
            }
        }
    }

    @Test
    void literalEmbeddedDiagramReachingTheEngineDirectlyIsStillContained() {
        final RenderOutcome out = PlantUmlEngine.render( "Alice -> Bob\nnote right\n{{\nclass A\nclass B\nA <|-- B\n}}\nend note" );
        assertGraphvizCannotRun();
        if( out.isDrawn() ) {
            final String scrubbed = SvgScrubber.scrub( out.drawing().lightSvg(), "p-" );
            assertFalse( scrubbed.contains( "<image" ), scrubbed );
            assertFalse( scrubbed.contains( "svg+xml" ), scrubbed );
        }
    }

    @Test
    void javascriptLinkInRawEngineOutputDoesNotSurviveTheScrub() {
        // the engine itself does not sanitise: the policy refuses the link, and the scrubber is the second line of defence
        final RenderOutcome out = PlantUmlEngine.render( "class A [[javascript:alert(1)]]" );
        assertTrue( out.isDrawn(), () -> out.reason() + " " + out.message() );
        final String scrubbed = SvgScrubber.scrub( out.drawing().lightSvg(), "p-" );
        // the tooltip text (title / xlink:title) may still read "javascript:..." - it is inert text, not a navigation target
        assertFalse( scrubbed.toLowerCase().contains( "href=\"javascript" ), scrubbed );
        assertFalse( scrubbed.contains( "href=" ), "the unsafe link target must be dropped entirely: " + scrubbed );
    }

    @Test
    void sourceWithNoDiagramBlockIsASyntaxError() {
        final RenderOutcome out = PlantUmlEngine.render( "@startuml\n@startuml\nclass A" );
        assertEquals( "syntax-error", out.reason() );
        assertEquals( "No diagram found", out.message() );
        assertFalse( out.transientFailure() );
    }

    @Test
    void sourceWithoutStartumlIsWrappedAndSourceWithOneIsLeftAlone() {
        assertTrue( PlantUmlEngine.render( "class A" ).isDrawn() );
        assertTrue( PlantUmlEngine.render( "  @startuml\nclass A\n@enduml" ).isDrawn() );
        assertTrue( PlantUmlEngine.render( "@startuml foo\r\nclass A\r\n@enduml\r\n" ).isDrawn() );
    }

    @Test
    void errorLineIsMappedBackAcrossTheInjectedPragma() {
        // error on the very first line after @startuml (author line 2) and in a wrapped source (author line 1)
        assertEquals( 2, PlantUmlEngine.render( "@startuml\nclass {\n@enduml" ).line() );
        assertEquals( 1, PlantUmlEngine.render( "class {" ).line() );
        // content before @startuml keeps its own numbering
        assertEquals( 3, PlantUmlEngine.render( "' c\n@startuml\nclass {\n@enduml" ).line() );
    }

    @Test
    void whitespaceOnlySourceAndNullAreEmptyDiagrams() {
        for( final String s : new String[] { null, "", " \n\t", "@startuml\n   \n@enduml", "@enduml" } ) {
            final RenderOutcome out = PlantUmlEngine.render( s );
            assertEquals( "syntax-error", out.reason(), String.valueOf( s ) );
            assertEquals( "The diagram is empty", out.message() );
        }
    }

    @Test
    void versionIsReported() {
        assertTrue( PlantUmlEngine.version().matches( ".*\\d.*" ), PlantUmlEngine.version() );
    }

    @Test
    void renderersFailureBecomesTransientRenderFailed() {
        try( MockedConstruction< SourceStringReader > ignored = Mockito.mockConstruction( SourceStringReader.class,
            ( m, ctx ) -> Mockito.when( m.getBlocks() ).thenThrow( new IllegalStateException( "synthetic" ) ) ) ) {
            final RenderOutcome out = PlantUmlEngine.render( "class A" );
            assertEquals( "render-failed", out.reason() );
            assertTrue( out.transientFailure() );
        }
    }

    @Test
    void linkageErrorBecomesTransientRenderFailed() {
        try( MockedConstruction< SourceStringReader > ignored = Mockito.mockConstruction( SourceStringReader.class,
            ( m, ctx ) -> Mockito.when( m.getBlocks() ).thenThrow( new NoClassDefFoundError( "synthetic" ) ) ) ) {
            assertEquals( "render-failed", PlantUmlEngine.render( "class A" ).reason() );
        }
    }

    @Test
    void stackOverflowOutsideExportIsTooComplexAndPermanent() {
        try( MockedConstruction< SourceStringReader > ignored = Mockito.mockConstruction( SourceStringReader.class,
            ( m, ctx ) -> Mockito.when( m.getBlocks() ).thenThrow( new StackOverflowError() ) ) ) {
            final RenderOutcome out = PlantUmlEngine.render( "class A" );
            assertEquals( "too-complex", out.reason() );
            assertFalse( out.transientFailure() );
        }
    }

    @Test
    void crashInOnlyTheDarkVariantIsRefused() throws Exception {
        final TitledDiagram diagram = Mockito.mock( TitledDiagram.class );
        final AtomicInteger exports = new AtomicInteger();
        Mockito.when( diagram.exportDiagram( any( OutputStream.class ), anyInt(), any() ) ).thenAnswer( inv -> {
            final boolean dark = exports.incrementAndGet() == 2;
            ( (OutputStream) inv.getArgument( 0 ) ).write( "<svg/>".getBytes( StandardCharsets.UTF_8 ) );
            final ImageData data = Mockito.mock( ImageData.class );
            Mockito.when( data.getStatus() ).thenReturn( dark ? 503 : 0 );
            return data;
        } );
        final BlockUml block = Mockito.mock( BlockUml.class );
        Mockito.when( block.getDiagram() ).thenReturn( diagram );
        try( MockedConstruction< SourceStringReader > ignored = Mockito.mockConstruction( SourceStringReader.class,
            ( m, ctx ) -> Mockito.when( m.getBlocks() ).thenReturn( List.of( block ) ) ) ) {
            final RenderOutcome out = PlantUmlEngine.render( "class A" );
            assertEquals( 2, exports.get(), "both variants are exported before the verdict" );
            assertEquals( "render-crashed", out.reason() );
            assertFalse( out.transientFailure() );
        }
    }

    @Test
    void nullImageDataMeansStatusZeroAndBothVariantsAreReturned() throws Exception {
        final TitledDiagram diagram = Mockito.mock( TitledDiagram.class );
        Mockito.when( diagram.exportDiagram( any( OutputStream.class ), anyInt(), any() ) ).thenAnswer( inv -> {
            ( (OutputStream) inv.getArgument( 0 ) ).write( "<svg/>".getBytes( StandardCharsets.UTF_8 ) );
            return null;
        } );
        final BlockUml block = Mockito.mock( BlockUml.class );
        Mockito.when( block.getDiagram() ).thenReturn( diagram );
        try( MockedConstruction< SourceStringReader > ignored = Mockito.mockConstruction( SourceStringReader.class,
            ( m, ctx ) -> Mockito.when( m.getBlocks() ).thenReturn( List.of( block ) ) ) ) {
            final RenderOutcome out = PlantUmlEngine.render( "class A" );
            assertTrue( out.isDrawn() );
            assertEquals( "<svg/>", out.drawing().lightSvg() );
            assertEquals( "<svg/>", out.drawing().darkSvg() );
        }
    }
}
