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
import com.google.gson.JsonObject;
import com.wikantik.its.rest.WorkIt.Caller;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.wikantik.its.rest.WorkIt.json;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Work item attachments end to end against the real container and filter chain: multipart upload, the serving
 * headers, the addressing rules (an attachment is reachable only through its own item), who may attach and remove,
 * both size limits, the render token, and the credentials. The IT module's wiki sets
 * {@code wikantik.work.attachments.maxBytes = 65536} and {@code maxItemBytes = 200000} (it-rest-cascade.properties).
 *
 * <p>The class makes its own users and a fresh project per method (unique per-run keys), takes every key and id from
 * the server and shares nothing between methods; they may run in any order.
 */
public class WorkAttachmentIT {

    private static final String RUN = new java.util.Random().ints( 6, 'a', 'z' + 1 )
        .collect( StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append ).toString();
    private static final String WORD = Character.toUpperCase( RUN.charAt( 0 ) ) + RUN.substring( 1 );
    private static final String LEAD = "atlead" + RUN;
    private static final String MEMBER = "atmem" + RUN;
    private static final String OTHER_MEMBER = "atmem2" + RUN;
    private static final String VIEWER = "atview" + RUN;
    private static final String STRANGER = "atstr" + RUN;
    private static final long LIMIT = 65536;
    private static final long ITEM_LIMIT = 200000;
    private static final byte[] PNG = Base64.getDecoder().decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==" );
    private static final AtomicInteger PROJECTS = new AtomicInteger();

    private static Caller admin;
    private static Caller lead;
    private static Caller member;
    private static Caller otherMember;
    private static Caller viewer;
    private static Caller stranger;

    @BeforeAll
    static void setUp() throws Exception {
        admin = WorkIt.admin();
        lead = WorkIt.account( admin, LEAD, "At Lead " + WORD );
        member = WorkIt.account( admin, MEMBER, "At Member " + WORD );
        otherMember = WorkIt.account( admin, OTHER_MEMBER, "At Other " + WORD );
        viewer = WorkIt.account( admin, VIEWER, "At Viewer " + WORD );
        stranger = WorkIt.account( admin, STRANGER, "At Stranger " + WORD );
    }

    /** A project of its own with a lead, two members and a viewer, and two items made by the member. */
    private record Fx( String project, String itemA, String itemB ) {
    }

    private static Fx fixture() throws Exception {
        final String project = "AT" + RUN.toUpperCase( Locale.ROOT ) + PROJECTS.incrementAndGet();
        assertEquals( 201, admin.send( "POST", "/api/work/projects",
            Map.of( "key", project, "name", project, "description", "d" ) ).statusCode() );
        role( project, LEAD, "lead" );
        role( project, MEMBER, "member" );
        role( project, OTHER_MEMBER, "member" );
        role( project, VIEWER, "viewer" );
        return new Fx( project, item( project ), item( project ) );
    }

    private static void role( final String project, final String login, final String role ) throws Exception {
        assertEquals( 204, admin.send( "PUT", "/api/work/projects/" + project + "/members", Map.of( "principal", login,
            "principalKind", "user", "role", role ) ).statusCode() );
    }

    private static String item( final String project ) throws Exception {
        final HttpResponse< String > r = member.send( "POST", "/api/work/items", Map.of( "project", project,
            "level", "task", "title", "Attach here" ) );
        assertEquals( 201, r.statusCode(), r.body() );
        return json( r ).getAsJsonObject().getAsJsonObject( "item" ).get( "key" ).getAsString();
    }

    private static String path( final String item ) {
        return "/api/work/items/" + item + "/attachments";
    }

    private static JsonObject uploaded( final Caller who, final String item, final String name, final byte[] bytes )
            throws Exception {
        final HttpResponse< String > r = who.multipart( path( item ), name, bytes );
        assertEquals( 201, r.statusCode(), r.body() );
        return json( r ).getAsJsonObject();
    }

