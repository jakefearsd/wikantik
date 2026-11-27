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

import com.wikantik.HttpMockFactory;
import com.wikantik.TestEngine;
import com.wikantik.WikiEngine;
import com.wikantik.audit.AuditCategory;
import com.wikantik.audit.AuditEntry;
import com.wikantik.audit.AuditOutcome;
import com.wikantik.audit.AuditReadPolicy;
import com.wikantik.audit.AuditService;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * A read of a page that opts into read auditing ({@code audit_reads: true}, or a cluster listed for it) leaves exactly
 * one {@code page.read} audit entry naming the page and the reader; a page that does not opt in leaves none.
 */
class PageReadHandlerAuditTest {

    private static TestEngine engine;
    private AuditService audit;
    private PageResource servlet;

    @BeforeAll
    static void start() throws Exception {
        engine = TestEngine.build();
        engine.saveText( "AuditedRead", "---\naudit_reads: true\n---\nAudited body.\n" );
        engine.saveText( "AuditedClusterRead", "---\ncluster: audited-cluster\n---\nCluster body.\n" );
        engine.saveText( "UnauditedRead", "---\ntype: article\n---\nPlain body.\n" );
    }

    @AfterAll
    static void stop() {
        if ( engine != null ) {
            engine.deleteQuietly( "AuditedRead", "AuditedClusterRead", "UnauditedRead" );
            engine.stop();
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        audit = mock( AuditService.class );
        final WikiEngine spy = Mockito.spy( engine );
        Mockito.doReturn( audit ).when( spy ).getAuditService();
        Mockito.doReturn( new AuditReadPolicy( name -> "AuditedRead".equals( name ) ? Map.of( "audit_reads", true ) : Map.of(),
                name -> "AuditedClusterRead".equals( name ) ? "audited-cluster" : null,
                Set.of( "audited-cluster" ) ) ).when( spy ).getAuditReadPolicy();
        servlet = RestTestSupport.initServlet( PageResource::new, engine );
        servlet.setEngine( spy );
    }

    private void read( final String page, final String version ) throws Exception {
        final HttpServletRequest request = HttpMockFactory.createIsolatedHttpRequest( "/api/pages/" + page );
        Mockito.doReturn( "/" + page ).when( request ).getPathInfo();
        Mockito.doReturn( version ).when( request ).getParameter( "version" );
        Mockito.doReturn( "203.0.113.9" ).when( request ).getRemoteAddr();
        Mockito.doReturn( "probe-agent/1.0" ).when( request ).getHeader( "User-Agent" );
        final HttpServletResponse response = HttpMockFactory.createHttpResponse();
        Mockito.doReturn( new PrintWriter( new StringWriter() ) ).when( response ).getWriter();
        servlet.doGet( request, response );
    }

    @Test
    void aPageThatOptsInIsAuditedOnceWithTheReaderAndTheirAddress() throws Exception {
        read( "AuditedRead", null );

        final ArgumentCaptor< AuditEntry > entry = ArgumentCaptor.forClass( AuditEntry.class );
        verify( audit, times( 1 ) ).record( entry.capture() );
        final AuditEntry e = entry.getValue();
        assertEquals( AuditCategory.READ, e.category() );
        assertEquals( "page.read", e.eventType() );
        assertEquals( AuditOutcome.SUCCESS, e.outcome() );
        assertEquals( "page", e.targetType() );
        assertEquals( "AuditedRead", e.targetId() );
        assertEquals( "AuditedRead", e.targetLabel() );
        assertEquals( "anonymous", e.actorType() );
        assertNull( e.actorPrincipal() );
        assertEquals( "203.0.113.9", e.sourceIp() );
        assertEquals( "probe-agent/1.0", e.userAgent() );
    }

    @Test
    void aPageInAnAuditedClusterIsAuditedEvenWithoutTheFlag() throws Exception {
        read( "AuditedClusterRead", null );

        verify( audit, times( 1 ) ).record( any() );
    }

    @Test
    void aPageThatDoesNotOptInIsNotAudited() throws Exception {
        read( "UnauditedRead", null );

        verify( audit, never() ).record( any() );
    }

    @Test
    void readingAnOldVersionOfAnOptedInPageIsAuditedToo() throws Exception {
        read( "AuditedRead", "1" );

        verify( audit, times( 1 ) ).record( any() );
    }

    @Test
    void anAuditServiceThatFailsDoesNotFailTheRead() throws Exception {
        Mockito.doThrow( new IllegalStateException( "audit store down" ) ).when( audit ).record( any() );
        final HttpServletRequest request = HttpMockFactory.createIsolatedHttpRequest( "/api/pages/AuditedRead" );
        Mockito.doReturn( "/AuditedRead" ).when( request ).getPathInfo();
        final HttpServletResponse response = HttpMockFactory.createHttpResponse();
        final StringWriter sw = new StringWriter();
        Mockito.doReturn( new PrintWriter( sw ) ).when( response ).getWriter();

        servlet.doGet( request, response );

        verify( audit ).record( any() );
        assertEquals( true, sw.toString().contains( "Audited body." ), sw.toString() );
    }
}
