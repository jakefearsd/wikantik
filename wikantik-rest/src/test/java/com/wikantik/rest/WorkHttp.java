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

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.wikantik.HttpMockFactory;
import com.wikantik.work.Actor;
import com.wikantik.work.Item;
import com.wikantik.work.Autonomy;
import com.wikantik.work.Project;
import com.wikantik.work.WorkKind;
import com.wikantik.work.WorkLevel;
import com.wikantik.work.WorkRole;
import com.wikantik.work.WorkService;
import com.wikantik.work.WorkStatus;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.mockito.Mockito;

import java.io.BufferedReader;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/** Shared plumbing for the {@code Work*ResourceTest} classes: a spied servlet, a fake request, a captured response. */
final class WorkHttp {

    static final Actor ALICE = Actor.human( "alice", Set.of(), false );

    /** What a servlet wrote. */
    record Result( int status, String body, Map< String, String > headers ) {
        Result( final int status, final String body ) {
            this( status, body, Map.of() );
        }

        JsonElement json() {
            return JsonParser.parseString( body );
        }
    }

    private WorkHttp() {
    }

    /** A servlet wired to {@code svc} and {@code actor}; display names read "Name of &lt;login&gt;". */
    static < T extends WorkResourceBase > T wire( final T servlet, final WorkService svc, final Actor actor ) {
        final T spy = Mockito.spy( servlet );
        Mockito.doReturn( svc ).when( spy ).workService();
        Mockito.doReturn( actor ).when( spy ).actor( Mockito.any() );
        Mockito.doAnswer( inv -> "Name of " + inv.getArgument( 0 ) ).when( spy ).displayName( Mockito.anyString() );
        Mockito.doAnswer( inv -> java.util.Optional.of( "id-" + inv.getArgument( 0 ).hashCode() ) ).when( spy )
            .mentionId( Mockito.anyString() );
        return spy;
    }

    /**
     * A servlet wired like {@link #wire} except that the caller is worked out by the real {@code actor(request)}:
     * from the request's key record, or else {@code session} (null = anonymous) when it carries no bearer header.
     */
    static < T extends WorkResourceBase > T wireKeyed( final T servlet, final WorkService svc, final Actor session ) {
        final T spy = Mockito.spy( servlet );
        Mockito.doReturn( svc ).when( spy ).workService();
        Mockito.doReturn( session ).when( spy ).sessionActor( Mockito.any() );
        Mockito.doAnswer( inv -> "Name of " + inv.getArgument( 0 ) ).when( spy ).displayName( Mockito.anyString() );
        return spy;
    }

    static Result call( final WorkResourceBase servlet, final String method, final String servletPath,
                        final String pathInfo, final Map< String, String > query, final String jsonBody )
            throws Exception {
        return call( servlet, method, servletPath, pathInfo, query, jsonBody, req -> { } );
    }

    /** As above; {@code tweak} adjusts the mock request (a header, an attribute) before the call. */
    static Result call( final WorkResourceBase servlet, final String method, final String servletPath,
                        final String pathInfo, final Map< String, String > query, final String jsonBody,
                        final java.util.function.Consumer< HttpServletRequest > tweak )
            throws Exception {
        final HttpServletRequest req = HttpMockFactory.createHttpRequest( servletPath );
        tweak.accept( req );
        Mockito.doReturn( method ).when( req ).getMethod();
        Mockito.doReturn( servletPath ).when( req ).getServletPath();
        Mockito.doReturn( pathInfo ).when( req ).getPathInfo();
        for ( final Map.Entry< String, String > e : query.entrySet() ) {
            Mockito.doReturn( e.getValue() ).when( req ).getParameter( e.getKey() );
        }
        if ( jsonBody != null ) {
            Mockito.doReturn( new BufferedReader( new StringReader( jsonBody ) ) ).when( req ).getReader();
            Mockito.doReturn( jsonBody.length() ).when( req ).getContentLength();
        }
        final HttpServletResponse resp = HttpMockFactory.createHttpResponse();
        final StringWriter sw = new StringWriter();
        final AtomicInteger status = new AtomicInteger( 200 );
        Mockito.doReturn( new PrintWriter( sw ) ).when( resp ).getWriter();
        Mockito.doAnswer( inv -> {
            status.set( inv.getArgument( 0 ) );
            return null;
        } ).when( resp ).setStatus( Mockito.anyInt() );
        final java.util.Map< String, String > headers = new java.util.HashMap<>();
        Mockito.doAnswer( inv -> {
            headers.put( inv.getArgument( 0 ), inv.getArgument( 1 ) );
            return null;
        } ).when( resp ).setHeader( Mockito.anyString(), Mockito.anyString() );
        servlet.service( req, resp );
        return new Result( status.get(), sw.toString(), headers );
    }

    static Project project( final String key ) {
        return new Project( 1, key, key + " name", "desc", Autonomy.SUPERVISED, Instant.parse( "2026-11-01T10:00:00Z" ),
            null );
    }

    static Item item( final String key, final String assignee, final int version ) {
        return new Item( 7, 1, key, WorkLevel.TASK, null, null, WorkKind.SOFTWARE, "Title", "Body", WorkStatus.DRAFT, 3,
            1.0, null, assignee, "bob", null, null, null, version, Instant.parse( "2026-11-01T10:00:00Z" ),
            Instant.parse( "2026-11-02T10:00:00Z" ), null, null, null, null, null, null );
    }

    static WorkService.ItemSummary summary( final Item item, final int comments, final boolean blocked,
                                            final String productKey, final String parentKey ) {
        return new WorkService.ItemSummary( item, comments, blocked, productKey, parentKey );
    }

    static WorkService.ItemView view( final String key, final String assignee, final int version ) {
        return new WorkService.ItemView( item( key, assignee, version ), "<p>Body</p>", "PLAT", null, null, List.of(),
            List.of(), List.of(), false, WorkRole.MEMBER, false, null );
    }
}