    private static List< String > names( final Caller who, final String item ) throws Exception {
        final HttpResponse< String > r = who.get( path( item ) );
        assertEquals( 200, r.statusCode(), r.body() );
        final var listed = json( r ).getAsJsonObject();
        assertTrue( listed.get( "mayAttach" ).isJsonPrimitive(), r.body() );
        assertEquals( 65536L, listed.get( "maxFileBytes" ).getAsLong(), r.body() );
        assertEquals( ITEM_LIMIT, listed.get( "maxItemBytes" ).getAsLong(), r.body() );
        assertTrue( listed.get( "usedBytes" ).getAsLong() >= 0, r.body() );
        return listed.getAsJsonArray( "attachments" ).asList().stream()
            .map( e -> e.getAsJsonObject().get( "filename" ).getAsString() ).toList();
    }

    private static String header( final HttpResponse< ? > r, final String name ) {
        return r.headers().firstValue( name ).orElse( null );
    }

    private static void assertDownloadHeaders( final HttpResponse< byte[] > r, final boolean inline ) {
        final String disposition = header( r, "Content-Disposition" );
        assertTrue( disposition != null && disposition.startsWith( inline ? "inline" : "attachment" ), disposition );
        assertEquals( "nosniff", header( r, "X-Content-Type-Options" ) );
        assertEquals( inline ? "private, max-age=300" : "private, no-store", header( r, "Cache-Control" ) );
        final List< String > csp = r.headers().allValues( "Content-Security-Policy" );
        assertTrue( csp.stream().anyMatch( v -> v.contains( "sandbox" ) ), "the download's own sandbox policy: " + csp );
        assertTrue( csp.stream().anyMatch( v -> v.contains( "default-src 'self'" ) ),
            "the site policy of the real filter chain is still there: " + csp );
    }

    @Test
    void aMemberUploadsAnImageAndATextFileAndTheImageIsServedInline() throws Exception {
        final Fx fx = fixture();
        final JsonObject png = uploaded( member, fx.itemA(), "shot.png", PNG );
        uploaded( member, fx.itemA(), "notes.txt", "plain notes".getBytes( StandardCharsets.UTF_8 ) );
        assertEquals( List.of( "shot.png", "notes.txt" ), names( member, fx.itemA() ), "oldest first" );
        assertEquals( PNG.length, png.get( "sizeBytes" ).getAsLong() );
        assertEquals( "image/png", png.get( "contentType" ).getAsString() );
        assertTrue( png.get( "inline" ).getAsBoolean() );
        assertEquals( path( fx.itemA() ) + "/" + png.get( "id" ).getAsLong(), png.get( "url" ).getAsString() );
        assertEquals( "![shot.png](work-attachment:" + png.get( "id" ).getAsLong() + ")",
            png.get( "markdown" ).getAsString() );

        final HttpResponse< byte[] > got = member.bytes( png.get( "url" ).getAsString() );
        assertEquals( 200, got.statusCode() );
        assertTrue( Arrays.equals( PNG, got.body() ), "the same bytes come back" );
        assertEquals( "image/png", header( got, "Content-Type" ) );
        assertDownloadHeaders( got, true );
        assertEquals( String.valueOf( PNG.length ), header( got, "Content-Length" ) );

        // a viewer sees and downloads, but may not attach
        assertEquals( 200, viewer.bytes( png.get( "url" ).getAsString() ).statusCode() );
    }

    @Test
    void htmlSvgAndAnImageNameOverHtmlBytesAreForcedDownloads() throws Exception {
        final Fx fx = fixture();
        final byte[] html = "<html><script>alert(1)</script></html>".getBytes( StandardCharsets.UTF_8 );
        final byte[] svg = "<svg xmlns=\"http://www.w3.org/2000/svg\" onload=\"alert(1)\"/>".getBytes( StandardCharsets.UTF_8 );
        for ( final String name : List.of( "page.html", "logo.svg", "fake.png" ) ) {
            final byte[] bytes = name.endsWith( ".svg" ) ? svg : html;
            // the client claims an image type for the last one; the stored type comes from the bytes
            final HttpResponse< String > up = member.multipart( path( fx.itemA() ), name,
                name.equals( "fake.png" ) ? "image/png" : "text/html", bytes, Map.of() );
            assertEquals( 201, up.statusCode(), up.body() );
            final JsonObject a = json( up ).getAsJsonObject();
            assertFalse( a.get( "inline" ).getAsBoolean(), name );
            final HttpResponse< byte[] > got = member.bytes( a.get( "url" ).getAsString() );
            assertEquals( 200, got.statusCode(), name );
            assertEquals( "application/octet-stream", header( got, "Content-Type" ), name );
            assertDownloadHeaders( got, false );
            assertTrue( Arrays.equals( bytes, got.body() ), name );
        }
    }

