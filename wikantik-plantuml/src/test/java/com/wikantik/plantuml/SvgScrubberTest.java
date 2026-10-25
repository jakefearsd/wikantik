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
import org.w3c.dom.Document;
import org.xml.sax.InputSource;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SvgScrubberTest {

    private static final String NS = "xmlns=\"http://www.w3.org/2000/svg\" xmlns:xlink=\"http://www.w3.org/1999/xlink\"";

    private static String fixture( final String name ) throws IOException {
        try( InputStream in = SvgScrubberTest.class.getResourceAsStream( "/svg/" + name + ".svg" ) ) {
            return new String( in.readAllBytes(), StandardCharsets.UTF_8 );
        }
    }

    private static Document parse( final String xml ) throws Exception {
        return DocumentBuilderFactory.newInstance().newDocumentBuilder().parse( new InputSource( new StringReader( xml ) ) );
    }

    private static int elementCount( final String xml ) throws Exception {
        return parse( xml ).getElementsByTagName( "*" ).getLength();
    }

    @ParameterizedTest
    @ValueSource( strings = { "class", "class-dark", "styled", "activity", "sequence", "usecase", "component", "state",
                              "object", "deployment", "timing", "c4" } )
    void fixturesSurviveUnchangedExceptIds( final String name ) throws Exception {
        final String in = fixture( name );
        final String out = SvgScrubber.scrub( in, "wkfabc-" );
        assertEquals( elementCount( in ), elementCount( out ), name );
        assertTrue( out.startsWith( "<svg" ), name );
        assertFalse( out.contains( "<?" ), name );
        assertFalse( out.contains( "<!--" ), name );
    }

    @Test
    void linkAndGradientAndFilterSurvive() throws Exception {
        final String out = SvgScrubber.scrub( fixture( "class" ), "p-" );
        assertTrue( out.contains( "href=\"https://example.com/x\"" ), out );
        assertTrue( out.contains( "target=\"_top\"" ), out );
        final String styled = SvgScrubber.scrub( fixture( "styled" ), "p-" );
        assertTrue( styled.contains( "<linearGradient" ) && styled.contains( "<filter" ), styled );
        assertTrue( styled.contains( "filter=\"url(#p-f" ), styled );
        assertTrue( styled.contains( "fill=\"url(#p-g" ), styled );
    }

    @Test
    void scriptAndForeignObjectRemoved() throws Exception {
        final String out = SvgScrubber.scrub( "<svg " + NS + "><script>x</script><foreignObject><div/></foreignObject><g/></svg>", "p-" );
        assertFalse( out.contains( "script" ), out );
        assertFalse( out.contains( "foreignObject" ), out );
        assertFalse( out.contains( "div" ), out );
        assertTrue( out.contains( "<g/>" ), out );
    }

    @Test
    void otherDangerousElementsRemoved() {
        final String out = SvgScrubber.scrub( "<svg " + NS + "><style>@import 'x';</style><image href=\"http://e/x.png\"/>"
            + "<use xlink:href=\"http://e/x.svg#a\"/><iframe/><animate attributeName=\"href\" to=\"javascript:x\"/>"
            + "<set attributeName=\"href\" to=\"javascript:x\"/><rect id=\"r\"/></svg>", "p-" );
        for( final String bad : new String[] { "style", "image", "use", "iframe", "animate", "set", "javascript" } ) {
            assertFalse( out.contains( bad ), bad + " in " + out );
        }
        assertTrue( out.contains( "<rect id=\"p-r\"/>" ), out );
    }

    @Test
    void onAttributesRemoved() {
        final String out = SvgScrubber.scrub( "<svg " + NS + " onload=\"x()\"><rect OnClick=\"y()\" onmouseover=\"z\" x=\"1\"/></svg>", "p-" );
        assertFalse( out.toLowerCase().contains( "on" + "load" ) || out.toLowerCase().contains( "onclick" ) || out.contains( "onmouseover" ), out );
        assertTrue( out.contains( "x=\"1\"" ), out );
    }

    @ParameterizedTest
    @ValueSource( strings = { "javascript:alert(1)", " javascript:alert(1)", "JaVaScRiPt:alert(1)", "data:text/html,x", "//evil.example/x",
                              "/other/path", "vbscript:x", "ftp://example.com/x" } )
    void unsafeHrefRemoved( final String href ) {
        final String out = SvgScrubber.scrub( "<svg " + NS + "><a href=\"" + href + "\"><g/></a><a xlink:href=\"" + href + "\"><g/></a></svg>", "p-" );
        assertFalse( out.contains( "href" ), out );
        assertTrue( out.contains( "<g/>" ), out );
    }

    @Test
    void safeHrefsKept() {
        final String out = SvgScrubber.scrub( "<svg " + NS + "><a href=\"/wiki/Foo\" target=\"_top\"/><a xlink:href=\"https://e.com/x\" target=\"_blank\"/>"
            + "<a href=\"http://e.com\" target=\"evil\"/><a href=\"#n1\"/><g id=\"n1\"/></svg>", "p-" );
        assertTrue( out.contains( "href=\"/wiki/Foo\"" ), out );
        assertTrue( out.contains( "xlink:href=\"https://e.com/x\"" ), out );
        assertTrue( out.contains( "target=\"_blank\"" ), out );
        assertFalse( out.contains( "evil" ), out );
        assertTrue( out.contains( "href=\"#p-n1\"" ), out );
    }

    @Test
    void styleUrlToExternalRemoved() {
        final String out = SvgScrubber.scrub( "<svg " + NS + "><rect x=\"1\" style=\"fill:url(http://evil/x)\"/><rect x=\"2\" fill=\"url(//evil/x)\"/>"
            + "<rect x=\"3\" style=\"fill:url('#a')\"/><rect x=\"4\" style=\"stroke:red;stroke-width:1\"/><rect x=\"5\" style=\"background:expression(x)\"/>"
            + "<rect x=\"6\" style=\"a:\\75rl(x)\"/><rect x=\"7\" style=\"@import 'x'\"/><rect x=\"8\" style=\"x:javascript:y\"/>"
            + "<rect x=\"9\" style=\"fill:url(#a)\"/></svg>", "p-" );
        assertFalse( out.contains( "evil" ) || out.contains( "expression" ) || out.contains( "@import" ) || out.contains( "javascript" ) || out.contains( "\\" ), out );
        assertTrue( out.contains( "stroke:red;stroke-width:1" ), out );
        assertTrue( out.contains( "style=\"fill:url(#p-a)\"" ), out );
        assertFalse( out.contains( "url('#a')" ), out );
    }

    @Test
    void idsPrefixedAndReferencesRewritten() {
        final String out = SvgScrubber.scrub( "<svg " + NS + "><defs><linearGradient id=\"g\"/></defs><rect fill=\"url(#g)\"/></svg>", "wkfabc-" );
        assertTrue( out.contains( "id=\"wkfabc-g\"" ), out );
        assertTrue( out.contains( "fill=\"url(#wkfabc-g)\"" ), out );
    }

    @Test
    void plantumlSrcPiRemoved() {
        final String out = SvgScrubber.scrub( "<svg " + NS + "><?plantuml-src abc?><?plantuml 1.2?><!-- c --><g/></svg>", "p-" );
        assertFalse( out.contains( "plantuml" ), out );
        assertFalse( out.contains( "<?" ) || out.contains( "<!--" ), out );
    }

    @Test
    void doctypeRejected() {
        assertThrows( SvgScrubber.Rejected.class, () -> SvgScrubber.scrub(
            "<!DOCTYPE svg [<!ENTITY x SYSTEM \"file:///etc/passwd\">]><svg " + NS + ">&x;</svg>", "p-" ) );
    }

    @Test
    void unparsableAndNonSvgRejected() {
        assertThrows( SvgScrubber.Rejected.class, () -> SvgScrubber.scrub( "<svg><g></svg>", "p-" ) );
        assertThrows( SvgScrubber.Rejected.class, () -> SvgScrubber.scrub( "", "p-" ) );
        assertThrows( SvgScrubber.Rejected.class, () -> SvgScrubber.scrub( "<html/>", "p-" ) );
    }

    @Test
    void labelTextCannotBecomeMarkup() throws Exception {
        final String out = SvgScrubber.scrub( "<svg " + NS + "><title>a\"b</title><text title='q\"&amp;&lt;x'>"
            + "&lt;script&gt;alert(1)&lt;/script&gt; a &amp;&amp; b ]]&gt; <![CDATA[<script>y</script>]]></text></svg>", "p-" );
        assertFalse( out.contains( "<script" ), out );
        assertFalse( out.contains( "<![CDATA[" ), out );
        final Document doc = parse( out );
        assertEquals( 0, doc.getElementsByTagName( "script" ).getLength(), out );
        assertTrue( doc.getElementsByTagName( "text" ).item( 0 ).getTextContent().contains( "<script>alert(1)</script> a && b ]]> <script>y</script>" ), out );
    }

    @Test
    void outputHasNoXmlDeclarationOrDoctypeAndKeepsNamespaces() {
        final String out = SvgScrubber.scrub( "<?xml version=\"1.0\"?><svg " + NS + " xmlns:evil=\"urn:x\"><g/></svg>", "p-" );
        assertTrue( out.startsWith( "<svg xmlns=\"http://www.w3.org/2000/svg\"" ), out );
        assertTrue( out.contains( "xmlns:xlink=\"http://www.w3.org/1999/xlink\"" ), out );
        assertFalse( out.contains( "evil" ) || out.contains( "<?" ), out );
    }
}
