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
package com.wikantik.war;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;

import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Obsidian import servlet sweeps stale upload spools in {@code init()}; without {@code load-on-startup} that
 * only happens on the first import request after a restart, so a crash's leftovers could sit on disk indefinitely.
 */
class ObsidianImportServletStartupTest {

    private static final Path WEB_XML = Paths.get( "src", "main", "webapp", "WEB-INF", "web.xml" );

    @Test
    void importServletLoadsOnStartup() throws Exception {
        final DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setFeature( "http://apache.org/xml/features/nonvalidating/load-external-dtd", false );
        f.setFeature( "http://xml.org/sax/features/external-general-entities", false );
        f.setFeature( "http://xml.org/sax/features/external-parameter-entities", false );
        final NodeList servlets = f.newDocumentBuilder().parse( WEB_XML.toFile() ).getElementsByTagName( "servlet" );
        Element servlet = null;
        for ( int i = 0; i < servlets.getLength(); i++ ) {
            final Element s = ( Element ) servlets.item( i );
            if ( "ObsidianImportResource".equals( text( s, "servlet-name" ) ) ) {
                servlet = s;
            }
        }
        assertNotNull( servlet, "ObsidianImportResource servlet missing from web.xml" );
        final String order = text( servlet, "load-on-startup" );
        assertNotNull( order, "ObsidianImportResource needs <load-on-startup> so the stale-spool sweep runs at startup" );
        assertTrue( Integer.parseInt( order.trim() ) > 0, "load-on-startup must be positive: " + order );
    }

    private static String text( final Element parent, final String tag ) {
        final NodeList n = parent.getElementsByTagName( tag );
        return n.getLength() == 0 ? null : n.item( 0 ).getTextContent();
    }
}