    @Test
    void aHostileFileNameStaysInsideTheDispositionHeader() throws Exception {
        final Fx fx = fixture();
        final String hostile = "a;b'c%d(e)*f é ü; 中" + "x".repeat( 300 ) + ".txt";
        final HttpResponse< String > up = member.multipart( path( fx.itemA() ), hostile,
            "text/plain", "hello".getBytes( StandardCharsets.UTF_8 ), Map.of() );
        assertEquals( 201, up.statusCode(), up.body() );
        final JsonObject a = json( up ).getAsJsonObject();
        assertTrue( a.get( "filename" ).getAsString().length() <= 200, "the stored name is cut to 200" );
        final HttpResponse< byte[] > got = member.bytes( a.get( "url" ).getAsString() );
        assertEquals( 200, got.statusCode() );
        assertEquals( 1, got.headers().allValues( "Content-Disposition" ).size() );
        final String disposition = header( got, "Content-Disposition" );
        assertTrue( Pattern.matches( "attachment; filename=\"[^\"\\\\\\r\\n]*\"; filename\\*=UTF-8''[A-Za-z0-9!#$&+\\-.^_`|~%]+",
            disposition ), disposition );
        assertTrue( disposition.chars().allMatch( ch -> ch >= 0x20 && ch < 0x7f ), "ASCII only: " + disposition );
        assertTrue( got.headers().firstValue( "X-Evil" ).isEmpty() );
    }

    @Test
    void theCommentFieldAttachesTheFileToThatComment() throws Exception {
        final Fx fx = fixture();
        final HttpResponse< String > c = member.send( "POST", "/api/work/items/" + fx.itemA() + "/comments",
            Map.of( "body", "see attached" ) );
        assertEquals( 201, c.statusCode(), c.body() );
        final long commentId = json( c ).getAsJsonObject().get( "id" ).getAsLong();

        final HttpResponse< String > up = member.multipart( path( fx.itemA() ), "c.txt", "text/plain",
            "x".getBytes( StandardCharsets.UTF_8 ), Map.of( "comment", String.valueOf( commentId ) ) );
        assertEquals( 201, up.statusCode(), up.body() );
        assertEquals( commentId, json( up ).getAsJsonObject().get( "commentId" ).getAsLong(),
            "Tomcat exposes the multipart text field to getParameter" );
        final var none = json( uploadedNoComment( fx.itemA() ) ).getAsJsonObject().get( "commentId" );
        assertTrue( none == null || none.isJsonNull(), "without the field there is no comment: " + none );

        final HttpResponse< String > bad = member.multipart( path( fx.itemA() ), "d.txt", "text/plain",
            "x".getBytes( StandardCharsets.UTF_8 ), Map.of( "comment", "abc" ) );
        assertEquals( 400, bad.statusCode(), bad.body() );
        assertEquals( "comment", json( bad ).getAsJsonObject().get( "field" ).getAsString() );
    }

    private static HttpResponse< String > uploadedNoComment( final String item ) throws Exception {
        return member.multipart( path( item ), "n.txt", "x".getBytes( StandardCharsets.UTF_8 ) );
    }

