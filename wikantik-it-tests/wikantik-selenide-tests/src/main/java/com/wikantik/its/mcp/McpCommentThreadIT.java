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
package com.wikantik.its.mcp;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.wikantik.its.environment.Env;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Type;
import java.net.CookieHandler;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;

/**
 * Wire-level Cargo IT for the agent comment feedback loop on {@code /wikantik-admin-mcp}:
 * an editor opens a thread over REST, the agent finds it with {@code list_comment_threads}
 * (actionable, because the commenter could edit the page), resolves it with
 * {@code respond_to_comment_thread}, and REST then shows it resolved with a {@code viaAgent}
 * last comment. A second {@code dismiss} is refused because the thread is closed.
 */
public class McpCommentThreadIT extends WithMcpTestSetup {

    private static final Gson GSON = new Gson();
    private static final Type MAP_TYPE = new TypeToken< Map< String, Object > >() { }.getType();

    private final String base = Env.TESTS_BASE_URL.endsWith( "/" )
            ? Env.TESTS_BASE_URL.substring( 0, Env.TESTS_BASE_URL.length() - 1 ) : Env.TESTS_BASE_URL;
    private final HttpClient http = HttpClient.newBuilder().cookieHandler( secureCookieOverHttp() ).build();

    /** The session cookie is Secure; Java's cookie store drops it on http URIs unless the scheme is rewritten. */
    private static CookieHandler secureCookieOverHttp() {
        final CookieManager cm = new CookieManager( null, CookiePolicy.ACCEPT_ALL );
        return new CookieHandler() {
            @Override
            public Map< String, List< String > > get( final URI uri, final Map< String, List< String > > h ) throws IOException {
                return cm.get( URI.create( uri.toString().replaceFirst( "^http:", "https:" ) ), h );
            }

            @Override
            public void put( final URI uri, final Map< String, List< String > > h ) throws IOException {
                cm.put( uri, h );
            }
        };
    }

    private HttpResponse< String > send( final String method, final String path, final String json ) throws Exception {
        final HttpRequest.Builder b = HttpRequest.newBuilder().uri( URI.create( base + path ) )
                .header( "Content-Type", "application/json" ).header( "Accept", "application/json" );
        return http.send( b.method( method, json == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString( json ) ).build(), HttpResponse.BodyHandlers.ofString() );
    }

    @Test
    @SuppressWarnings( "unchecked" )
    public void agentResolvesThenSecondDismissIsRefused() throws Exception {
        final Map< String, Object > loginBody = Map.of( "username", "janne", "password", "myP@5sw0rd" );
        Assertions.assertEquals( 200, send( "POST", "/api/auth/login", GSON.toJson( loginBody ) ).statusCode() );

        final String slug = uniquePageName( "CommentLoop" );
        final String anchor = "the quick brown fox";
        Assertions.assertEquals( 200, send( "PUT", "/api/pages/" + slug, GSON.toJson( Map.of(
                "content", "Body containing " + anchor + " for anchoring.", "changeNote", "seed" ) ) ).statusCode() );
        // the structural index picks the page up asynchronously; thread creation needs its slug to resolve
        HttpResponse< String > created = null;
        for ( int i = 0; i < 30; i++ ) {
            created = send( "POST", "/api/comment-threads?page=" + slug, GSON.toJson( Map.of(
                    "exact", anchor, "prefix", "", "suffix", "", "text", "please clarify this" ) ) );
            if ( created.statusCode() == 200 ) break;
            Thread.sleep( 1000 );
        }
        Assertions.assertNotNull( created );
        Assertions.assertEquals( 200, created.statusCode(), created.body() );
        final String threadId = ( String ) ( ( Map< String, Object > ) GSON.fromJson( created.body(), MAP_TYPE ) ).get( "id" );

        final Map< String, Object > listed = mcp.callTool( "list_comment_threads", Map.of( "slug", slug ) );
        final List< Map< String, Object > > threads = ( List< Map< String, Object > > ) listed.get( "threads" );
        Assertions.assertEquals( 1, threads.size(), "expected exactly the new thread: " + listed );
        Assertions.assertEquals( threadId, threads.get( 0 ).get( "threadId" ) );
        Assertions.assertEquals( Boolean.TRUE, threads.get( 0 ).get( "actionable" ),
                "an editor's comment makes the thread actionable: " + threads.get( 0 ) );

        final Map< String, Object > resolved = mcp.callTool( "respond_to_comment_thread",
                Map.of( "threadId", threadId, "action", "resolve", "body", "clarified in the page" ) );
        Assertions.assertEquals( "resolved", resolved.get( "status" ), resolved.toString() );

        final HttpResponse< String > after = send( "GET", "/api/comment-threads?page=" + slug + "&status=resolved", null );
        Assertions.assertEquals( 200, after.statusCode(), after.body() );
        final Map< String, Object > afterJson = GSON.fromJson( after.body(), MAP_TYPE );
        final List< Map< String, Object > > resolvedThreads = ( List< Map< String, Object > > ) afterJson.get( "threads" );
        Assertions.assertEquals( 1, resolvedThreads.size(), after.body() );
        Assertions.assertEquals( "resolved", resolvedThreads.get( 0 ).get( "status" ) );
        final List< Map< String, Object > > comments = ( List< Map< String, Object > > ) resolvedThreads.get( 0 ).get( "comments" );
        Assertions.assertEquals( Boolean.TRUE, comments.get( comments.size() - 1 ).get( "viaAgent" ),
                "the last comment is the agent's: " + comments );

        final Map< String, Object > refused = mcp.callToolExpectingError( "respond_to_comment_thread",
                Map.of( "threadId", threadId, "action", "dismiss", "body", "too late" ) );
        Assertions.assertEquals( "Thread is no longer open", refused.get( "error" ), refused.toString() );
    }
}
