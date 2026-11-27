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
package com.wikantik.its.work;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.wikantik.its.work.WorkIt.Caller;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static com.wikantik.its.work.WorkIt.json;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The two refusals that protect a project from a person who only reports: the daily filing cap
 * ({@code wikantik.work.intake.maxPerDay}, default 20: HTTP 429, reason {@code intake_limit}) and the reporter's share of
 * an item's attachment room ({@code wikantik.work.attachments.reporterItemBytes}: HTTP 413, reason
 * {@code reporter_full}; removed files still count).
 *
 * <p><b>Effective limits in this module.</b> The cap is the shipped default of 20 (no cascade override). The reporter's
 * attachment share is the shipped default of 10 MiB, but the product clamps it to the item total
 * ({@code WorkWiring.reporterItemBytes}), and this module's cascade sets {@code maxItemBytes = 200000} (files up to
 * 65536), so the effective share here is 200000 bytes. That still separates the two refusals: the reporter total is
 * checked after the item total, and only a removed file (which leaves the item's live total) makes {@code reporter_full}
 * reachable before {@code item_full}.
 *
 * <p>Every method builds its own project and its own reporters (per-run letters), because the daily count is per person
 * across every project; no other IT can count against them. Methods may run in any order.
 */
public class WorkIntakeLimitsIT {

    private static final String RUN = new java.util.Random().ints( 6, 'a', 'z' + 1 )
        .collect( StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append ).toString();
    private static final AtomicInteger SEQ = new AtomicInteger();
    private static final int DAILY_CAP = 20;
    private static final int CHUNK = 65536;
    private static final long REPORTER_SHARE = 200000;

    private static Caller admin;

    @BeforeAll
    static void setUp() throws Exception {
        admin = WorkIt.admin();
    }

    /** A project with a lead, a member, and two reporters (each with their own fresh account). */
    private record Fx( String project, Caller lead, Caller member, Caller r1, Caller r2 ) {
    }

    private static Fx fixture() throws Exception {
        final int n = SEQ.getAndIncrement();
        final String tag = RUN + ( char ) ( 'a' + n );
        final String word = Character.toUpperCase( tag.charAt( 0 ) ) + tag.substring( 1 );
        final String project = "ILM" + RUN.substring( 0, 4 ).toUpperCase( Locale.ROOT ) + n;
        assertEquals( 201, admin.send( "POST", "/api/work/projects", Map.of( "key", project, "name", "Limits " + word,
            "description", "d" ) ).statusCode() );
        final Caller lead = WorkIt.account( admin, "ilmlead" + tag, "Ilm Lead " + word );
        final Caller member = WorkIt.account( admin, "ilmmem" + tag, "Ilm Member " + word );
        final Caller r1 = WorkIt.account( admin, "ilmrone" + tag, "Ilm Reporter One " + word );
        final Caller r2 = WorkIt.account( admin, "ilmrtwo" + tag, "Ilm Reporter Two " + word );
        role( project, "ilmlead" + tag, "lead" );
        role( project, "ilmmem" + tag, "member" );
        role( project, "ilmrone" + tag, "reporter" );
        role( project, "ilmrtwo" + tag, "reporter" );
        return new Fx( project, lead, member, r1, r2 );
    }

    private static void role( final String project, final String login, final String role ) throws Exception {
        final HttpResponse< String > r = admin.send( "PUT", "/api/work/projects/" + project + "/members",
            Map.of( "principal", login, "principalKind", "user", "role", role ) );
        assertEquals( 204, r.statusCode(), "grant " + role + " to " + login + ": " + r.body() );
    }

    private static HttpResponse< String > fileRaw( final Caller who, final String project, final String title )
            throws Exception {
        return who.send( "POST", "/api/work/intake/items", Map.of( "projectKey", project, "level", "task",
            "type", "bug", "title", title, "body", "body" ) );
    }

    private static String file( final Caller who, final String project, final String title ) throws Exception {
        final HttpResponse< String > r = fileRaw( who, project, title );
        assertEquals( 201, r.statusCode(), r.body() );
        return json( r ).getAsJsonObject().get( "key" ).getAsString();
    }

    private static int reportedCount( final Caller who ) throws Exception {
        final HttpResponse< String > r = who.get( "/api/work/intake/items" );
        assertEquals( 200, r.statusCode(), r.body() );
        return json( r ).getAsJsonArray().size();
    }

    private static int boardCount( final Caller who, final String project ) throws Exception {
        final HttpResponse< String > r = who.get( "/api/work/projects/" + project + "/board" );
        assertEquals( 200, r.statusCode(), r.body() );
        final JsonArray board = json( r ).getAsJsonArray();
        int cards = 0;
        for ( final var column : board ) {
            final var col = column.getAsJsonObject();
            for ( final String field : List.of( "items", "cards" ) ) {
                if ( col.has( field ) && col.get( field ).isJsonArray() ) {
                    cards += col.getAsJsonArray( field ).size();
                }
            }
        }
        return cards;
    }

    private static String path( final String item ) {
        return "/api/work/items/" + item + "/attachments";
    }

    private static JsonArray attachments( final Caller who, final String item ) throws Exception {
        final HttpResponse< String > r = who.get( path( item ) );
        assertEquals( 200, r.statusCode(), r.body() );
        return json( r ).getAsJsonObject().getAsJsonArray( "attachments" );
    }

    private static List< Long > ids( final JsonArray list ) {
        final List< Long > out = new ArrayList<>();
        list.forEach( e -> out.add( e.getAsJsonObject().get( "id" ).getAsLong() ) );
        return out;
    }

    // ================================================================ the daily filing cap

    @Test
    void aReporterPastTheDailyCapIs429AndNothingIsCreatedWhileOthersAreUnaffected() throws Exception {
        final Fx fx = fixture();
        for ( int i = 0; i < DAILY_CAP; i++ ) {
            file( fx.r1(), fx.project(), "Filing " + i );
        }
        assertEquals( DAILY_CAP, reportedCount( fx.r1() ), "control: all of the first " + DAILY_CAP + " were accepted" );
        final int boardBefore = boardCount( fx.lead(), fx.project() );
        assertTrue( boardBefore >= DAILY_CAP, "control: the project holds the filings (" + boardBefore + ")" );

        final HttpResponse< String > over = fileRaw( fx.r1(), fx.project(), "One too many" );
        assertEquals( 429, over.statusCode(), over.body() );
        final JsonObject body = json( over ).getAsJsonObject();
        assertEquals( 429, body.get( "status" ).getAsInt(), over.body() );
        assertEquals( "intake_limit", body.get( "reason" ).getAsString(), over.body() );
        assertEquals( "You have filed " + DAILY_CAP + " requests in the last 24 hours, the most allowed. Try again later.",
            body.get( "message" ).getAsString(), over.body() );
        assertTrue( body.has( "reason" ) && !body.has( "current" ), "no merge target on a cap refusal: " + over.body() );

        // nothing was created, and the refusal is repeatable, not a one-off
        assertEquals( DAILY_CAP, reportedCount( fx.r1() ), "the reporter's list is unchanged" );
        assertEquals( boardBefore, boardCount( fx.lead(), fx.project() ), "the project's board is unchanged" );
        assertEquals( 429, fileRaw( fx.r1(), fx.project(), "Still too many" ).statusCode() );

        // a second reporter is not blocked by the first one's cap
        file( fx.r2(), fx.project(), "Second reporter is fine" );
        assertEquals( 1, reportedCount( fx.r2() ) );

        // people with a role in the project are not capped, through the ordinary route
        for ( final Caller who : List.of( fx.member(), fx.lead() ) ) {
            for ( int i = 0; i <= DAILY_CAP; i++ ) {
                final HttpResponse< String > r = who.send( "POST", "/api/work/items", Map.of( "project", fx.project(),
                    "level", "task", "title", "Member filing " + i ) );
                assertEquals( 201, r.statusCode(), "filing " + i + " by a member or lead: " + r.body() );
            }
        }
    }

    // ================================================================ the reporter's attachment share

    @Test
    void aReportersFilesPastTheirShareAre413AndRemovingOneGivesNoRoomBack() throws Exception {
        final Fx fx = fixture();
        final String key = file( fx.r1(), fx.project(), "Needs files" );
        final String other = file( fx.r2(), fx.project(), "Other reporter's request" );

        final List< Long > mine = new ArrayList<>();
        for ( int i = 0; i < 3; i++ ) {
            final HttpResponse< String > up = fx.r1().multipart( path( key ), "f" + i + ".bin", new byte[ CHUNK ] );
            assertEquals( 201, up.statusCode(), up.body() );
            mine.add( json( up ).getAsJsonObject().get( "id" ).getAsLong() );
        }
        assertTrue( 3L * CHUNK <= REPORTER_SHARE && 4L * CHUNK > REPORTER_SHARE, "the sizes straddle the share" );

        // remove one: the item's live total drops to 2 files, so only the reporter rule can refuse a 4th upload
        assertEquals( 204, fx.r1().send( "DELETE", path( key ) + "/" + mine.get( 0 ), null ).statusCode() );
        final List< Long > before = ids( attachments( fx.r1(), key ) );
        assertEquals( List.of( mine.get( 1 ), mine.get( 2 ) ), before );

        final HttpResponse< String > over = fx.r1().multipart( path( key ), "again.bin", new byte[ CHUNK ] );
        assertEquals( 413, over.statusCode(), "removed files still count: " + over.body() );
        final JsonObject body = json( over ).getAsJsonObject();
        assertEquals( 413, body.get( "status" ).getAsInt(), over.body() );
        assertEquals( "reporter_full", body.get( "reason" ).getAsString(), over.body() );
        assertEquals( "Your files on this request would pass the limit of " + REPORTER_SHARE + " bytes.",
            body.get( "message" ).getAsString(), over.body() );
        assertEquals( before, ids( attachments( fx.r1(), key ) ), "the item's attachment list is unchanged" );

        // a file that still fits in what is left is accepted (3 x 65536 used; 3392 remain)
        final HttpResponse< String > small = fx.r1().multipart( path( key ), "small.bin", new byte[ 1000 ] );
        assertEquals( 201, small.statusCode(), "a small file within the share: " + small.body() );

        // a member's upload on the same item is not charged to the reporter's share
        final HttpResponse< String > viaMember = fx.member().multipart( path( key ), "member.bin", new byte[ CHUNK ] );
        assertEquals( 201, viaMember.statusCode(), "a member is not held to the reporter's share: " + viaMember.body() );

        // a second reporter has a share of their own on their own item
        final HttpResponse< String > r2up = fx.r2().multipart( path( other ), "r2.bin", new byte[ CHUNK ] );
        assertEquals( 201, r2up.statusCode(), r2up.body() );
    }
}