    @Test
    void anAttachmentIdThroughAnotherItemIsTheSame404AsAnUnknownId() throws Exception {
        final Fx fx = fixture();
        final JsonObject a = uploaded( member, fx.itemA(), "s.png", PNG );
        final long id = a.get( "id" ).getAsLong();
        final HttpResponse< byte[] > ok = member.bytes( path( fx.itemA() ) + "/" + id );
        assertEquals( 200, ok.statusCode() );
        final HttpResponse< byte[] > other = member.bytes( path( fx.itemB() ) + "/" + id );
        final HttpResponse< byte[] > unknown = member.bytes( path( fx.itemB() ) + "/999999999" );
        final HttpResponse< byte[] > junk = member.bytes( path( fx.itemB() ) + "/abc" );
        assertEquals( 404, other.statusCode() );
        assertEquals( new String( unknown.body(), StandardCharsets.UTF_8 ), new String( other.body(), StandardCharsets.UTF_8 ) );
        assertEquals( 404, unknown.statusCode() );
        assertEquals( 404, junk.statusCode() );
        assertEquals( new String( unknown.body(), StandardCharsets.UTF_8 ), new String( junk.body(), StandardCharsets.UTF_8 ) );
        assertEquals( 404, member.send( "DELETE", path( fx.itemB() ) + "/" + id, null ).statusCode() );
        assertEquals( 1, names( member, fx.itemA() ).size(), "the foreign delete removed nothing" );
    }

    @Test
    void aNonMemberGetsNotFoundFromEveryRouteEvenWithABody() throws Exception {
        final Fx fx = fixture();
        final long id = uploaded( member, fx.itemA(), "s.png", PNG ).get( "id" ).getAsLong();
        final HttpResponse< String > list = stranger.get( path( fx.itemA() ) );
        final HttpResponse< String > ghost = stranger.get( path( "ZZZQ-1" ) );
        assertEquals( 404, list.statusCode() );
        assertEquals( ghost.body(), list.body(), "a hidden item is not told apart from a missing one" );
        final HttpResponse< String > up = stranger.multipart( path( fx.itemA() ), "x.png", PNG );
        assertEquals( 404, up.statusCode(), up.body() );
        assertEquals( 404, stranger.bytes( path( fx.itemA() ) + "/" + id ).statusCode() );
        assertEquals( 404, stranger.send( "DELETE", path( fx.itemA() ) + "/" + id, null ).statusCode() );
        assertEquals( 1, names( member, fx.itemA() ).size(), "nothing was added or removed" );
    }

    @Test
    void aViewerMayNotAttachOrRemove() throws Exception {
        final Fx fx = fixture();
        final long id = uploaded( member, fx.itemA(), "s.png", PNG ).get( "id" ).getAsLong();
        assertEquals( 403, viewer.multipart( path( fx.itemA() ), "v.png", PNG ).statusCode() );
        assertEquals( 403, viewer.send( "DELETE", path( fx.itemA() ) + "/" + id, null ).statusCode() );
        assertEquals( 200, viewer.get( path( fx.itemA() ) ).statusCode(), "a viewer lists" );
        assertEquals( 1, names( member, fx.itemA() ).size() );
    }

    @Test
    void aFileOverTheLimitIs413WithTheContractBody() throws Exception {
        final Fx fx = fixture();
        final HttpResponse< String > exact = member.multipart( path( fx.itemA() ), "exact.bin", new byte[ (int) LIMIT ] );
        assertEquals( 201, exact.statusCode(), "control: a file of exactly the limit is accepted: " + exact.body() );
        final HttpResponse< String > over = member.multipart( path( fx.itemB() ), "over.bin", new byte[ (int) LIMIT + 1 ] );
        assertEquals( 413, over.statusCode(), over.body() );
        final JsonObject body = json( over ).getAsJsonObject();
        assertEquals( 413, body.get( "status" ).getAsInt() );
        assertEquals( "too_large", body.get( "reason" ).getAsString() );
        assertEquals( "This file is larger than the limit of " + LIMIT + " bytes.", body.get( "message" ).getAsString() );
        assertEquals( 0, names( member, fx.itemB() ).size() );
    }

    @Test
    void theItemTotalIsCappedAndARemovalFreesRoom() throws Exception {
        final Fx fx = fixture();
        final int chunk = 60000;
        final JsonObject first = uploaded( member, fx.itemA(), "one.bin", new byte[ chunk ] );
        uploaded( member, fx.itemA(), "two.bin", new byte[ chunk ] );
        uploaded( member, fx.itemA(), "three.bin", new byte[ chunk ] );
        final HttpResponse< String > fourth = member.multipart( path( fx.itemA() ), "four.bin", new byte[ chunk ] );
        assertEquals( 413, fourth.statusCode(), "4 x 60000 is over " + ITEM_LIMIT + ": " + fourth.body() );
        assertEquals( "item_full", json( fourth ).getAsJsonObject().get( "reason" ).getAsString() );
        assertEquals( 3, names( member, fx.itemA() ).size() );
        // the cap is per item
        uploaded( member, fx.itemB(), "other.bin", new byte[ chunk ] );
        // removed bytes no longer count
        assertEquals( 204, member.send( "DELETE", first.get( "url" ).getAsString(), null ).statusCode() );
        uploaded( member, fx.itemA(), "four.bin", new byte[ chunk ] );
    }

