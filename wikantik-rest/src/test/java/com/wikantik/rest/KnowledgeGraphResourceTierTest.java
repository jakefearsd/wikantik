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

import com.wikantik.api.core.Engine;
import com.wikantik.api.core.Session;
import com.wikantik.api.knowledge.GraphSnapshot;
import com.wikantik.api.knowledge.KnowledgeGraphService;
import com.wikantik.api.knowledge.Tier;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class KnowledgeGraphResourceTierTest {

    /**
     * Subclass that bypasses servlet container init: provides our own engine
     * + wiki session so we can call doGet directly without Tomcat.
     */
    private static class TestableResource extends KnowledgeGraphResource {
        private final Engine engine;
        TestableResource( Engine engine ) { this.engine = engine; }
        @Override protected Engine getEngine() { return engine; }
        @Override
        protected Session findSession( Engine eng, HttpServletRequest req ) {
            return mock( Session.class );
        }
    }

    private HttpServletResponse newResponse() throws IOException {
        final HttpServletResponse resp = mock( HttpServletResponse.class );
        when( resp.getWriter() ).thenReturn( new PrintWriter( new StringWriter() ) );
        return resp;
    }

    @Test
    void doGet_passes_human_when_min_tier_human() throws Exception {
        final HttpServletRequest req = mock( HttpServletRequest.class );
        when( req.getParameter( "min_tier" ) ).thenReturn( "human" );
        final HttpServletResponse resp = newResponse();

        final KnowledgeGraphService svc = mock( KnowledgeGraphService.class );
        when( svc.snapshotGraph( any( Session.class ), eq( Tier.HUMAN ) ) )
            .thenReturn( new GraphSnapshot( "ts", 0, 0, 0, List.of(), List.of() ) );

        final Engine engine = mock( Engine.class );
        when( engine.getManager( KnowledgeGraphService.class ) ).thenReturn( svc );
        when( engine.getWikiProperties() ).thenReturn( new Properties() );

        new TestableResource( engine ).doGet( req, resp );

        verify( svc ).snapshotGraph( any( Session.class ), eq( Tier.HUMAN ) );
    }

    @Test
    void doGet_defaults_to_machine_when_no_param() throws Exception {
        final HttpServletRequest req = mock( HttpServletRequest.class );
        when( req.getParameter( "min_tier" ) ).thenReturn( null );
        final HttpServletResponse resp = newResponse();

        final KnowledgeGraphService svc = mock( KnowledgeGraphService.class );
        when( svc.snapshotGraph( any( Session.class ), eq( Tier.MACHINE ) ) )
            .thenReturn( new GraphSnapshot( "ts", 0, 0, 0, List.of(), List.of() ) );

        final Engine engine = mock( Engine.class );
        when( engine.getManager( KnowledgeGraphService.class ) ).thenReturn( svc );
        when( engine.getWikiProperties() ).thenReturn( new Properties() );

        new TestableResource( engine ).doGet( req, resp );

        verify( svc ).snapshotGraph( any( Session.class ), eq( Tier.MACHINE ) );
    }

    @Test
    void doGet_default_overridden_by_property() throws Exception {
        final HttpServletRequest req = mock( HttpServletRequest.class );
        when( req.getParameter( "min_tier" ) ).thenReturn( null );
        final HttpServletResponse resp = newResponse();

        final KnowledgeGraphService svc = mock( KnowledgeGraphService.class );
        when( svc.snapshotGraph( any( Session.class ), eq( Tier.HUMAN ) ) )
            .thenReturn( new GraphSnapshot( "ts", 0, 0, 0, List.of(), List.of() ) );

        final Properties props = new Properties();
        props.setProperty( "wikantik.kg.read.default_min_tier", "human" );

        final Engine engine = mock( Engine.class );
        when( engine.getManager( KnowledgeGraphService.class ) ).thenReturn( svc );
        when( engine.getWikiProperties() ).thenReturn( props );

        new TestableResource( engine ).doGet( req, resp );

        verify( svc ).snapshotGraph( any( Session.class ), eq( Tier.HUMAN ) );
    }

    @Test
    void doGet_returns_400_on_invalid_min_tier() throws Exception {
        final HttpServletRequest req = mock( HttpServletRequest.class );
        when( req.getParameter( "min_tier" ) ).thenReturn( "garbage" );
        final HttpServletResponse resp = mock( HttpServletResponse.class );
        when( resp.getWriter() ).thenReturn( new PrintWriter( new StringWriter() ) );

        final Engine engine = mock( Engine.class );
        new TestableResource( engine ).doGet( req, resp );

        verify( resp ).setStatus( HttpServletResponse.SC_BAD_REQUEST );
    }
}
