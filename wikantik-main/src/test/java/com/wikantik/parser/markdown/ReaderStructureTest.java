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
package com.wikantik.parser.markdown;

import com.wikantik.HttpMockFactory;
import com.wikantik.TestEngine;
import com.wikantik.api.core.Context;
import com.wikantik.api.core.Page;
import com.wikantik.api.spi.Wiki;
import com.wikantik.render.RenderingManager;
import com.wikantik.render.markdown.MarkdownRenderer;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReaderStructureTest {

    private static void assertSame( final List< int[] > expected, final List< int[] > actual, final String what ) {
        assertEquals( expected.size(), actual.size(), what + " count" );
        for ( int i = 0; i < expected.size(); i++ ) {
            assertEquals( Arrays.toString( expected.get( i ) ), Arrays.toString( actual.get( i ) ), what + " #" + i );
        }
    }

    @Test
    void ofClassifiesCodeMathHtmlAndRulesAsRenderPathAccessRulesDid() {
        final String[] bodies = {
                "a `x` $y$ <b>h</b> [{ALLOW view Admin}]\n\n$$\nz\n$$\n",
                "intro\n\n```java\nint a = 1;\n```\n\nafter [{ALLOW view Bob}]\n",
                "intro\n\n    indented code\n    more\n\nafter [t](http://x.org)\n" };
        for ( final String body : bodies ) {
            final RenderPathAccessRules.Structure expected = RenderPathAccessRules.structure( body );
            final RenderPathAccessRules.Structure actual = ReaderStructure.of( body, 0 );
            assertEquals( expected.rules(), actual.rules() );
            assertEquals( expected.labels(), actual.labels() );
            assertSame( expected.code(), actual.code(), "code" );
            assertSame( expected.html(), actual.html(), "html" );
            assertSame( expected.math(), actual.math(), "math" );
            assertSame( expected.links(), actual.links(), "links" );
        }
    }

    @Test
    void budgetExhaustionIsUnreadable() {
        assertThrows( RenderPathAccessRules.Unreadable.class, () -> ReaderStructure.of( "a `b` c", 0, 1 ) );
    }

    /** The shared list shape: a dedented line ends the item, and with it the generated fence. */
    private static final String LIST_BODY = "- item\n  $$\n  x\ny\n  $$\n- next\n";

    private static String render( final String markdown ) throws Exception {
        final TestEngine engine = TestEngine.build(
                TestEngine.with( "wikantik.fileSystemProvider.pageDir", "./target/md-pageDir" ),
                TestEngine.with( "wikantik.renderingManager.markupParser", MarkdownParser.class.getName() ),
                TestEngine.with( "wikantik.renderingManager.renderer", MarkdownRenderer.class.getName() ) );
        try {
            engine.saveText( "ReaderStructureHost", markdown );
            final Page page = Wiki.contents().page( engine, "ReaderStructureHost" );
            final Context context = Wiki.context().create( engine, HttpMockFactory.createHttpRequest(), page );
            return engine.getManager( RenderingManager.class ).textToHTML( context, markdown );
        } finally {
            engine.stop();
        }
    }

    @Test
    void theRenderEndsTheMathBlockWithTheListItem() throws Exception {
        final String html = render( LIST_BODY );
        assertTrue( html.contains( "<div class=\"math-display\" data-block=\"1\">\nx\n</div>\n</li></ul>\n<p data-block=\"2\">y</p>" ), html );
    }

    @Test
    void displayMathInAListItemEndsWithTheItem() {
        for ( final String body : new String[]{ LIST_BODY, LIST_BODY.replace( "\n", "\r\n" ) } ) {
            final RenderPathAccessRules.Structure structure = ReaderStructure.of( body, 0 );
            assertEquals( List.of( body.indexOf( "  $$" ) + "," + ( body.lastIndexOf( "$$" ) + 2 ) ),
                    structure.math().stream().map( r -> r[ 0 ] + "," + r[ 1 ] ).toList(), "math() keeps the editor's whole pair" );
            final List< int[] > math = structure.renderedMath();
            assertEquals( 1, math.size(), "one rendered math span" );
            assertEquals( body.indexOf( "  $$" ), math.get( 0 )[ 0 ], "starts at the opener line (its indent is part of the block)" );
            assertTrue( math.get( 0 )[ 1 ] <= body.indexOf( "y" ), "ends before the dedented line: " + Arrays.toString( math.get( 0 ) ) );
            assertTrue( math.get( 0 )[ 1 ] > body.indexOf( "x" ), "covers the content: " + Arrays.toString( math.get( 0 ) ) );
        }
    }

    @Test
    void displayMathAtTopLevelStillSpansDelimiters() {
        for ( final String body : new String[]{ "a\n\n$$\nx\n$$\n\nb", "a\r\n\r\n$$\r\nx\r\n$$\r\n\r\nb" } ) {
            final List< int[] > math = ReaderStructure.of( body, 0 ).renderedMath();
            assertEquals( 1, math.size() );
            assertEquals( body.indexOf( "$$" ), math.get( 0 )[ 0 ] );
            assertEquals( body.lastIndexOf( "$$" ) + 2, math.get( 0 )[ 1 ] );
        }
    }

    @Test
    void aNodeNoEditGeneratedIsNotDisplayMath() {
        assertNull( MarkdownBodySource.prepare( "a\n\n$$\nx\n$$\n" ).displayMathNodeInBody( 0, 1 ) );
    }
}