    @Test
    void removalHidesTheFileAndTheActivityTellsTheStory() throws Exception {
        final Fx fx = fixture();
        final JsonObject a = uploaded( member, fx.itemA(), "gone.png", PNG );
        final String url = a.get( "url" ).getAsString();

        assertEquals( 403, otherMember.send( "DELETE", url, null ).statusCode(), "only the uploader or a lead removes" );
        assertEquals( 200, member.bytes( url ).statusCode() );
        assertEquals( 204, member.send( "DELETE", url, null ).statusCode() );
        assertEquals( 0, names( member, fx.itemA() ).size() );
        assertEquals( 404, member.bytes( url ).statusCode() );
        assertEquals( 404, member.send( "DELETE", url, null ).statusCode(), "twice is not found" );

        final JsonArray events = json( member.get( "/api/work/items/" + fx.itemA() + "/events" ) ).getAsJsonArray();
        final List< String > types = events.asList().stream().map( e -> e.getAsJsonObject().get( "type" ).getAsString() ).toList();
        assertEquals( 1, types.stream().filter( "attached"::equals ).count(), types.toString() );
        assertEquals( 1, types.stream().filter( "attachment_removed"::equals ).count(), types.toString() );

        // a lead removes someone else's
        final JsonObject b = uploaded( member, fx.itemA(), "lead.png", PNG );
        assertEquals( 204, lead.send( "DELETE", b.get( "url" ).getAsString(), null ).statusCode() );
    }

    @Test
    void theTokenInItemTextRendersToThisItemsOwnDownloadAndNowhereElse() throws Exception {
        final Fx fx = fixture();
        final long mine = uploaded( member, fx.itemA(), "mine.png", PNG ).get( "id" ).getAsLong();
        final long file = uploaded( member, fx.itemA(), "doc.txt", "t".getBytes( StandardCharsets.UTF_8 ) )
            .get( "id" ).getAsLong();
        final long foreign = uploaded( member, fx.itemB(), "foreign.png", PNG ).get( "id" ).getAsLong();

        final HttpResponse< String > made = member.get( "/api/work/items/" + fx.itemA() );
        final int version = json( made ).getAsJsonObject().getAsJsonObject( "item" ).get( "version" ).getAsInt();
        final String text = "![shot](work-attachment:" + mine + ")\n\n[doc](work-attachment:" + file + ")\n\n"
            + "![stolen](work-attachment:" + foreign + ")";
        final HttpResponse< String > patched = member.send( "PATCH", "/api/work/items/" + fx.itemA(), Map.of(
            "expectedVersion", version, "changes", Map.of( "body", text ) ) );
        assertEquals( 200, patched.statusCode(), patched.body() );

        final String html = json( member.get( "/api/work/items/" + fx.itemA() ) ).getAsJsonObject().get( "bodyHtml" ).getAsString();
        final String own = "/api/work/items/" + fx.itemA() + "/attachments/";
        assertTrue( html.contains( "src=\"" ) && html.contains( own + mine + "\"" ), html );
        assertTrue( html.contains( "href=\"" ) && html.contains( own + file + "\"" ), html );
        assertFalse( html.contains( "/items/" + fx.itemB() + "/" ), "no URL names the other item: " + html );
        // every attachment URL in the text points at this item's own route, and the foreign id is a 404 there
        final Matcher m = Pattern.compile( "/api/work/items/[^\"]*/attachments/(\\d+)" ).matcher( html );
        int seen = 0;
        while ( m.find() ) {
            seen++;
            assertTrue( m.group().startsWith( own ), m.group() );
        }
        assertTrue( seen >= 2, html );
        assertEquals( 404, member.bytes( own + foreign ).statusCode(), "a foreign id does not resolve" );
        assertEquals( 200, member.bytes( own + mine ).statusCode() );
        assertNotEquals( mine, foreign );
    }

