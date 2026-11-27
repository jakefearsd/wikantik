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
import org.w3c.dom.Document;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Exact size and shape boundaries of the PlantUML policy, cache, service and scrubber helpers. */
class PlantUmlBoundaryContractTest {

    // ---- OutcomeCache: an entry exactly as heavy as the byte bound still fits ----

    private static OutcomeCache.Entry refusedWithMessage( final int chars ) {
        return new OutcomeCache.Entry( RenderOutcome.refused( "syntax-error", "m".repeat( chars ), 1 ), 0L );
    }

    @Test
    void anEntryExactlyAsHeavyAsTheByteBoundIsCachedAndOneByteMoreIsNot() {
        final long exact = OutcomeCache.weight( refusedWithMessage( 100 ).outcome() );
        final OutcomeCache fits = new OutcomeCache( 10, exact );
        fits.put( "k", refusedWithMessage( 100 ) );
        assertNotNull( fits.get( "k" ) );
        assertEquals( exact, fits.bytes() );

        final OutcomeCache tooHeavy = new OutcomeCache( 10, exact - 1 );
        tooHeavy.put( "k", refusedWithMessage( 100 ) );
        assertNull( tooHeavy.get( "k" ) );
        assertEquals( 0L, tooHeavy.bytes() );
    }

    // ---- PlantUmlPolicy: the source-size cap is inclusive ----

    private static String sourceOfBytes( final int bytes ) {
        final String head = "@startuml\nA -> B : ";
        final String tail = "\n@enduml";
        return head + "x".repeat( bytes - head.length() - tail.length() ) + tail;
    }

    @Test
    void aSourceOfExactlyTheCapIsAllowedAndOneByteMoreIsTooLarge() {
        final String atCap = sourceOfBytes( PlantUmlPolicy.MAX_SOURCE_BYTES );
        assertEquals( PlantUmlPolicy.MAX_SOURCE_BYTES, atCap.getBytes( StandardCharsets.UTF_8 ).length );
        assertNull( PlantUmlPolicy.analyse( atCap ).reason() );
        assertEquals( "too-large", PlantUmlPolicy.analyse( sourceOfBytes( PlantUmlPolicy.MAX_SOURCE_BYTES + 1 ) ).reason() );
    }

    // ---- PlantUmlService: output size cap, cached bytes, page budget ----

    private static final String HEAD = "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"10\" height=\"10\"><text>";
    private static final String TAIL = "</text></svg>";

    private static String svgOfBytes( final int bytes ) {
        return HEAD + "x".repeat( bytes - HEAD.length() - TAIL.length() ) + TAIL;
    }

    private static PlantUmlService serviceCapping( final int maxSvgBytes, final String svg, final AtomicInteger calls ) {
        final PlantUmlSettings st = new PlantUmlSettings( true, 1, 4, 1_048_576L, 5_000L, maxSvgBytes, 600_000L, 50 );
        return new PlantUmlService( st, src -> {
            calls.incrementAndGet();
            return RenderOutcome.drawn( new PlantUmlDrawing( svg, svg ) );
        } );
    }

    @Test
    void aDrawingExactlyAtTheSvgByteCapIsKeptAndOneByteMoreIsRefused() {
        final int cap = 2_000;
        final RenderOutcome ok = serviceCapping( cap, svgOfBytes( cap ), new AtomicInteger() )
            .render( "@startuml\nA -> B\n@enduml", 2_000 );
        assertTrue( ok.isDrawn(), "reason: " + ok.reason() );

        final RenderOutcome tooBig = serviceCapping( cap, svgOfBytes( cap + 1 ), new AtomicInteger() )
            .render( "@startuml\nA -> B\n@enduml", 2_000 );
        assertEquals( "output-too-large", tooBig.reason() );
        assertEquals( "The drawing exceeds " + cap + " bytes", tooBig.message() );
    }

    @Test
    void theSvgCapIsMeasuredInUtf8BytesNotCharacters() {
        // 'é' is 2 bytes: the string is under the cap in characters but over it in bytes.
        final String svg = HEAD + "é".repeat( 600 ) + TAIL;
        assertTrue( svg.length() < 1_000 );
        assertTrue( svg.getBytes( StandardCharsets.UTF_8 ).length > 1_000 );
        final RenderOutcome out = serviceCapping( 1_000, svg, new AtomicInteger() ).render( "@startuml\nA -> B\n@enduml", 2_000 );
        assertEquals( "output-too-large", out.reason() );
    }

    @Test
    void aMissingVariantIsRefusedAsTooLarge() {
        final PlantUmlSettings st = new PlantUmlSettings( true, 1, 4, 1_048_576L, 5_000L, 100_000, 600_000L, 50 );
        final PlantUmlService s = new PlantUmlService( st,
            src -> RenderOutcome.drawn( new PlantUmlDrawing( svgOfBytes( 200 ), null ) ) );
        assertEquals( "output-too-large", s.render( "@startuml\nA -> B\n@enduml", 2_000 ).reason() );
    }

