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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SvgScrubberEdgeCaseTest {

    private static final String SVG = "<svg xmlns=\"http://www.w3.org/2000/svg\"><g id=\"a\"/></svg>";

    @Test
    void nullPrefixIsRefused() {
        assertThrows( IllegalArgumentException.class, () -> SvgScrubber.scrub( SVG, null ) );
    }

    @ParameterizedTest
    @ValueSource( strings = { "", "1abc", "-abc", "a b", "a\"b", "a<b", "a:b", "a.b", "a\nb", "éa", "a'onload='x" } )
    void unsafePrefixesAreRefused( final String prefix ) {
        assertThrows( IllegalArgumentException.class, () -> SvgScrubber.scrub( SVG, prefix ) );
    }

    @ParameterizedTest
    @ValueSource( strings = { "a", "wkf0123456789l-", "A_b-C9" } )
    void safePrefixesAreApplied( final String prefix ) {
        assertTrue( SvgScrubber.scrub( SVG, prefix ).contains( "id=\"" + prefix + "a\"" ) );
    }

    @Test
    void nonSvgRootIsRejected() {
        for( final String root : new String[] { "<html><svg/></html>", "<g/>", "<SVG/>", "<script>alert(1)</script>" } ) {
            final SvgScrubber.Rejected r = assertThrows( SvgScrubber.Rejected.class, () -> SvgScrubber.scrub( root, "p-" ) );
            assertEquals( "root element is not svg", r.getMessage(), root );
        }
    }

    @Test
    void emptyAndUnparsableInputAreRejected() {
        assertThrows( SvgScrubber.Rejected.class, () -> SvgScrubber.scrub( "", "p-" ) );
        assertThrows( SvgScrubber.Rejected.class, () -> SvgScrubber.scrub( null, "p-" ) );
        assertThrows( SvgScrubber.Rejected.class, () -> SvgScrubber.scrub( "<svg", "p-" ) );
    }

    @Test
    void textAndAttributeValuesAreEscapedNeverReinterpretedAsMarkup() {
        final String out = SvgScrubber.scrub( "<svg xmlns=\"http://www.w3.org/2000/svg\"><text title=\"a&quot;&lt;b\">"
            + "<![CDATA[<script>alert(1)</script>]]> &amp; \"q\"</text></svg>", "p-" );
        assertFalse( out.contains( "<script" ), out );
        assertTrue( out.contains( "&lt;script&gt;alert(1)&lt;/script&gt;" ), out );
        assertTrue( out.contains( "title=\"a&quot;&lt;b\"" ), out );
    }

    private static String nested( final int depth ) {
        final StringBuilder sb = new StringBuilder( "<svg xmlns=\"http://www.w3.org/2000/svg\">" );
        sb.append( "<g>".repeat( depth ) ).append( "<rect/>" ).append( "</g>".repeat( depth ) ).append( "</svg>" );
        return sb.toString();
    }

    @Test
    void reasonablyNestedElementsAreKept() {
        assertEquals( 90, SvgScrubber.scrub( nested( 90 ), "p-" ).split( "<g>", -1 ).length - 1 );
    }

    @Test
    void absurdlyNestedElementsAreRejectedByTheParserLimitNotByStackExhaustion() {
        final SvgScrubber.Rejected r = assertThrows( SvgScrubber.Rejected.class, () -> SvgScrubber.scrub( nested( 5_000 ), "p-" ) );
        assertTrue( r.getMessage().startsWith( "unparsable svg" ), r.getMessage() );
    }

    @Test
    void imageWithRasterDataUriKeepsOnlyGeometryAndHref() {
        final String out = SvgScrubber.scrub( "<svg xmlns=\"http://www.w3.org/2000/svg\" xmlns:xlink=\"http://www.w3.org/1999/xlink\">"
            + "<image x=\"1\" y=\"2\" width=\"3\" height=\"4\" onload=\"x()\" id=\"i\" class=\"c\" style=\"fill:red\" "
            + "xlink:href=\"data:image/png;base64,iVBORw0KGgo=\"/></svg>", "p-" );
        assertTrue( out.contains( "<image x=\"1\" y=\"2\" width=\"3\" height=\"4\" xlink:href=\"data:image/png;base64,iVBORw0KGgo=\"/>" )
            || ( out.contains( "<image" ) && !out.contains( "onload" ) && !out.contains( "id=\"p-i\"" ) && !out.contains( "class=" ) ), out );
        assertFalse( out.contains( "style=" ), out );
    }

    @Test
    void imageWithAnyNonRasterHrefIsDroppedWithItsSiblingAttributes() {
        for( final String href : new String[] { "data:image/svg+xml;base64,AAAA", "http://x/y.png", "data:text/html;base64,AAAA",
                                               "data:image/png;base64,AAAA\" onload=\"x", "" } ) {
            final String out = SvgScrubber.scrub( "<svg xmlns=\"http://www.w3.org/2000/svg\"><image href=\"" + href.replace( "\"", "&quot;" )
                + "\" x=\"1\"/></svg>", "p-" );
            assertFalse( out.contains( "<image" ), href + " -> " + out );
        }
    }

    @Test
    void imageWithNoHrefAtAllIsDropped() {
        assertFalse( SvgScrubber.scrub( "<svg xmlns=\"http://www.w3.org/2000/svg\"><image x=\"1\"/></svg>", "p-" ).contains( "<image" ) );
    }

    @Test
    void imageWithTwoHrefsMustHaveBothRasterOrIsDropped() {
        final String out = SvgScrubber.scrub( "<svg xmlns=\"http://www.w3.org/2000/svg\" xmlns:xlink=\"http://www.w3.org/1999/xlink\">"
            + "<image href=\"data:image/png;base64,AAAA\" xlink:href=\"http://evil.example/x.png\"/></svg>", "p-" );
        assertFalse( out.contains( "<image" ), out );
    }
}
