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
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression guard: the {@code CSPFilter} and {@code ClickJackFilter} security-header filters
 * exist and are unit-tested, but for a long time they were never registered in {@code web.xml} —
 * so no {@code Content-Security-Policy} or {@code X-Frame-Options} header was ever emitted. This
 * test fails if either filter is dropped from {@code web.xml} or unmapped from {@code /*}, and if
 * the CSP loses a load-bearing directive. The wire-level counterpart (headers actually present on
 * a live response) is {@code SecurityHeadersIT}.
 */
class SecurityHeaderRegistrationTest {

    private static final Path WEB_XML = Paths.get( "src", "main", "webapp", "WEB-INF", "web.xml" );

    private static Document webXml() throws Exception {
        final DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setNamespaceAware( false );
        f.setValidating( false );
        // No network fetches / XXE while parsing the descriptor under test.
        f.setFeature( "http://apache.org/xml/features/nonvalidating/load-external-dtd", false );
        f.setFeature( "http://xml.org/sax/features/external-general-entities", false );
        f.setFeature( "http://xml.org/sax/features/external-parameter-entities", false );
        final DocumentBuilder b = f.newDocumentBuilder();
        return b.parse( WEB_XML.toFile() );
    }

    /** The {@code <filter-class>} declared for the named {@code <filter>}, or {@code null}. */
    private static String filterClass( final Document doc, final String filterName ) {
        final NodeList filters = doc.getElementsByTagName( "filter" );
        for ( int i = 0; i < filters.getLength(); i++ ) {
            final Element f = (Element) filters.item( i );
            if ( filterName.equals( childText( f, "filter-name" ) ) ) {
                return childText( f, "filter-class" );
            }
        }
        return null;
    }

    /** Index of the named filter's first {@code <filter-mapping>} in document (chain) order, or -1. */
    private static int firstMappingIndex( final Document doc, final String filterName ) {
        final NodeList mappings = doc.getElementsByTagName( "filter-mapping" );
        for ( int i = 0; i < mappings.getLength(); i++ ) {
            final Element m = (Element) mappings.item( i );
            if ( filterName.equals( childText( m, "filter-name" ) ) ) {
                return i;
            }
        }
        return -1;
    }

    /** All {@code url-pattern}s the named filter is mapped to. */
    private static Set< String > mappingsFor( final Document doc, final String filterName ) {
        final Set< String > patterns = new HashSet<>();
        final NodeList mappings = doc.getElementsByTagName( "filter-mapping" );
        for ( int i = 0; i < mappings.getLength(); i++ ) {
            final Element m = (Element) mappings.item( i );
            if ( filterName.equals( childText( m, "filter-name" ) ) ) {
                final NodeList urls = m.getElementsByTagName( "url-pattern" );
                for ( int j = 0; j < urls.getLength(); j++ ) {
                    patterns.add( urls.item( j ).getTextContent().trim() );
                }
            }
        }
        return patterns;
    }

    private static String initParam( final Document doc, final String filterName, final String paramName ) {
        final NodeList filters = doc.getElementsByTagName( "filter" );
        for ( int i = 0; i < filters.getLength(); i++ ) {
            final Element f = (Element) filters.item( i );
            if ( !filterName.equals( childText( f, "filter-name" ) ) ) continue;
            final NodeList params = f.getElementsByTagName( "init-param" );
            for ( int j = 0; j < params.getLength(); j++ ) {
                final Element p = (Element) params.item( j );
                if ( paramName.equals( childText( p, "param-name" ) ) ) {
                    return childText( p, "param-value" );
                }
            }
        }
        return null;
    }

    private static String childText( final Element parent, final String tag ) {
        final NodeList nl = parent.getElementsByTagName( tag );
        return nl.getLength() == 0 ? null : nl.item( 0 ).getTextContent().trim();
    }

    @Test
    void cspFilter_isRegisteredAndMappedToEverything() throws Exception {
        final Document doc = webXml();
        assertEquals( "com.wikantik.http.filter.CSPFilter", filterClass( doc, "CSPFilter" ),
                "CSPFilter must be registered in web.xml" );
        assertTrue( mappingsFor( doc, "CSPFilter" ).contains( "/*" ),
                "CSPFilter must be mapped to /* so every response carries a Content-Security-Policy header" );
    }

    @Test
    void clickJackFilter_isRegisteredAndMappedToEverything() throws Exception {
        final Document doc = webXml();
        assertEquals( "com.wikantik.http.filter.ClickJackFilter", filterClass( doc, "ClickJackFilter" ),
                "ClickJackFilter must be registered in web.xml" );
        assertTrue( mappingsFor( doc, "ClickJackFilter" ).contains( "/*" ),
                "ClickJackFilter must be mapped to /* so every response carries an X-Frame-Options header" );
    }

    @Test
    void csp_policy_hasTheLoadBearingDirectives() throws Exception {
        final String csp = initParam( webXml(), "CSPFilter", "CSPValue" );
        assertNotNull( csp, "CSPFilter must declare a CSPValue init-param (the production policy)" );
        // Directives whose absence would defeat the point of shipping the header at all.
        assertTrue( csp.contains( "default-src 'self'" ),
                "CSP must restrict default-src to 'self'; was: " + csp );
        assertTrue( csp.contains( "frame-ancestors 'none'" ),
                "CSP must set frame-ancestors 'none' (clickjacking defense); was: " + csp );
        assertTrue( csp.contains( "object-src 'none'" ),
                "CSP must set object-src 'none'; was: " + csp );
        assertTrue( csp.contains( "base-uri 'self'" ),
                "CSP must set base-uri 'self'; was: " + csp );
    }

    @Test
    void securityHeaderFilters_runBeforeChainShortCircuitingContentFilters() throws Exception {
        final Document doc = webXml();
        // SpaRoutingFilter and WikiPageFormatFilter serve /wiki/* (and the SPA shell) by WRITING the
        // response body and returning WITHOUT calling chain.doFilter. Any /* security-header filter
        // mapped AFTER them in the chain therefore never runs on a server-rendered HTML page — the
        // exact bug behind "no security headers on rendered pages". These header filters must be
        // ordered earlier in the chain than the content-serving filters.
        final int spa = firstMappingIndex( doc, "SpaRoutingFilter" );
        final int fmt = firstMappingIndex( doc, "WikiPageFormatFilter" );
        assertTrue( spa >= 0, "SpaRoutingFilter must be mapped in web.xml" );
        assertTrue( fmt >= 0, "WikiPageFormatFilter must be mapped in web.xml" );
        final int earliestContent = Math.min( spa, fmt );

        for ( final String headerFilter : new String[] {
                "CSPFilter", "ClickJackFilter", "STSFilter", "ContentTypeOptionsFilter",
                "ReferrerPolicyFilter", "COEPFilter", "CORPFilter", "CrossDomainFilter" } ) {
            final int idx = firstMappingIndex( doc, headerFilter );
            assertTrue( idx >= 0, headerFilter + " must be mapped in web.xml" );
            assertTrue( idx < earliestContent,
                    headerFilter + " (filter-mapping #" + idx + ") must be ordered BEFORE the "
                    + "chain-short-circuiting content filters SpaRoutingFilter/WikiPageFormatFilter "
                    + "(filter-mapping #" + earliestContent + ") so its header reaches "
                    + "server-rendered HTML pages" );
        }
    }
}
