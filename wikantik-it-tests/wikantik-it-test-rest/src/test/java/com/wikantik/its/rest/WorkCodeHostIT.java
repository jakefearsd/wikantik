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

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.wikantik.its.rest.WorkIt.Caller;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import static com.wikantik.its.rest.WorkIt.json;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The code-host hook end to end against the real container: the inbound endpoint ({@code POST
 * /api/work/hooks/{product}/{github|generic}}, no session, no credential but the HMAC over the body), the admin routes
 * that turn it on and off, and what a delivery does to change records. Every delivery is signed by this class with the
 * secret the enable route returned, over the exact bytes it sends.
 *
 * <p>Every method builds its own product, project, accounts, items and secret (a per-method random tag in each key and
 * login), takes keys and ids from the server's answers, and asserts "this lane has / lacks THIS item key", never "the
 * lane is empty" (the admin's lane and other ITs' projects are shared). No method depends on another. Times in
 * payloads are fixed past instants. The REST module's cascade sets {@code wikantik.work.codehost.enabled = true} and
 * {@code wikantik.work.codehost.maxBodyBytes = 65536}; no payload here is anywhere near 64 KiB except the one that
 * means to be.
 *
 * <p>Anything not derivable from the Java contract is marked {@code // ASSUMED:}.
 */
public class WorkCodeHostIT {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final HttpClient PLAIN = HttpClient.newBuilder().followRedirects( HttpClient.Redirect.NEVER ).build();
    private static final String REPO = "acme/it";
    private static final String NOT_FOUND = "{\"error\":true,\"status\":404,\"message\":\"Not found.\"}";
    private static final String FLAG_UNREVIEWED = "unreviewed";
    private static final String REASON_LANE = "change_outside_policy";

    private static Caller admin;

    @BeforeAll
    static void setUp() throws Exception {
        admin = WorkIt.admin();
    }

    // ---------------------------------------------------------------- fixtures

    /** One method's world: a product with a hook, a project, a lead, an author who is a member, and a stranger. */
    private static final class Env {
        String tag;
        String product;
        String project;
        String secret;
        String leadLogin;
        Caller lead;
        String authorLogin;
        String authorName;
        String authorEmail;
        Caller author;
        Caller stranger;
    }

    private static String tag() {
        final StringBuilder sb = new StringBuilder();
        for ( int i = 0; i < 6; i++ ) {
            sb.append( ( char ) ( 'a' + RANDOM.nextInt( 26 ) ) );
        }
        return sb.toString();
    }

    private static Env env( final boolean enableHook ) throws Exception {
        final Env e = new Env();
        e.tag = tag();
        e.product = "hookprod" + e.tag;
        e.project = "HK" + e.tag.toUpperCase( Locale.ROOT );
        final String word = Character.toUpperCase( e.tag.charAt( 0 ) ) + e.tag.substring( 1 );
        e.leadLogin = "hklead" + e.tag;
        e.authorLogin = "hkauth" + e.tag;
        e.authorName = "Hook Author " + word;
        e.authorEmail = e.authorLogin + "@Example.COM"; // mixed case on purpose: the match ignores case
        e.lead = WorkIt.account( admin, e.leadLogin, "Hook Lead " + word );
        e.author = WorkIt.account( admin, e.authorLogin, e.authorName );
        e.stranger = WorkIt.account( admin, "hkstr" + e.tag, "Hook Stranger " + word );
        assertEquals( 201, admin.send( "POST", "/api/work/products",
            Map.of( "key", e.product, "name", "Hook product " + e.tag, "defaultBranch", "main" ) ).statusCode() );
        assertEquals( 201, admin.send( "POST", "/api/work/projects",
            Map.of( "key", e.project, "name", e.project, "description", "d" ) ).statusCode() );
        member( e, e.leadLogin, "lead" );
        member( e, e.authorLogin, "member" );
        if ( enableHook ) {
            e.secret = enable( e );
        }
        return e;
    }

    private static void member( final Env e, final String login, final String role ) throws Exception {
        assertEquals( 204, admin.send( "PUT", "/api/work/projects/" + e.project + "/members", Map.of( "principal", login,
            "principalKind", "user", "role", role ) ).statusCode() );
    }

    /** The admin turns the product's hook on (or rotates it); returns the secret. */
    private static String enable( final Env e ) throws Exception {
        final HttpResponse< String > r = admin.send( "POST", "/api/work/products/" + e.product + "/hook", null );
        assertEquals( 200, r.statusCode(), "enable hook: " + r.statusCode() );
        return json( r ).getAsJsonObject().get( "secret" ).getAsString();
    }

    /** An item targeting the product, assigned to the author; returns the key the server gave it. */
    private static String item( final Env e ) throws Exception {
        final HttpResponse< String > made = e.lead.send( "POST", "/api/work/items", Map.of( "project", e.project,
            "level", "task", "title", "Hook item " + e.tag, "productKey", e.product, "assignee", e.authorName ) );
        assertEquals( 201, made.statusCode(), made.body() );
        return json( made ).getAsJsonObject().getAsJsonObject( "item" ).get( "key" ).getAsString();
    }

    // ---------------------------------------------------------------- HTTP

    private static String hmac( final String secret, final byte[] body ) throws Exception {
        final Mac mac = Mac.getInstance( "HmacSHA256" );
        mac.init( new SecretKeySpec( secret.getBytes( StandardCharsets.UTF_8 ), "HmacSHA256" ) );
        return HexFormat.of().formatHex( mac.doFinal( body ) );
    }

    /** One request with no cookie, no session, no Authorization and no Origin unless the caller adds them. Retries only a 429. */
    private static HttpResponse< String > raw( final String method, final String path, final byte[] body,
                                               final String... headers ) throws Exception {
        for ( int attempt = 0; ; attempt++ ) {
            final HttpRequest.Builder b = HttpRequest.newBuilder().uri( URI.create( WorkIt.baseUrl() + path ) )
                .method( method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray( body ) );
            for ( int i = 0; i + 1 < headers.length; i += 2 ) {
                b.setHeader( headers[ i ], headers[ i + 1 ] );
            }
            final HttpResponse< String > r = PLAIN.send( b.build(), HttpResponse.BodyHandlers.ofString() );
            if ( r.statusCode() != 429 || attempt >= 6 ) {
                return r;
            }
            Thread.sleep( 1_200 ); // the per-IP limit is not what this IT is about; wait it out
        }
    }

    private static String hookPath( final String product, final String format ) {
        return "/api/work/hooks/" + product + "/" + format;
    }

    /** A correctly signed GitHub delivery. */
    private static HttpResponse< String > github( final Env e, final String event, final String json ) throws Exception {
        return githubSigned( e.product, e.secret, event, json.getBytes( StandardCharsets.UTF_8 ), "application/json" );
    }

    private static HttpResponse< String > githubSigned( final String product, final String secret, final String event,
                                                        final byte[] body, final String contentType ) throws Exception {
        return raw( "POST", hookPath( product, "github" ), body, "Content-Type", contentType,
            "X-GitHub-Event", event, "X-GitHub-Delivery", UUID.randomUUID().toString(),
            "X-Hub-Signature-256", "sha256=" + hmac( secret, body ) );
    }

    private static HttpResponse< String > generic( final Env e, final String json ) throws Exception {
        final byte[] body = json.getBytes( StandardCharsets.UTF_8 );
        return raw( "POST", hookPath( e.product, "generic" ), body, "Content-Type", "application/json",
            "X-Wikantik-Signature", "sha256=" + hmac( e.secret, body ) );
    }

    private static void accepted( final HttpResponse< String > r ) {
        assertEquals( 204, r.statusCode(), "a verified delivery is accepted: " + r.statusCode() + " " + r.body() );
    }

    // ---------------------------------------------------------------- payloads (only the fields the adapters read)

    private static JsonObject obj( final Object... kv ) {
        final JsonObject o = new JsonObject();
        for ( int i = 0; i < kv.length; i += 2 ) {
            final String k = ( String ) kv[ i ];
            final Object v = kv[ i + 1 ];
            if ( v == null ) {
                continue;
            }
            switch ( v ) {
                case JsonElement j -> o.add( k, j );
                case Boolean b -> o.addProperty( k, b );
                case Number n -> o.addProperty( k, n );
                default -> o.addProperty( k, v.toString() );
            }
        }
        return o;
    }

    /** Minute {@code m} of a fixed past hour. */
    private static String t( final int m ) {
        return String.format( "2026-01-10T10:%02d:00Z", m );
    }

    private static String sha() {
        final byte[] b = new byte[ 20 ];
        RANDOM.nextBytes( b );
        return HexFormat.of().formatHex( b );
    }

    private static JsonObject commit( final String sha, final String message, final String at, final String name,
                                      final String email, final String username, final boolean distinct ) {
        return obj( "id", sha, "message", message, "timestamp", at, "distinct", distinct,
            "author", obj( "name", name, "email", email, "username", username ) );
    }

    private static String push( final String branch, final String before, final String after, final String at,
                                final JsonObject... commits ) {
        final JsonArray list = new JsonArray();
        for ( final JsonObject c : commits ) {
            list.add( c );
        }
        return obj( "ref", "refs/heads/" + branch, "before", before, "after", after, "commits", list,
            "repository", obj( "full_name", REPO, "default_branch", "main", "pushed_at", Instant.parse( at ).getEpochSecond() ),
            "head_commit", obj( "timestamp", at ) ).toString();
    }

    /** One pull request as the adapters read it. */
    private record Pr( int number, String branch, String head, String title, String user ) {
    }

    private static Pr pr( final String itemKey, final String user ) {
        return new Pr( 100 + RANDOM.nextInt( 800_000 ), itemKey + "-fix", sha(), itemKey + " fix", user );
    }

    private static JsonObject prBody( final Pr p, final String at, final Object... extra ) {
        final JsonObject pr = obj( "number", p.number, "title", p.title, "html_url", "https://github.com/" + REPO + "/pull/" + p.number,
            "draft", false, "user", obj( "login", p.user ), "head", obj( "ref", p.branch, "sha", p.head ),
            "base", obj( "ref", "main" ), "created_at", t( 1 ), "updated_at", at );
        for ( int i = 0; i < extra.length; i += 2 ) {
            final Object v = extra[ i + 1 ];
            switch ( v ) {
                case JsonElement j -> pr.add( ( String ) extra[ i ], j );
                case Boolean b -> pr.addProperty( ( String ) extra[ i ], b );
                default -> pr.addProperty( ( String ) extra[ i ], v.toString() );
            }
        }
        return pr;
    }

    private static String prEvent( final String action, final JsonObject pr ) {
        return obj( "action", action, "number", pr.get( "number" ), "pull_request", pr,
            "repository", obj( "full_name", REPO, "default_branch", "main" ) ).toString();
    }

    private static String opened( final Pr p, final int minute ) {
        return prEvent( "opened", prBody( p, t( minute ), "created_at", t( minute ) ) );
    }

    private static String reopened( final Pr p, final int minute ) {
        return prEvent( "reopened", prBody( p, t( minute ) ) );
    }

    private static String closed( final Pr p, final int minute ) {
        return prEvent( "closed", prBody( p, t( minute ), "merged", false, "closed_at", t( minute ) ) );
    }

    private static String merged( final Pr p, final String mergedBy, final String mergeSha, final int minute ) {
        return prEvent( "closed", prBody( p, t( minute ), "merged", true, "merged_at", t( minute ), "closed_at", t( minute ),
            "merged_by", obj( "login", mergedBy ), "merge_commit_sha", mergeSha ) );
    }

    private static String approved( final Pr p, final String reviewer, final int minute ) {
        return obj( "action", "submitted", "review", obj( "state", "approved", "commit_id", p.head, "submitted_at", t( minute ),
            "user", obj( "login", reviewer ) ), "pull_request", prBody( p, t( minute ) ),
            "repository", obj( "full_name", REPO, "default_branch", "main" ) ).toString();
    }

    // ---------------------------------------------------------------- reads

    private static JsonArray changes( final String itemKey ) throws Exception {
        final HttpResponse< String > r = admin.get( "/api/work/items/" + itemKey + "/changes" );
        assertEquals( 200, r.statusCode(), r.body() );
        return json( r ).getAsJsonObject().getAsJsonArray( "changes" );
    }

    /** The changes that count: a superseded row (a direct commit the pull request's merge replaced) counts nowhere. */
    private static List< JsonObject > counted( final String itemKey ) throws Exception {
        final List< JsonObject > out = new ArrayList<>();
        for ( final JsonElement c : changes( itemKey ) ) {
            // the API serializes nulls: a live row carries "supersededBy": null
            final JsonElement by = c.getAsJsonObject().get( "supersededBy" );
            if ( by == null || by.isJsonNull() ) {
                out.add( c.getAsJsonObject() );
            }
        }
        return out;
    }

    private static JsonObject theChange( final String itemKey ) throws Exception {
        final List< JsonObject > list = counted( itemKey );
        assertEquals( 1, list.size(), "exactly one counted change on " + itemKey + ": " + list );
        return list.get( 0 );
    }

    private static String str( final JsonObject o, final String key ) {
        final JsonElement v = o.get( key );
        return v == null || v.isJsonNull() ? null : v.getAsString();
    }

    private static JsonArray events( final String itemKey ) throws Exception {
        final HttpResponse< String > r = admin.get( "/api/work/items/" + itemKey + "/events" );
        assertEquals( 200, r.statusCode(), r.body() );
        return json( r ).getAsJsonArray();
    }

    /** The fields no code-host delivery may ever change. */
    private static List< String > itemState( final String itemKey ) throws Exception {
        final HttpResponse< String > r = admin.get( "/api/work/items/" + itemKey );
        assertEquals( 200, r.statusCode(), r.body() );
        final JsonObject view = json( r ).getAsJsonObject();
        final JsonObject item = view.has( "item" ) ? view.getAsJsonObject( "item" ) : view;
        return List.of( String.valueOf( str( item, "status" ) ), String.valueOf( str( item, "assignee" ) ),
            String.valueOf( str( item, "assigneeName" ) ), String.valueOf( str( item, "sprintId" ) ),
            String.valueOf( str( item, "version" ) ) );
    }

    private static boolean inLane( final Caller who, final String itemKey ) throws Exception {
        final HttpResponse< String > r = who.get( "/api/work/me" );
        assertEquals( 200, r.statusCode(), r.body() );
        for ( final JsonElement w : json( r ).getAsJsonObject().getAsJsonArray( "waitingOn" ) ) {
            final JsonObject o = w.getAsJsonObject();
            if ( itemKey.equals( str( o, "key" ) ) && REASON_LANE.equals( str( o.getAsJsonObject( "waiting" ), "reason" ) ) ) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasEvent( final String itemKey, final String type ) throws Exception {
        for ( final JsonElement e : events( itemKey ) ) {
            if ( type.equals( str( e.getAsJsonObject(), "type" ) ) ) {
                return true;
            }
        }
        return false;
    }

    // ---------------------------------------------------------------- the management routes

    @Test
    void theSecretIsShownOnceToAWikiAdminAndOtherCallersLearnNothingOfTheHook() throws Exception {
        final Env e = env( false );
        final HttpResponse< String > r = admin.send( "POST", "/api/work/products/" + e.product + "/hook", null );
        assertEquals( 200, r.statusCode() );
        assertTrue( r.headers().firstValue( "Cache-Control" ).orElse( "" ).contains( "no-store" ),
            "the response that carries a secret is not cacheable" );
        final JsonObject made = json( r ).getAsJsonObject();
        assertEquals( 3, made.size(), "exactly secret, githubUrl, genericUrl: " + made.keySet() );
        final String secret = made.get( "secret" ).getAsString();
        assertTrue( secret.matches( "[0-9a-f]{64}" ), "a 256-bit hex secret" );
        assertTrue( made.get( "githubUrl" ).getAsString().endsWith( hookPath( e.product, "github" ) ) );
        assertTrue( made.get( "genericUrl" ).getAsString().endsWith( hookPath( e.product, "generic" ) ) );
        assertFalse( made.get( "githubUrl" ).getAsString().contains( secret ), "the URL holds no secret" );

        // the secret appears in no later read, by anyone
        final List< String > reads = new ArrayList<>();
        reads.add( admin.get( "/api/work/products" ).body() );
        reads.add( admin.get( "/api/work/products/" + e.product + "/history" ).body() );
        reads.add( e.lead.get( "/api/work/products" ).body() );
        reads.add( e.lead.get( "/api/work/products/" + e.product + "/history" ).body() );
        reads.add( admin.get( "/api/work/activity?project=" + e.project + "&limit=200" ).body() );
        for ( final String body : reads ) {
            assertFalse( body.contains( secret ), "a later read repeats the secret" );
        }
        final JsonObject row = rowOf( admin, e.product );
        assertTrue( row.getAsJsonObject( "hook" ).get( "enabled" ).getAsBoolean() );
        assertTrue( row.getAsJsonObject( "hook" ).get( "lastReceivedAt" ).isJsonNull(), "nothing delivered yet" );

        // a lead, a member and a stranger: refused alike, and the answer for a real product is the one for a ghost
        final String ghost = "/api/work/products/hookghost" + e.tag + "/hook";
        for ( final Caller who : new Caller[] { e.lead, e.author, e.stranger } ) {
            final HttpResponse< String > real = who.send( "POST", "/api/work/products/" + e.product + "/hook", null );
            final HttpResponse< String > none = who.send( "POST", ghost, null );
            assertEquals( 403, real.statusCode() );
            assertEquals( real.statusCode(), none.statusCode(), "a non-admin cannot tell a real product from a ghost" );
            assertEquals( real.body(), none.body() );
            assertFalse( real.body().contains( secret ) );
            assertEquals( 403, who.send( "DELETE", "/api/work/products/" + e.product + "/hook", null ).statusCode() );
            final String listed = who.get( "/api/work/products" ).body();
            assertFalse( listed.contains( "\"hook\"" ), "a non-admin's product list carries no hook state" );
            if ( who != e.stranger ) { // a caller who sees no project is listed no product at all
                assertFalse( rowOf( who, e.product ).has( "hook" ), "a non-admin's product row carries no hook state" );
            } else {
                assertFalse( listed.contains( e.product ), "a stranger is listed no product" );
            }
        }
        assertEquals( 404, admin.send( "POST", ghost, null ).statusCode(), "an admin gets a plain not found for a ghost" );
    }

    private static JsonObject rowOf( final Caller who, final String product ) throws Exception {
        for ( final JsonElement p : json( who.get( "/api/work/products" ) ).getAsJsonArray() ) {
            if ( product.equals( str( p.getAsJsonObject(), "key" ) ) ) {
                return p.getAsJsonObject();
            }
        }
        throw new AssertionError( "product " + product + " is not listed" );
    }

    @Test
    void aWorkKeyAnAgentTokenAndAnAdminsKeyOnTheManagementRoutes() throws Exception {
        final Env e = env( false );
        final String path = "/api/work/products/" + e.product + "/hook";
        final String leadKey = mint( e.lead );
        // ASSUMED: a work key acts as its owner, so a lead's key is refused as the lead is (403)
        final HttpResponse< String > asLead = WorkIt.bearer( leadKey, "POST", path, null );
        assertEquals( 403, asLead.statusCode(), WorkIt.status( asLead ) );
        // ASSUMED: an agent is refused as management; 403 (or the plain 404 of an unknown route is NOT acceptable)
        final WorkIt.Agent bot = WorkIt.registerAgent( admin, "hookbot" + e.tag + "@it" );
        final HttpResponse< String > asAgent = WorkIt.bearer( bot.token(), "POST", path, null );
        assertEquals( 403, asAgent.statusCode(), WorkIt.status( asAgent ) );
        assertEquals( 403, WorkIt.bearer( bot.token(), "DELETE", path, null ).statusCode() );
        // ASSUMED: an admin's own work key reaches the admin check as its owner: 200 with the secret, or a refusal;
        // either way never a server error, and a 200 carries the secret only in that one body
        final HttpResponse< String > asAdmin = WorkIt.bearer( mint( admin ), "POST", path, null );
        assertTrue( asAdmin.statusCode() == 200 || asAdmin.statusCode() == 403, WorkIt.status( asAdmin ) );
        if ( asAdmin.statusCode() == 200 ) {
            final String secret = json( asAdmin ).getAsJsonObject().get( "secret" ).getAsString();
            assertFalse( admin.get( "/api/work/products" ).body().contains( secret ) );
        }
        // no key, no session: no route at all
        final int anonymous = raw( "POST", path, null, "Accept", "application/json" ).statusCode();
        assertTrue( anonymous == 401 || anonymous == 403 || anonymous == 404, "no credential, no route: " + anonymous );
    }

    private static String mint( final Caller owner ) throws Exception {
        final HttpResponse< String > r = owner.send( "POST", "/api/self/apikeys", Map.of( "label", "hook-work", "scope", "work" ) );
        assertEquals( 201, r.statusCode(), "mint a work key" );
        return json( r ).getAsJsonObject().get( "token" ).getAsString();
    }

    // ---------------------------------------------------------------- the endpoint's front door

    @Test
    void aSignedPingIsAcceptedAndMovesTheLastDelivery() throws Exception {
        final Env e = env( true );
        assertTrue( rowOf( admin, e.product ).getAsJsonObject( "hook" ).get( "lastReceivedAt" ).isJsonNull() );
        // no session, no API key, no Origin: only the signature
        accepted( github( e, "ping", obj( "zen", "Keep it logically awesome.", "hook_id", 1 ).toString() ) );
        final JsonObject hook = rowOf( admin, e.product ).getAsJsonObject( "hook" );
        assertFalse( hook.get( "lastReceivedAt" ).isJsonNull(), "a ping shows as the last delivery" );
        // a body of a size an ordinary push could have (20 KB) is under the configured limit
        final String pad = "x".repeat( 20_000 );
        accepted( github( e, "ping", obj( "zen", pad ).toString() ) );
    }

    @Test
    void everyUnverifiedDeliveryIsTheSameNotFoundAsAnUnknownHook() throws Exception {
        final Env e = env( true );
        final byte[] body = obj( "zen", "x" ).toString().getBytes( StandardCharsets.UTF_8 );
        final String path = hookPath( e.product, "github" );
        final HttpResponse< String > ghost = githubSigned( "hookghost" + e.tag, sha() + sha(), "ping", body, "application/json" );
        assertEquals( 404, ghost.statusCode(), "the reference answer: an unknown hook" );
        assertEquals( NOT_FOUND, ghost.body() );

        final List< HttpResponse< String > > unverified = new ArrayList<>();
        unverified.add( raw( "POST", path, body, "Content-Type", "application/json", "X-GitHub-Event", "ping" ) );
        unverified.add( raw( "POST", path, body, "Content-Type", "application/json", "X-GitHub-Event", "ping",
            "X-Hub-Signature-256", "sha256=" + hmac( sha() + sha(), body ) ) );
        unverified.add( raw( "POST", path, body, "Content-Type", "application/json", "X-GitHub-Event", "ping",
            "X-Hub-Signature-256", "sha1=" + hmac( e.secret, body ) ) );
        unverified.add( raw( "POST", path, body, "Content-Type", "application/json", "X-GitHub-Event", "ping",
            "X-Hub-Signature-256", hmac( e.secret, body ) ) );
        final byte[] tampered = body.clone();
        tampered[ tampered.length - 3 ] ^= 0x01;
        unverified.add( raw( "POST", path, tampered, "Content-Type", "application/json", "X-GitHub-Event", "ping",
            "X-Hub-Signature-256", "sha256=" + hmac( e.secret, body ) ) );
        unverified.add( raw( "GET", path, null ) );
        unverified.add( raw( "PUT", path, body, "Content-Type", "application/json" ) );
        unverified.add( raw( "DELETE", path, null ) );
        // the generic route, with the GitHub header or no header
        unverified.add( raw( "POST", hookPath( e.product, "generic" ), body, "Content-Type", "application/json",
            "X-Hub-Signature-256", "sha256=" + hmac( e.secret, body ) ) );
        for ( final HttpResponse< String > r : unverified ) {
            assertSameAsGhost( ghost, r );
        }

        // a disabled hook is that same not found, even for a correct signature
        assertEquals( 204, admin.send( "DELETE", "/api/work/products/" + e.product + "/hook", null ).statusCode() );
        assertSameAsGhost( ghost, githubSigned( e.product, e.secret, "ping", body, "application/json" ) );
        assertFalse( rowOf( admin, e.product ).getAsJsonObject( "hook" ).get( "enabled" ).getAsBoolean() );
    }

    private static void assertSameAsGhost( final HttpResponse< String > ghost, final HttpResponse< String > r ) {
        assertEquals( ghost.statusCode(), r.statusCode(), "status of " + r.request().method() + " " + r.uri().getPath() );
        assertEquals( ghost.body(), r.body(), "body of " + r.request().method() + " " + r.uri().getPath() );
        for ( final String h : new String[] { "Content-Type", "Cache-Control" } ) {
            assertEquals( ghost.headers().firstValue( h ).orElse( null ), r.headers().firstValue( h ).orElse( null ), h );
        }
    }

    @Test
    void aRotatedSecretReplacesTheOldOne() throws Exception {
        final Env e = env( true );
        final String old = e.secret;
        final byte[] body = obj( "zen", "x" ).toString().getBytes( StandardCharsets.UTF_8 );
        accepted( githubSigned( e.product, old, "ping", body, "application/json" ) );
        final String fresh = enable( e );
        assertNotEquals( old, fresh, "turning it on again issues a new secret" );
        assertEquals( 404, githubSigned( e.product, old, "ping", body, "application/json" ).statusCode() );
        accepted( githubSigned( e.product, fresh, "ping", body, "application/json" ) );
    }

    @Test
    void anOversizedBodyIs413BeforeAnyCheckOfTheSignature() throws Exception {
        final Env e = env( true );
        // ASSUMED: it-rest-cascade.properties sets wikantik.work.codehost.maxBodyBytes = 65536
        final byte[] big = obj( "zen", "x".repeat( 70_000 ) ).toString().getBytes( StandardCharsets.UTF_8 );
        final HttpResponse< String > signed = githubSigned( e.product, e.secret, "ping", big, "application/json" );
        assertEquals( 413, signed.statusCode() );
        assertTrue( signed.body().contains( "Payload too large" ), signed.body() );
        // the size check comes first: an unsigned oversized body is a 413 too, not the 404
        assertEquals( 413, raw( "POST", hookPath( e.product, "github" ), big, "Content-Type", "application/json" ).statusCode() );
    }

    @Test
    void aSignedFormEncodedDeliveryIs415AndAnUnsignedOneStaysNotFound() throws Exception {
        final Env e = env( true );
        final String form = "payload=" + java.net.URLEncoder.encode( obj( "zen", "x" ).toString(), StandardCharsets.UTF_8 );
        final byte[] body = form.getBytes( StandardCharsets.UTF_8 );
        final HttpResponse< String > r = githubSigned( e.product, e.secret, "ping", body, "application/x-www-form-urlencoded" );
        assertEquals( 415, r.statusCode() );
        assertTrue( r.body().contains( "application/json" ), r.body() );
        assertEquals( 404, raw( "POST", hookPath( e.product, "github" ), body,
            "Content-Type", "application/x-www-form-urlencoded", "X-GitHub-Event", "ping",
            "X-Hub-Signature-256", "sha256=" + hmac( sha() + sha(), body ) ).statusCode(), "unverified: not found, not 415" );
    }

    @Test
    void aSignedBodyThatIsNotAnObjectIsAcceptedAndRecordsNothing() throws Exception {
        final Env e = env( true );
        final String key = item( e );
        final String c = commit( sha(), key + " work", t( 2 ), "Out Sider", "outsider@elsewhere.example", "out" + e.tag, true ).toString();
        final String arrayWithAPush = "[" + push( key + "-fix", sha(), sha(), t( 2 ), commit( sha(), key + " work", t( 2 ),
            "Out Sider", "outsider@elsewhere.example", "out" + e.tag, true ) ) + "]";
        // ASSUMED: the documented status for a verified body that yields no events is 204 (the hook shows green)
        for ( final String garbage : new String[] { arrayWithAPush, "42", "\"text\"", "null", c.substring( 0, c.length() / 2 ) } ) {
            accepted( github( e, "push", garbage ) );
        }
        assertEquals( 0, changes( key ).size(), "no change was recorded for a body that is not an event object" );
        assertFalse( rowOf( admin, e.product ).getAsJsonObject( "hook" ).get( "lastReceivedAt" ).isJsonNull(),
            "it still counts as a delivery" );
    }

    // ---------------------------------------------------------------- the flows

    @Test
    void aBranchPullRequestReviewAndMergeRecordOneReviewedChangeByTheMatchedUser() throws Exception {
        final Env e = env( true );
        final String key = item( e );
        final List< String > before = itemState( key );
        final String user = "gh" + e.tag;
        final String reviewer = "rev" + e.tag;
        final Pr p = pr( key, user );
        final String mergeSha = sha();

        // the commit's address is the author's wiki profile email (case differs): identity by email, and the
        // host's own username is learned from the push's account field
        accepted( github( e, "push", push( p.branch(), sha(), p.head(), t( 0 ),
            commit( p.head(), key + " the fix", t( 0 ), "Someone Else", e.authorEmail, user, true ) ) ) );
        accepted( github( e, "pull_request", opened( p, 1 ) ) );
        accepted( github( e, "pull_request_review", approved( p, reviewer, 2 ) ) );
        final String live = str( theChange( key ), "state" );
        assertTrue( "open".equals( live ) || "in_review".equals( live ), "live before the merge: " + live );

        accepted( github( e, "pull_request", merged( p, reviewer, mergeSha, 3 ) ) );
        // ASSUMED: the merge's own push (its message names the pull request number) adds no second counted change
        accepted( github( e, "push", push( "main", sha(), mergeSha, t( 3 ),
            commit( mergeSha, "Merge pull request #" + p.number() + " from o/" + p.branch(), t( 3 ), "Merger", null, reviewer, true ) ) ) );

        final JsonObject c = theChange( key );
        assertEquals( "merged", str( c, "state" ) );
        assertEquals( "reviewed", str( c, "mergeKind" ) );
        assertFalse( c.has( "policyFlag" ) && !c.get( "policyFlag" ).isJsonNull(), "a reviewed merge is not flagged" );
        assertEquals( e.authorName, str( c, "openedByName" ), "opened by the wiki user the commit address matches" );
        assertEquals( e.authorLogin, str( c, "openedBy" ), "an admin sees the login" );
        assertNotNull( str( c, "firstCommitAt" ), "the first commit's time is set from the push" );
        assertEquals( String.valueOf( p.number() ), str( c, "reviewNumber" ) );
        assertEquals( mergeSha, str( c, "mergedSha" ) );
        assertFalse( inLane( admin, key ), "nothing here is outside policy" );
        assertTrue( hasEvent( key, "change_merged" ) );
        // the code host's events read as the system actor
        for ( final JsonElement ev : events( key ) ) {
            final JsonObject o = ev.getAsJsonObject();
            if ( str( o, "type" ) != null && str( o, "type" ).startsWith( "change_" ) && "system".equals( str( o, "actorKind" ) ) ) {
                assertEquals( "GitHub", str( o, "actorName" ) );
            }
        }
        // D15: the code host never moves the item
        assertEquals( before, itemState( key ), "status, assignee, sprint and version are untouched" );
    }

    @Test
    void anOutsideAuthorIsShownByTheirHostNameWithNoLogin() throws Exception {
        final Env e = env( true );
        final String key = item( e );
        final String user = "out" + e.tag;
        final Pr p = pr( key, user );
        accepted( github( e, "push", push( p.branch(), sha(), p.head(), t( 0 ),
            commit( p.head(), key + " fix", t( 0 ), "Out Sider", "nobody-" + e.tag + "@elsewhere.example", user, true ) ) ) );
        accepted( github( e, "pull_request", opened( p, 1 ) ) );
        final JsonObject c = theChange( key );
        assertEquals( user, str( c, "externalOpenedBy" ) );
        assertFalse( c.has( "openedBy" ) && !c.get( "openedBy" ).isJsonNull(), "an outsider has no wiki login" );
        assertNull( str( c, "openedByAgentId" ) );
        // the UI row for it reads "<name> (outside the wiki)" and never links to a profile: the data it reads is above
    }

    @Test
    void anUnreviewedMergeIsFlaggedReachesTheLeadsLaneAndInboxAndAcknowledgingClearsIt() throws Exception {
        final Env e = env( true );
        final String key = item( e );
        final List< String > before = itemState( key );
        final Pr p = pr( key, "gh" + e.tag );
        accepted( github( e, "pull_request", opened( p, 1 ) ) );
        accepted( github( e, "pull_request", merged( p, "gh" + e.tag, sha(), 3 ) ) );

        final JsonObject c = theChange( key );
        assertEquals( "unreviewed", str( c, "mergeKind" ) );
        assertEquals( FLAG_UNREVIEWED, str( c, "policyFlag" ) );
        assertTrue( inLane( e.lead, key ), "the lead's merged-outside-policy lane has this item" );
        assertFalse( inLane( e.author, key ), "a member's lane does not" );
        assertTrue( hasEvent( key, "change_flagged" ) );

        // the lead's inbox (the dispatcher runs every 2 s in this module)
        final String inbox = WorkIt.await( "inbox", e.lead, "/api/work/inbox", b -> b.contains( key ) );
        boolean flagged = false;
        for ( final JsonElement n : json( e.lead.get( "/api/work/inbox" ) ).getAsJsonObject().getAsJsonArray( "notices" ) ) {
            final JsonObject ev = n.getAsJsonObject().getAsJsonObject( "event" );
            flagged |= ev != null && "change_flagged".equals( str( ev, "type" ) ) && "acknowledge".equals( str( n.getAsJsonObject(), "reason" ) );
        }
        assertTrue( flagged, "a change_flagged notice with reason acknowledge: " + inbox );
        for ( final JsonElement n : json( e.author.get( "/api/work/inbox" ) ).getAsJsonObject().getAsJsonArray( "notices" ) ) {
            final JsonObject ev = n.getAsJsonObject().getAsJsonObject( "event" );
            assertFalse( ev != null && "change_flagged".equals( str( ev, "type" ) ), "the member is not told of the flag" );
        }

        final long id = c.get( "id" ).getAsLong();
        final int memberStatus = e.author.send( "POST", "/api/work/changes/" + id + "/acknowledge-flag", null ).statusCode();
        assertTrue( memberStatus == 403 || memberStatus == 404, "a member cannot acknowledge: " + memberStatus );
        assertTrue( inLane( e.lead, key ), "still waiting" );
        final HttpResponse< String > ack = e.lead.send( "POST", "/api/work/changes/" + id + "/acknowledge-flag", null );
        assertEquals( 200, ack.statusCode(), ack.body() );
        assertFalse( inLane( e.lead, key ), "acknowledged: this item has left the lane" );
        final JsonObject after = theChange( key );
        assertNotNull( str( after, "flagAcknowledgedAt" ) );
        assertEquals( FLAG_UNREVIEWED, str( after, "policyFlag" ), "acknowledging does not rewrite what was found" );
        assertEquals( 409, e.lead.send( "POST", "/api/work/changes/" + id + "/acknowledge-flag", null ).statusCode(),
            "a second acknowledgement is refused" );
        assertEquals( before, itemState( key ), "the item is untouched by the merge and by the acknowledgement" );
    }

    @Test
    void aDirectPushToTheDefaultBranchWhereDirectIsNotAllowedIsFlagged() throws Exception {
        final Env e = env( true );
        final String key = item( e );
        final List< String > before = itemState( key );
        final String user = "gh" + e.tag;
        // a commit the branch had already (distinct=false) names no item
        accepted( github( e, "push", push( "main", sha(), sha(), t( 1 ),
            commit( sha(), key + " already known", t( 1 ), "A", "a@elsewhere.example", user, false ) ) ) );
        assertEquals( 0, changes( key ).size(), "a commit that is not distinct names nothing" );
        accepted( github( e, "push", push( "main", sha(), sha(), t( 2 ),
            commit( sha(), key + " hotfix", t( 2 ), "A", "a@elsewhere.example", user, true ) ) ) );
        final JsonObject c = theChange( key );
        assertEquals( "merged", str( c, "state" ) );
        assertEquals( "direct", str( c, "mergeKind" ) );
        assertEquals( "direct", str( c, "policyFlag" ), "a direct commit where none is allowed is flagged" );
        assertTrue( inLane( e.lead, key ) );
        assertFalse( inLane( e.author, key ) );
        assertEquals( 200, e.lead.send( "POST", "/api/work/changes/" + c.get( "id" ).getAsLong() + "/acknowledge-flag", null ).statusCode() );
        assertFalse( inLane( e.lead, key ) );
        assertEquals( before, itemState( key ) );
    }

    @Test
    void aMergePushBeforeItsPullRequestsMergeIsNotAFlagInTheEnd() throws Exception {
        final Env e = env( true );
        final String key = item( e );
        final Pr p = pr( key, "gh" + e.tag );
        final String mergeSha = sha();
        accepted( github( e, "pull_request", opened( p, 1 ) ) );
        accepted( github( e, "pull_request_review", approved( p, "rev" + e.tag, 2 ) ) );
        // the merge commit reaches main (naming the pull request) before the pull request event does
        accepted( github( e, "push", push( "main", sha(), mergeSha, t( 3 ),
            commit( mergeSha, "Merge pull request #" + p.number() + " from o/" + p.branch(), t( 3 ), "M", null, "rev" + e.tag, true ) ) ) );
        accepted( github( e, "pull_request", merged( p, "rev" + e.tag, mergeSha, 3 ) ) );
        final JsonObject c = theChange( key ); // a direct row the merge replaced counts nowhere
        assertEquals( "merged", str( c, "state" ) );
        assertEquals( "reviewed", str( c, "mergeKind" ) );
        assertFalse( inLane( e.lead, key ), "the replaced direct commit is not in the lane" );
    }

    // ---------------------------------------------------------------- order and replay

    @Test
    void mergedDeliveredBeforeOpenedGivesTheSameChangeAsHostOrder() throws Exception {
        final Env e = env( true );
        final String hostOrder = item( e );
        final String reversed = item( e );
        final String reviewer = "rev" + e.tag;
        final Pr a = pr( hostOrder, "gh" + e.tag );
        final Pr b = pr( reversed, "gh" + e.tag );
        final String mergeA = sha();
        final String mergeB = sha();
        final String pushA = push( a.branch(), sha(), a.head(), t( 0 ), commit( a.head(), hostOrder + " fix", t( 0 ), "O", "o@x.example", "gh" + e.tag, true ) );
        final String pushB = push( b.branch(), sha(), b.head(), t( 0 ), commit( b.head(), reversed + " fix", t( 0 ), "O", "o@x.example", "gh" + e.tag, true ) );

        accepted( github( e, "push", pushA ) );
        accepted( github( e, "pull_request", opened( a, 1 ) ) );
        accepted( github( e, "pull_request_review", approved( a, reviewer, 2 ) ) );
        accepted( github( e, "pull_request", merged( a, reviewer, mergeA, 3 ) ) );

        accepted( github( e, "pull_request", merged( b, reviewer, mergeB, 3 ) ) );
        accepted( github( e, "pull_request_review", approved( b, reviewer, 2 ) ) );
        accepted( github( e, "pull_request", opened( b, 1 ) ) );
        accepted( github( e, "push", pushB ) );

        final JsonObject x = theChange( hostOrder );
        final JsonObject y = theChange( reversed );
        for ( final String f : new String[] { "state", "mergeKind", "policyFlag", "openedAt", "closedAt", "firstCommitAt", "openedByName" } ) {
            assertEquals( str( x, f ), str( y, f ), "field " + f + " is the same in any order" );
        }
        assertEquals( "reviewed", str( y, "mergeKind" ) );
        assertFalse( inLane( e.lead, reversed ), "the late approval left no flag in the lane" );
    }

    @Test
    void everyDeliveryRedeliveredTwiceChangesNothing() throws Exception {
        final Env e = env( true );
        final String key = item( e );
        final String user = "gh" + e.tag;
        final String reviewer = "rev" + e.tag;
        final Pr p = pr( key, user );
        final String mergeSha = sha();
        final List< String[] > deliveries = List.of(
            new String[] { "push", push( p.branch(), sha(), p.head(), t( 0 ), commit( p.head(), key + " fix", t( 0 ), "O", e.authorEmail, user, true ) ) },
            new String[] { "pull_request", opened( p, 1 ) },
            new String[] { "pull_request_review", approved( p, reviewer, 2 ) },
            new String[] { "pull_request", merged( p, reviewer, mergeSha, 3 ) },
            new String[] { "push", push( "main", sha(), mergeSha, t( 3 ),
                commit( mergeSha, "Merge pull request #" + p.number() + " from o/" + p.branch(), t( 3 ), "M", null, reviewer, true ) ) } );
        for ( final String[] d : deliveries ) {
            accepted( github( e, d[ 0 ], d[ 1 ] ) );
        }
        final int changesBefore = changes( key ).size();
        final int eventsBefore = events( key ).size();
        final String changeBefore = theChange( key ).toString();
        for ( int round = 0; round < 2; round++ ) {
            for ( final String[] d : deliveries ) {
                accepted( github( e, d[ 0 ], d[ 1 ] ) );
            }
        }
        assertEquals( changesBefore, changes( key ).size(), "no second change" );
        assertEquals( eventsBefore, events( key ).size(), "no duplicate events" );
        assertEquals( changeBefore, theChange( key ).toString(), "the record is byte for byte the same" );
    }

    @Test
    void aClosedAndReopenedPullRequestIsOneChangeAndItMerges() throws Exception {
        final Env e = env( true );
        final String key = item( e );
        final List< String > before = itemState( key );
        final Pr p = pr( key, "gh" + e.tag );
        accepted( github( e, "pull_request", opened( p, 1 ) ) );
        accepted( github( e, "pull_request", closed( p, 2 ) ) );
        assertEquals( "abandoned", str( theChange( key ), "state" ) );
        accepted( github( e, "pull_request", reopened( p, 3 ) ) );
        final String live = str( theChange( key ), "state" );
        assertTrue( "open".equals( live ) || "in_review".equals( live ), "the same change is live again: " + live );
        accepted( github( e, "pull_request", merged( p, "gh" + e.tag, sha(), 4 ) ) );
        final JsonObject c = theChange( key );
        assertEquals( 1, changes( key ).size(), "one pull request is one change for its whole history" );
        assertEquals( "merged", str( c, "state" ) );
        boolean noted = false;
        for ( final JsonElement ev : events( key ) ) {
            // ASSUMED: an event's note is serialised as "note"
            noted |= "reopened on the code host".equals( str( ev.getAsJsonObject(), "note" ) );
        }
        assertTrue( noted, "the reopening is logged as an update noting it" );
        assertEquals( before, itemState( key ) );
    }

    @Test
    void aLateApprovalStampedBeforeTheMergeClearsTheUnreviewedFlag() throws Exception {
        final Env e = env( true );
        final String key = item( e );
        final Pr p = pr( key, "gh" + e.tag );
        accepted( github( e, "pull_request", opened( p, 1 ) ) );
        accepted( github( e, "pull_request", merged( p, "gh" + e.tag, sha(), 4 ) ) );
        assertEquals( FLAG_UNREVIEWED, str( theChange( key ), "policyFlag" ), "flagged on arrival" );
        assertTrue( inLane( e.lead, key ) );
        accepted( github( e, "pull_request_review", approved( p, "rev" + e.tag, 3 ) ) ); // stamped before the merge
        final JsonObject c = theChange( key );
        assertEquals( "reviewed", str( c, "mergeKind" ) );
        assertFalse( c.has( "policyFlag" ) && !c.get( "policyFlag" ).isJsonNull(), "the flag is gone" );
        assertFalse( inLane( e.lead, key ) );
    }

    @Test
    void aLateApprovalDoesNotClearAFlagALeadAlreadyAcknowledged() throws Exception {
        final Env e = env( true );
        final String key = item( e );
        final Pr p = pr( key, "gh" + e.tag );
        accepted( github( e, "pull_request", opened( p, 1 ) ) );
        accepted( github( e, "pull_request", merged( p, "gh" + e.tag, sha(), 4 ) ) );
        final long id = theChange( key ).get( "id" ).getAsLong();
        assertEquals( 200, e.lead.send( "POST", "/api/work/changes/" + id + "/acknowledge-flag", null ).statusCode() );
        accepted( github( e, "pull_request_review", approved( p, "rev" + e.tag, 3 ) ) );
        final JsonObject c = theChange( key );
        assertEquals( "unreviewed", str( c, "mergeKind" ) );
        assertEquals( FLAG_UNREVIEWED, str( c, "policyFlag" ) );
        assertNotNull( str( c, "flagAcknowledgedAt" ) );
    }

    @Test
    void anApprovalStampedAfterTheMergeNeverClearsTheFlag() throws Exception {
        final Env e = env( true );
        final String key = item( e );
        final Pr p = pr( key, "gh" + e.tag );
        accepted( github( e, "pull_request", opened( p, 1 ) ) );
        accepted( github( e, "pull_request", merged( p, "gh" + e.tag, sha(), 3 ) ) );
        accepted( github( e, "pull_request_review", approved( p, "rev" + e.tag, 5 ) ) ); // stamped after the merge
        final JsonObject c = theChange( key );
        assertEquals( "unreviewed", str( c, "mergeKind" ) );
        assertEquals( FLAG_UNREVIEWED, str( c, "policyFlag" ) );
        assertTrue( inLane( e.lead, key ) );
    }

    @Test
    void aGenericEventNeedsAnAtAndOneWithAtWorks() throws Exception {
        final Env e = env( true );
        final String key = item( e );
        final String branch = key + "-gen";
        // ASSUMED: the generic review event reads kind, repo, branch, baseBranch, review.number/headSha/title/author
        final JsonObject withoutAt = obj( "kind", "review_opened", "repo", REPO, "defaultBranch", "main", "branch", branch,
            "baseBranch", "main", "review", obj( "number", 7, "headSha", sha(), "title", key + " generic",
                "author", obj( "username", "gen" + e.tag ) ) );
        accepted( generic( e, withoutAt.toString() ) ); // rejected as a delivery, still a 204 to the sender
        assertEquals( 0, changes( key ).size(), "an event with no time is never stamped with the time it arrived" );
        final JsonObject withAt = withoutAt.deepCopy();
        withAt.addProperty( "at", t( 5 ) );
        accepted( generic( e, withAt.toString() ) );
        final JsonObject c = theChange( key );
        assertEquals( "7", str( c, "reviewNumber" ) );
        assertEquals( branch, str( c, "branch" ) );
        // an array with one untimed event is rejected whole
        final JsonArray both = new JsonArray();
        final JsonObject second = withAt.deepCopy();
        second.getAsJsonObject( "review" ).addProperty( "number", 8 );
        second.addProperty( "branch", key + "-gen2" );
        second.remove( "at" );
        both.add( second );
        accepted( generic( e, both.toString() ) );
        assertEquals( 1, changes( key ).size(), "the rejected delivery recorded nothing" );
    }
}
