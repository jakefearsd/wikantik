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

import javax.xml.parsers.DocumentBuilderFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The work-management REST registration in {@code web.xml}: the bearer-key filter sits straight after the Basic-auth
 * filter (before the cookie-based ones, which a key request must not depend on), is async-supported, and the new
 * queue, agent and claimable paths reach their servlets.
 */
class WorkApiRegistrationTest {

    private static Document web() throws Exception {
        final DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setNamespaceAware( false );
        return f.newDocumentBuilder().parse( Files.newInputStream( Path.of( "src", "main", "webapp", "WEB-INF", "web.xml" ) ) );
    }

    private static List< Element > list( final NodeList nl ) {
        final List< Element > out = new ArrayList<>();
        for ( int i = 0; i < nl.getLength(); i++ ) {
            out.add( ( Element ) nl.item( i ) );
        }
        return out;
    }

    private static String text( final Element e, final String tag ) {
        return e.getElementsByTagName( tag ).item( 0 ).getTextContent().trim();
    }

    @Test
    void theKeyFilterFollowsBasicAuthAndPrecedesTheCookieFilters() throws Exception {
        final List< String > order = new ArrayList<>();
        for ( final Element m : list( web().getElementsByTagName( "filter-mapping" ) ) ) {
            order.add( text( m, "filter-name" ) );
        }
        final int basic = order.indexOf( "BasicAuthFilter" );
        assertTrue( basic >= 0 );
        assertEquals( "WorkApiKeyFilter", order.get( basic + 1 ), order.toString() );
        assertTrue( order.indexOf( "RememberMeAuthFilter" ) > basic + 1 );
        assertTrue( order.indexOf( "MustChangePasswordFilter" ) > basic + 1 );
    }

    @Test
    void theKeyFilterIsMappedToWorkOnlyAndIsAsyncSupported() throws Exception {
        for ( final Element m : list( web().getElementsByTagName( "filter-mapping" ) ) ) {
            if ( "WorkApiKeyFilter".equals( text( m, "filter-name" ) ) ) {
                assertEquals( 1, m.getElementsByTagName( "url-pattern" ).getLength() );
                assertEquals( "/api/work/*", text( m, "url-pattern" ) );
            }
        }
        for ( final Element f : list( web().getElementsByTagName( "filter" ) ) ) {
            if ( "WorkApiKeyFilter".equals( text( f, "filter-name" ) ) ) {
                assertEquals( "com.wikantik.rest.WorkApiKeyFilter", text( f, "filter-class" ) );
                assertEquals( "true", text( f, "async-supported" ) );
                return;
            }
        }
        throw new AssertionError( "WorkApiKeyFilter is not declared" );
    }

    @Test
    void theNewPathsReachTheirServlets() throws Exception {
        final java.util.Map< String, String > byPattern = new java.util.HashMap<>();
        for ( final Element m : list( web().getElementsByTagName( "servlet-mapping" ) ) ) {
            for ( final Element p : list( m.getElementsByTagName( "url-pattern" ) ) ) {
                byPattern.put( p.getTextContent().trim(), text( m, "servlet-name" ) );
            }
        }
        assertEquals( "WorkQueueResource", byPattern.get( "/api/work/queues" ) );
        assertEquals( "WorkQueueResource", byPattern.get( "/api/work/queues/*" ) );
        assertEquals( "WorkAgentResource", byPattern.get( "/api/work/agents" ) );
        assertEquals( "WorkAgentResource", byPattern.get( "/api/work/agents/*" ) );
        assertEquals( "WorkItemResource", byPattern.get( "/api/work/claimable" ) );
    }

    @Test
    void theWorkMcpListenerFollowsTheEngineBootstrapAndTheKnowledgeListener() throws Exception {
        final List< String > listeners = new ArrayList<>();
        for ( final Element l : list( web().getElementsByTagName( "listener" ) ) ) {
            listeners.add( text( l, "listener-class" ) );
        }
        final int work = listeners.indexOf( "com.wikantik.work.mcp.WorkMcpInitializer" );
        assertTrue( work >= 0, "WorkMcpInitializer is not registered: " + listeners );
        assertTrue( work > listeners.indexOf( "com.wikantik.bootstrap.WikiBootstrapServletContextListener" ),
            "the engine (and its WorkService) must exist first" );
        assertTrue( work > listeners.indexOf( "com.wikantik.knowledge.mcp.KnowledgeMcpInitializer" ) );
    }

    @Test
    void workMcpIsTimedByTheRequestMetricsFilter() throws Exception {
        for ( final Element m : list( web().getElementsByTagName( "filter-mapping" ) ) ) {
            if ( "RequestMetricsFilter".equals( text( m, "filter-name" ) ) ) {
                final List< String > patterns = new ArrayList<>();
                for ( final Element p : list( m.getElementsByTagName( "url-pattern" ) ) ) {
                    patterns.add( p.getTextContent().trim() );
                }
                assertTrue( patterns.containsAll( List.of( "/work-mcp", "/work-mcp/*" ) ), patterns.toString() );
                return;
            }
        }
        throw new AssertionError( "RequestMetricsFilter has no mapping" );
    }
}
