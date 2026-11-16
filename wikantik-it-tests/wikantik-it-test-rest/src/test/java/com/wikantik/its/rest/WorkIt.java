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
package com.wikantik.its.rest;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.CookieHandler;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

/** Shared HTTP plumbing for the work-management ITs: cookie-isolated callers, account creation, polling. */
final class WorkIt {

    static final Gson GSON = new Gson();
    private static final String INITIAL_PASSWORD = "WorkIt-Init1!";
    private static final String PASSWORD = "WorkIt-Final2!";

    private WorkIt() {
    }

    static String baseUrl() {
        return System.getProperty( "it-wikantik.base.url", "http://localhost:18080/wikantik-it-test-rest" );
    }

    /** One caller with its own cookie jar. */
    static final class Caller {
        private final HttpClient client = HttpClient.newBuilder().followRedirects( HttpClient.Redirect.NORMAL )
            .cookieHandler( secureCookieOverHttp() ).build();

        HttpResponse< String > get( final String path ) throws Exception {
            return client.send( HttpRequest.newBuilder().uri( URI.create( baseUrl() + path ) )
                .header( "Accept", "application/json" ).GET().build(), HttpResponse.BodyHandlers.ofString() );
        }

        HttpResponse< String > send( final String method, final String path, final Object body ) throws Exception {
            final String json = body == null ? "" : body instanceof String s ? s : GSON.toJson( body );
            return client.send( HttpRequest.newBuilder().uri( URI.create( baseUrl() + path ) )
                .header( "Content-Type", "application/json" ).header( "Accept", "application/json" )
                .method( method, HttpRequest.BodyPublishers.ofString( json ) ).build(),
                HttpResponse.BodyHandlers.ofString() );
        }

        Caller login( final String user, final String password ) throws Exception {
            final HttpResponse< String > resp = send( "POST", "/api/auth/login",
                Map.of( "username", user, "password", password ) );
            assertEquals( 200, resp.statusCode(), "login as " + user + ": " + resp.body() );
            return this;
        }
    }

    static Caller admin() throws Exception {
        return new Caller().login( "janne", "myP@5sw0rd" );
    }

    /** An ordinary account as the admin API creates it, past the forced first-login password change. */
    static Caller account( final Caller admin, final String login, final String display ) throws Exception {
        final HttpResponse< String > created = admin.send( "POST", "/admin/users", Map.of( "loginName", login,
            "fullName", display, "email", login + "@example.com", "password", INITIAL_PASSWORD ) );
        assertEquals( 201, created.statusCode(), "create " + login + ": " + created.body() );
        final Caller caller = new Caller().login( login, INITIAL_PASSWORD );
        final HttpResponse< String > changed = caller.send( "PUT", "/api/auth/profile", Map.of(
            "currentPassword", INITIAL_PASSWORD, "newPassword", PASSWORD ) );
        assertEquals( 200, changed.statusCode(), "password change for " + login + ": " + changed.body() );
        return caller;
    }

    /** A client with no cookie jar: every request carries exactly the headers it is given. */
    private static final HttpClient PLAIN = HttpClient.newBuilder().followRedirects( HttpClient.Redirect.NEVER ).build();

    /**
     * One request that carries only {@code Authorization: Bearer <token>} (no cookie jar, no session), plus any extra
     * headers given as name/value pairs. A null token sends no Authorization header at all.
     */
    static HttpResponse< String > bearer( final String token, final String method, final String path, final String body,
                                          final String... extraHeaders ) throws Exception {
        final HttpRequest.Builder b = HttpRequest.newBuilder().uri( URI.create( baseUrl() + path ) )
            .header( "Accept", "application/json" )
            .method( method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString( body ) );
        if ( body != null ) {
            b.header( "Content-Type", "application/json" );
        }
        if ( token != null ) {
            b.header( "Authorization", "Bearer " + token );
        }
        for ( int i = 0; i + 1 < extraHeaders.length; i += 2 ) {
            b.setHeader( extraHeaders[ i ], extraHeaders[ i + 1 ] );
        }
        return PLAIN.send( b.build(), HttpResponse.BodyHandlers.ofString() );
    }

    /** Every {@code Set-Cookie} header of the response, as sent. */
    static List< String > setCookies( final HttpResponse< ? > r ) {
        return r.headers().allValues( "Set-Cookie" );
    }

    /** An agent registered by the admin; the token is kept in memory only, never printed. */
    record Agent( long id, String name, String token ) {
    }

    /** Registers an agent through the admin API (201) and keeps its one-time token. */
    static Agent registerAgent( final Caller admin, final String name, final String... queues ) throws Exception {
        final HttpResponse< String > r = admin.send( "POST", "/api/work/agents", Map.of( "name", name,
            "vendor", "it", "runtime", "it", "queues", List.of( queues ) ) );
        assertEquals( 201, r.statusCode(), "register " + name + ": " + status( r ) );
        final com.google.gson.JsonObject o = json( r ).getAsJsonObject();
        return new Agent( o.getAsJsonObject( "agent" ).get( "id" ).getAsLong(), name, o.get( "token" ).getAsString() );
    }

    /** The status line only: response bodies of key-bearing calls are not written to logs or messages. */
    static String status( final HttpResponse< String > r ) {
        return r.statusCode() + ( r.statusCode() >= 400 ? " " + r.body() : "" );
    }

    static JsonElement json( final HttpResponse< String > r ) {
        return JsonParser.parseString( r.body() );
    }

    /** Polls until a 200 answer satisfies {@code ready}; fails with the last answer otherwise. */
    static String await( final String surface, final Caller who, final String path, final Predicate< String > ready )
            throws Exception {
        final long deadline = System.currentTimeMillis() + 90_000L;
        HttpResponse< String > last = null;
        while ( System.currentTimeMillis() < deadline ) {
            last = who.get( path );
            if ( last.statusCode() == 200 && ready.test( last.body() ) ) {
                return last.body();
            }
            Thread.sleep( 500 );
        }
        fail( "positive control: " + surface + " never showed the control page within 90s; last: "
            + ( last == null ? "(none)" : last.statusCode() + " " + last.body() ) );
        return null;
    }

    private static CookieHandler secureCookieOverHttp() {
        final CookieManager cm = new CookieManager( null, CookiePolicy.ACCEPT_ALL );
        return new CookieHandler() {
            @Override
            public Map< String, List< String > > get( final URI uri, final Map< String, List< String > > h )
                    throws IOException {
                return cm.get( URI.create( uri.toString().replaceFirst( "^http:", "https:" ) ), h );
            }

            @Override
            public void put( final URI uri, final Map< String, List< String > > h ) throws IOException {
                cm.put( uri, h );
            }
        };
    }
}
