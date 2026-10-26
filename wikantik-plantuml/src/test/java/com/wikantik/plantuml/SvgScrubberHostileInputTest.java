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
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Property-style hostile-input test: a few hundred randomised documents from a fixed seed are scrubbed, and every
 * output must be well-formed XML that contains nothing executable or fetchable. A failure prints the seed index and
 * the offending input so it reproduces.
 */
class SvgScrubberHostileInputTest {

    private static final long SEED = 20261025L;
    private static final int DOCUMENTS = 600;
    private static final String PREFIX = "wkfTest-";
    private static final Pattern DATA_IMAGE = Pattern.compile( "data:image/(png|jpeg|gif);base64,[A-Za-z0-9+/=\\s]+" );
    private static final Pattern URL_CALL = Pattern.compile( "url\\(", Pattern.CASE_INSENSITIVE );
    private static final Pattern LOCAL_URL = Pattern.compile( "url\\(#[A-Za-z0-9_.:-]+\\)", Pattern.CASE_INSENSITIVE );

    private static final List< String > GOOD_ELEMENTS = List.of( "g", "rect", "path", "text", "a", "defs", "ellipse", "line",
        "polygon", "linearGradient", "stop", "title", "filter", "feGaussianBlur" );
    private static final List< String > BAD_ELEMENTS = List.of( "script", "foreignObject", "iframe", "use", "animate", "set",
        "style", "object", "embed", "html", "body", "math", "svg:script", "animateTransform", "handler", "audio", "video" );
    private static final List< String > IMAGE_HREFS = List.of( "http://evil.example/x.png", "https://evil.example/x.svg",
        "data:text/html;base64,PHNjcmlwdD4=", "data:image/svg+xml;base64,PHN2Zz4=", "data:image/png;base64,iVBORw0KGgo=",
        "data:image/png;base64,iVBORw0KGgo=\"><script>", "javascript:alert(1)", "//evil.example/x.png", "file:///etc/passwd",
        "data:image/gif;base64,R0lGODlhAQABAAAAACw=", "data:image/jpeg;base64,/9j/4AAQ", "", "#local" );
    private static final List< String > HREFS = List.of( "javascript:alert(1)", " javascript:alert(1)", "JaVaScRiPt:alert(1)",
        "http://example.org/ok", "HTTPS://example.org/ok", "data:text/html,<script>alert(1)</script>", "vbscript:x",
        "/wiki/Page", "#frag", "//evil.example", "ftp://x", "\tjavascript:alert(1)", "&#106;avascript:alert(1)",
        "jav&#x09;ascript:alert(1)", "file:///etc/passwd", "" );
    private static final List< String > STYLES = List.of( "fill:url(http://evil.example/x)", "fill:url(#g1);stroke:red",
        "width:expression(alert(1))", "@import url(http://evil.example/x.css)", "background:url(javascript:alert(1))",
        "stroke:#000;stroke-width:1.5", "fill:red;behavior:url(x.htc)", "fill:\\75rl(http://x)", "fill:red/*x*/",
        "font-family:'a';-moz-binding:url(x)", "stroke:URL(http://x)", "fill:rgb(1,2,3)" );
    private static final List< String > OTHER_ATTRS = List.of( "class", "x", "y", "width", "height", "fill", "d", "points",
        "transform", "filter", "id", "target", "xmlns", "xmlns:xlink", "xlink:title", "data-link-type", "data-evil",
        "srcdoc", "formaction", "poster", "src", "action", "content", "from", "to", "values", "attributeName" );
    private static final List< String > ATTR_VALUES = List.of( "1", "red", "url(#g1)", "url(http://evil.example)", "_blank",
        "_self", "javascript:alert(1)", "http://www.w3.org/2000/svg", "http://evil.example/ns", "M0 0L1 1", "a\"b", "a<b>c",
        "&amp;&lt;script&gt;", "x&#10;y", "url( #g1 )", "url(\\#g1)" );
    private static final List< String > TEXTS = List.of( "hello", "<script>alert(1)</script>", "a & b", "]]&gt;", "\"quoted\"",
        "&lt;img src=x onerror=alert(1)&gt;", "line1\nline2\ttab", "\u202e rtl", "javascript:alert(1)" );
    private static final List< String > INJECTIONS = List.of( "<!-- <script>alert(1)</script> -->",
        "<![CDATA[<script>alert(1)</script>]]>", "<?xml-stylesheet href=\"http://evil.example/x.css\"?>",
        "<?php echo 1; ?>", "&amp;", "&lt;script&gt;", "", "<![CDATA[ ]]]]><![CDATA[> ]]>" );

    private final Random rnd = new Random( SEED );