    @Test
    void cachedBytesIsTheWeightOfWhatWasCached() {
        final PlantUmlService s = serviceCapping( 100_000, svgOfBytes( 500 ), new AtomicInteger() );
        assertEquals( 0L, s.cachedBytes() );
        final RenderOutcome out = s.render( "@startuml\nA -> B\n@enduml", 2_000 );
        assertTrue( out.isDrawn() );
        assertEquals( OutcomeCache.weight( out ), s.cachedBytes() );
        assertTrue( s.cachedBytes() > 0 );
    }

    @Test
    void aZeroWaitBudgetNeverStartsANewRenderButStillServesAKnownOutcome() {
        final AtomicInteger calls = new AtomicInteger();
        final PlantUmlService s = serviceCapping( 100_000, svgOfBytes( 500 ), calls );
        final String src = "@startuml\nA -> B\n@enduml";

        final RenderOutcome none = s.render( src, 0 );
        assertEquals( "page-budget", none.reason() );
        assertTrue( none.transientFailure() );
        assertEquals( 0, calls.get(), "a zero budget must not start the engine" );

        assertTrue( s.render( src, 2_000 ).isDrawn() );
        assertTrue( s.render( src, 0 ).isDrawn(), "a cached outcome needs no budget" );
        assertEquals( 1, calls.get() );
    }

    // ---- SvgParser: coalescing, non-namespace DOM, no stderr noise ----

    @Test
    void cdataIsCoalescedIntoASingleTextNode() {
        final Document d = SvgParser.parse( "<svg><text>a<![CDATA[<b>]]>c</text></svg>" );
        final org.w3c.dom.Node text = d.getDocumentElement().getFirstChild();
        assertEquals( 1, text.getChildNodes().getLength(), "text and CDATA must be merged into one node" );
        assertEquals( org.w3c.dom.Node.TEXT_NODE, text.getFirstChild().getNodeType() );
    }

    @Test
    void theDomIsParsedWithoutNamespaceProcessingSoTheScrubberSeesQualifiedNames() {
        final Document d = SvgParser.parse( "<svg xmlns=\"http://www.w3.org/2000/svg\" xmlns:xlink=\"http://www.w3.org/1999/xlink\">"
            + "<use xlink:href=\"#a\"/></svg>" );
        assertNull( d.getDocumentElement().getLocalName(), "a namespace-unaware DOM has no local names" );
        assertEquals( "#a", ( (org.w3c.dom.Element) d.getDocumentElement().getFirstChild() ).getAttribute( "xlink:href" ) );
    }

    @Test
    void aMalformedDocumentIsRejectedWithoutTheJdkParserWritingToStderr() {
        final PrintStream original = System.err;
        final ByteArrayOutputStream captured = new ByteArrayOutputStream();
        System.setErr( new PrintStream( captured, true, StandardCharsets.UTF_8 ) );
        try {
            org.junit.jupiter.api.Assertions.assertThrows( SvgScrubber.Rejected.class, () -> SvgParser.parse( "<svg><g></svg>" ) );
        } finally {
            System.setErr( original );
        }
        assertEquals( "", captured.toString( StandardCharsets.UTF_8 ), "the quiet error handler keeps parse noise off stderr" );
    }

    // ---- SvgStyleFilter / SvgAttributeRules ----

    @Test
    void styleDeclarationsAreKeptOnlyWhenTheirValueIsInert() {
        assertEquals( "fill:red;", SvgStyleFilter.scrubStyle( "fill:red", "p-" ) );
        assertEquals( "fill:red;stroke:blue;", SvgStyleFilter.scrubStyle( "fill:red;cursor:pointer;stroke:blue", "p-" ) );
        assertNull( SvgStyleFilter.scrubStyle( "fill:(red)", "p-" ), "a value starting with a parenthesis is not inert" );
        assertNull( SvgStyleFilter.scrubStyle( "fill:rgb(1,2,3)", "p-" ), "functions other than a local url() are refused" );
        assertNull( SvgStyleFilter.scrubStyle( "fill:a\\b", "p-" ) );
        assertNull( SvgStyleFilter.scrubStyle( "fill:a/*c*/", "p-" ) );
        assertNull( SvgStyleFilter.scrubStyle( "fill:a@b", "p-" ) );
        assertNull( SvgStyleFilter.scrubStyle( "fill:a:b", "p-" ) );
        assertNull( SvgStyleFilter.scrubStyle( "fill:", "p-" ) );
        assertNull( SvgStyleFilter.scrubStyle( ":red;nonsense", "p-" ) );
        assertEquals( "fill:url(#p-a);", SvgStyleFilter.scrubStyle( "fill:url(#a)", "p-" ) );
    }

    @Test
    void eventHandlerAttributesAreDroppedWhateverTheirCase() {
        for( final String name : new String[] { "onclick", "onload", "ONCLICK", "OnMouseOver", "on" } ) {
            assertNull( SvgAttributeRules.scrubAttribute( "rect", name, "alert(1)", false, "p-" ), name );
        }
    }
}
