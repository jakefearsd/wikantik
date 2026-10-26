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
import org.xml.sax.ErrorHandler;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;

import java.lang.reflect.Constructor;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeout;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SvgParserTest {

    private static final String SVG = "<svg xmlns=\"http://www.w3.org/2000/svg\"><g/></svg>";

    @Test
    void parsesWellFormedSvg() {
        final Document d = SvgParser.parse( SVG );
        assertEquals( "svg", d.getDocumentElement().getTagName() );
    }

    @Test
    void blankAndNullAreRejected() {
        for( final String s : new String[] { null, "", "  \n\t " } ) {
            final SvgScrubber.Rejected r = assertThrows( SvgScrubber.Rejected.class, () -> SvgParser.parse( s ) );
            assertEquals( "empty svg", r.getMessage() );
        }
    }

    @Test
    void malformedXmlIsRejectedWithItsCause() {
        final SvgScrubber.Rejected r = assertThrows( SvgScrubber.Rejected.class, () -> SvgParser.parse( "<svg><g></svg>" ) );
        assertTrue( r.getMessage().startsWith( "unparsable svg" ), r.getMessage() );
        assertTrue( r.getCause() instanceof SAXException );
    }

    @Test
    void doctypeIsRefusedOutright() {
        final String doc = "<!DOCTYPE svg [<!ENTITY a \"b\">]>" + SVG;
        assertThrows( SvgScrubber.Rejected.class, () -> SvgParser.parse( doc ) );
    }

    @Test
    void externalEntityIsNeverResolved() {
        final String xxe = "<?xml version=\"1.0\"?><!DOCTYPE svg [<!ENTITY xxe SYSTEM \"file:///etc/passwd\">]>"
            + "<svg xmlns=\"http://www.w3.org/2000/svg\"><text>&xxe;</text></svg>";
        final SvgScrubber.Rejected r = assertThrows( SvgScrubber.Rejected.class, () -> SvgParser.parse( xxe ) );
        assertFalse( r.getMessage().contains( "root:" ), "file content must not leak into the message" );
    }

    @Test
    void remoteDtdIsNeverFetched() {
        final String remote = "<!DOCTYPE svg SYSTEM \"http://127.0.0.1:1/never.dtd\">" + SVG;
        assertTimeout( Duration.ofSeconds( 2 ), () -> assertThrows( SvgScrubber.Rejected.class, () -> SvgParser.parse( remote ) ) );
    }

    @Test
    void billionLaughsIsRefusedQuickly() {
        final StringBuilder dtd = new StringBuilder( "<!DOCTYPE lolz [<!ENTITY lol \"lol\">" );
        for( int i = 1; i <= 9; i++ ) {
            dtd.append( "<!ENTITY lol" ).append( i ).append( " \"" );
            for( int j = 0; j < 10; j++ ) {
                dtd.append( "&lol" ).append( i == 1 ? "" : i - 1 ).append( ';' );
            }
            dtd.append( "\">" );
        }
        dtd.append( "]><svg xmlns=\"http://www.w3.org/2000/svg\"><text>&lol9;</text></svg>" );
        assertTimeout( Duration.ofSeconds( 2 ), () -> assertThrows( SvgScrubber.Rejected.class, () -> SvgParser.parse( dtd.toString() ) ) );
        assertTimeout( Duration.ofSeconds( 2 ),
            () -> assertThrows( SvgScrubber.Rejected.class, () -> SvgScrubber.scrub( dtd.toString(), "p-" ) ) );
    }

    @Test
    void xincludeIsNotProcessed() {
        final String x = "<svg xmlns=\"http://www.w3.org/2000/svg\" xmlns:xi=\"http://www.w3.org/2001/XInclude\">"
            + "<xi:include href=\"file:///etc/passwd\" parse=\"text\"/></svg>";
        final Document d = assertDoesNotThrow( () -> SvgParser.parse( x ) );
        assertEquals( "xi:include", d.getDocumentElement().getFirstChild().getNodeName(), "the include element stays inert" );
        assertEquals( "", d.getDocumentElement().getTextContent() );
    }

    @Test
    void commentsAreDroppedAndCdataIsCoalesced() {
        final Document d = SvgParser.parse( "<svg><!-- c --><text>a<![CDATA[<b>]]>c</text></svg>" );
        assertEquals( 1, d.getDocumentElement().getChildNodes().getLength() );
        assertEquals( "a<b>c", d.getDocumentElement().getFirstChild().getTextContent() );
    }

    @Test
    void errorHandlerSurfacesErrorsAndSwallowsWarningsToTheLog() throws Exception {
        final Class< ? > type = Class.forName( SvgParser.class.getName() + "$QuietErrorHandler" );
        final Constructor< ? > ctor = type.getDeclaredConstructor();
        ctor.setAccessible( true );
        final ErrorHandler h = (ErrorHandler) ctor.newInstance();
        final SAXParseException e = new SAXParseException( "boom", null );

        assertDoesNotThrow( () -> h.warning( e ) );
        assertSame( e, assertThrows( SAXParseException.class, () -> h.error( e ) ) );
        assertSame( e, assertThrows( SAXParseException.class, () -> h.fatalError( e ) ) );
    }
}
