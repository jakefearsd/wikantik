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
package com.wikantik.rest;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pins the container's multipart limits on the item servlet (decision P7-2, work management part 7). */
class WorkAttachmentMultipartConfigTest {

    private static final long CEILING = 104_857_600L;

    private static long value( final Element config, final String tag ) {
        final NodeList nodes = config.getElementsByTagName( tag );
        assertEquals( 1, nodes.getLength(), tag + " must be set exactly once" );
        return Long.parseLong( nodes.item( 0 ).getTextContent().trim() );
    }

    @Test
    void theItemServletAcceptsTheCeilingPlusFormFieldsAndSpoolsLargePartsToDisk() throws Exception {
        final var doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(
            Path.of( "..", "wikantik-war", "src", "main", "webapp", "WEB-INF", "web.xml" ).toFile() );
        final NodeList servlets = doc.getElementsByTagName( "servlet" );
        Element config = null;
        for ( int i = 0; i < servlets.getLength(); i++ ) {
            final Element servlet = (Element) servlets.item( i );
            if ( "WorkItemResource".equals( servlet.getElementsByTagName( "servlet-name" ).item( 0 ).getTextContent().trim() ) ) {
                config = (Element) servlet.getElementsByTagName( "multipart-config" ).item( 0 );
            }
        }
        assertTrue( config != null, "WorkItemResource needs a multipart-config" );
        assertEquals( CEILING, value( config, "max-file-size" ) );
        assertTrue( value( config, "max-request-size" ) > CEILING,
            "the request ceiling must leave room for the other form fields, or a file at the ceiling can never arrive" );
        final long threshold = value( config, "file-size-threshold" );
        assertTrue( threshold > 0 && threshold < CEILING, "large parts must spool to disk, small ones stay in memory" );
    }
}