    private <T> T pick( final List< T > from ) {
        return from.get( rnd.nextInt( from.size() ) );
    }

    private String attrs() {
        final StringBuilder sb = new StringBuilder();
        final int n = rnd.nextInt( 5 );
        for( int i = 0; i < n; i++ ) {
            final int kind = rnd.nextInt( 6 );
            // attribute names are unique per element: duplicates would make the document ill-formed before the scrubber sees it
            final String name = switch( kind ) {
                case 0 -> "style";
                case 1 -> rnd.nextBoolean() ? "href" : "xlink:href";
                case 2 -> "on" + pick( List.of( "click", "load", "error", "mouseover", "focus", "ANIMATIONSTART", "x" ) );
                default -> pick( OTHER_ATTRS );
            };
            if( sb.indexOf( " " + name + "=" ) >= 0 ) {
                continue;
            }
            final String value = switch( kind ) {
                case 0 -> pick( STYLES );
                case 1 -> pick( HREFS );
                case 2 -> "alert(1)";
                default -> pick( ATTR_VALUES );
            };
            sb.append( ' ' ).append( name ).append( "=\"" ).append( escapeAttr( value ) ).append( '"' );
        }
        return sb.toString();
    }

    /** Escapes only what would break well-formedness, leaving entities such as &#106; as the author wrote them. */
    private static String escapeAttr( final String v ) {
        return v.replace( "\"", "&quot;" ).replace( "<", "&lt;" ).replaceAll( "&(?!#?\\w+;)", "&amp;" );
    }

    private void element( final StringBuilder sb, final int depth ) {
        final int roll = rnd.nextInt( 10 );
        if( roll < 2 ) {
            sb.append( "<image" ).append( attrs() );
            for( final String a : new String[] { "href", "xlink:href" } ) {
                if( rnd.nextInt( 3 ) > 0 && sb.indexOf( " " + a + "=", sb.lastIndexOf( "<image" ) ) < 0 ) {
                    sb.append( ' ' ).append( a ).append( "=\"" ).append( escapeAttr( pick( IMAGE_HREFS ) ) ).append( '"' );
                }
            }
            sb.append( "/>" );
            return;
        }
        final String name = roll < 4 ? pick( BAD_ELEMENTS ) : pick( GOOD_ELEMENTS );
        sb.append( '<' ).append( name ).append( attrs() ).append( '>' );
        final int children = depth > 6 ? 0 : rnd.nextInt( 4 );
        for( int i = 0; i < children; i++ ) {
            switch( rnd.nextInt( 4 ) ) {
                case 0 -> sb.append( pick( TEXTS ).replace( "&", "&amp;" ).replace( "<", "&lt;" ) );
                case 1 -> sb.append( pick( INJECTIONS ) );
                default -> element( sb, depth + 1 );
            }
        }
        sb.append( "</" ).append( name ).append( '>' );
    }

    private String document( final int index ) {
        final StringBuilder sb = new StringBuilder();
        final int shape = index % 12;
        if( shape == 0 ) {
            sb.append( "<!DOCTYPE svg [<!ENTITY xxe SYSTEM \"file:///etc/passwd\">]>" );
        } else if( shape == 1 ) {
            sb.append( "<!DOCTYPE svg PUBLIC \"-//W3C//DTD SVG 1.1//EN\" \"http://evil.example/svg.dtd\">" );
        } else if( shape == 2 ) {
            sb.append( "<?xml version=\"1.0\"?><!DOCTYPE svg [<!ENTITY a \"&#60;script&#62;alert(1)&#60;/script&#62;\">]>" );
        }
        sb.append( "<svg xmlns=\"http://www.w3.org/2000/svg\" xmlns:xlink=\"http://www.w3.org/1999/xlink\"" )
          .append( attrs() ).append( '>' );
        final int children = 1 + rnd.nextInt( 5 );
        for( int i = 0; i < children; i++ ) {
            element( sb, 1 );
        }
        if( shape == 3 ) {
            sb.append( "&xxe;&a;" );
        }
        if( shape == 4 ) {
            for( int i = 0; i < 300; i++ ) {
                sb.append( "<g onload=\"x()\">" );
            }
            for( int i = 0; i < 300; i++ ) {
                sb.append( "</g>" );
            }
        }
        sb.append( "</svg>" );
        return sb.toString();
    }

    private static Document parseOutput( final String xml ) throws Exception {
        final DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setFeature( "http://apache.org/xml/features/disallow-doctype-decl", true );
        f.setFeature( XMLConstants.FEATURE_SECURE_PROCESSING, true );
        return f.newDocumentBuilder().parse( new InputSource( new StringReader( xml ) ) );
    }