    private static String mint( final Caller owner, final String scope ) throws Exception {
        final HttpResponse< String > r = owner.send( "POST", "/api/self/apikeys", Map.of( "label", "att-" + scope, "scope", scope ) );
        assertEquals( 201, r.statusCode(), "mint " + scope );
        return json( r ).getAsJsonObject().get( "token" ).getAsString();
    }

    @Test
    void aWorkKeyActsAsItsOwnerAnAgentTokenAsTheAgentAndAWrongScopeIsRefused() throws Exception {
        final Fx fx = fixture();

        final String workKey = mint( member, "work" );
        final HttpResponse< String > viaKey = WorkIt.bearerMultipart( workKey, path( fx.itemA() ), "key.png", PNG );
        assertEquals( 201, viaKey.statusCode(), WorkIt.status( viaKey ) );
        final JsonObject keyed = json( viaKey ).getAsJsonObject();
        assertEquals( "human", keyed.get( "uploadedByKind" ).getAsString(), "a key is its owner, never an agent" );
        assertTrue( keyed.get( "mine" ).getAsBoolean() );
        assertEquals( 200, WorkIt.bearerBytes( workKey, keyed.get( "url" ).getAsString() ).statusCode() );
        final HttpResponse< String > keyedViewerUpload = WorkIt.bearerMultipart( mint( viewer, "work" ),
            path( fx.itemA() ), "v.png", PNG );
        assertEquals( 403, keyedViewerUpload.statusCode(), "the key is only as strong as its owner's role" );

        final WorkIt.Agent bot = WorkIt.registerAgent( admin, "attbot" + RUN + "@it" );
        assertEquals( 404, WorkIt.bearerMultipart( bot.token(), path( fx.itemA() ), "bot.png", PNG ).statusCode(),
            "an agent outside the project cannot see the item" );
        assertEquals( 204, admin.send( "PUT", "/api/work/projects/" + fx.project() + "/members", Map.of(
            "principal", bot.name(), "principalKind", "agent", "role", "member" ) ).statusCode() );
        final HttpResponse< String > viaAgent = WorkIt.bearerMultipart( bot.token(), path( fx.itemA() ), "bot.png", PNG );
        assertEquals( 201, viaAgent.statusCode(), WorkIt.status( viaAgent ) );
        final JsonObject byBot = json( viaAgent ).getAsJsonObject();
        assertEquals( "agent", byBot.get( "uploadedByKind" ).getAsString() );
        assertEquals( 200, WorkIt.bearerBytes( bot.token(), byBot.get( "url" ).getAsString() ).statusCode() );
        assertEquals( 403, otherMember.send( "DELETE", byBot.get( "url" ).getAsString(), null ).statusCode(),
            "a person who did not upload it may not remove the agent's file" );

        final String readKey = mint( member, "mcp_read" );
        final HttpResponse< String > wrong = WorkIt.bearerMultipart( readKey, path( fx.itemA() ), "r.png", PNG );
        assertEquals( 403, wrong.statusCode() );
        assertEquals( 403, WorkIt.bearerBytes( readKey, keyed.get( "url" ).getAsString() ).statusCode() );
        assertEquals( 2, names( member, fx.itemA() ).size(), "the refused uploads added nothing: key and agent only" );
    }

    @Test
    void anUnauthenticatedCallerIsRefused() throws Exception {
        final Fx fx = fixture();
        final long id = uploaded( member, fx.itemA(), "s.png", PNG ).get( "id" ).getAsLong();
        final HttpResponse< byte[] > r = WorkIt.bearerBytes( "not-a-key", path( fx.itemA() ) + "/" + id );
        assertTrue( r.statusCode() == 401 || r.statusCode() == 403, "status " + r.statusCode() );
        final HttpResponse< String > up = WorkIt.bearerMultipart( "not-a-key", path( fx.itemA() ), "x.png", PNG );
        assertTrue( up.statusCode() == 401 || up.statusCode() == 403, "status " + up.statusCode() );
    }
}