    private static void assertSafe( final Node n ) {
        if( n.getNodeType() == Node.CDATA_SECTION_NODE || n.getNodeType() == Node.COMMENT_NODE
            || n.getNodeType() == Node.PROCESSING_INSTRUCTION_NODE || n.getNodeType() == Node.ENTITY_REFERENCE_NODE ) {
            fail( "unexpected node type " + n.getNodeType() );
        }
        if( n.getNodeType() != Node.ELEMENT_NODE ) {
            return;
        }
        final Element el = (Element) n;
        assertTrue( SvgAllowList.ELEMENTS.contains( el.getTagName() ), "element not on the allow-list: " + el.getTagName() );
        final NamedNodeMap attrs = el.getAttributes();
        for( int i = 0; i < attrs.getLength(); i++ ) {
            final String name = attrs.item( i ).getNodeName();
            final String value = attrs.item( i ).getNodeValue();
            assertFalse( name.toLowerCase( Locale.ROOT ).startsWith( "on" ), "event handler kept: " + name );
            // data-* and title text are inert; javascript:/expression(/@import only matter where a URL or CSS is interpreted
            final boolean interpreted = "style".equals( name ) || name.endsWith( "href" );
            if( interpreted ) {
                assertFalse( value.toLowerCase( Locale.ROOT ).contains( "javascript:" ), "javascript: kept in " + name );
                assertFalse( value.toLowerCase( Locale.ROOT ).contains( "expression(" ), "expression( kept in " + name );
                assertFalse( value.toLowerCase( Locale.ROOT ).contains( "@import" ), "@import kept in " + name );
            }
            if( "href".equals( name ) || "xlink:href".equals( name ) ) {
                final boolean fragment = value.startsWith( "#" + PREFIX );
                final boolean wiki = value.startsWith( "/wiki/" );
                final boolean web = value.toLowerCase( Locale.ROOT ).startsWith( "http://" )
                    || value.toLowerCase( Locale.ROOT ).startsWith( "https://" );
                final boolean image = "image".equals( el.getTagName() ) && DATA_IMAGE.matcher( value ).matches();
                assertTrue( fragment || wiki || web || image, "href kept that is not a fragment/wiki/http/raster-data URI: " + value );
            }
            final int urls = (int) URL_CALL.matcher( value ).results().count();
            if( urls > 0 && !"id".equals( name ) ) {
                assertEquals( urls, (int) LOCAL_URL.matcher( value ).results().count(), "url( to something other than #id in " + name + ": " + value );
                assertFalse( value.contains( "url(#" ) && !value.contains( "url(#" + PREFIX ), "local reference not prefixed: " + value );
            }
            if( "image".equals( el.getTagName() ) ) {
                assertTrue( List.of( "x", "y", "width", "height", "preserveAspectRatio", "href", "xlink:href" ).contains( name ),
                    "unexpected attribute on image: " + name );
            }
        }
        final NodeList kids = el.getChildNodes();
        for( int i = 0; i < kids.getLength(); i++ ) {
            assertSafe( kids.item( i ) );
        }
    }

    @Test
    void randomisedHostileDocumentsNeverYieldUnsafeOutput() {
        int accepted = 0;
        int rejected = 0;
        for( int i = 0; i < DOCUMENTS; i++ ) {
            final String input = document( i );
            final String output;
            try {
                output = SvgScrubber.scrub( input, PREFIX );
            } catch( final SvgScrubber.Rejected e ) {
                rejected++;
                continue;
            }
            accepted++;
            try {
                assertFalse( output.contains( "<!DOCTYPE" ) || output.contains( "<!ENTITY" ) || output.contains( "<!--" )
                    || output.contains( "<![CDATA[" ) || output.contains( "<?" ), "markup that must never be emitted" );
                final Document doc = parseOutput( output );
                assertEquals( "svg", doc.getDocumentElement().getTagName() );
                assertSafe( doc.getDocumentElement() );
            } catch( final Throwable t ) {
                throw new AssertionError( "document #" + i + " (seed " + SEED + ") produced unsafe output: " + t.getMessage()
                    + "\n--- input ---\n" + input + "\n--- output ---\n" + output, t );
            }
        }
        assertTrue( accepted > DOCUMENTS / 2, "too few documents survived to be a meaningful test: " + accepted );
        assertTrue( rejected >= DOCUMENTS / 12, "DOCTYPE documents must be rejected, rejected=" + rejected );
    }

    @Test
    void generatorIsReproducible() {
        final SvgScrubberHostileInputTest a = new SvgScrubberHostileInputTest();
        final SvgScrubberHostileInputTest b = new SvgScrubberHostileInputTest();
        assertEquals( a.document( 7 ), b.document( 7 ) );
    }
}
