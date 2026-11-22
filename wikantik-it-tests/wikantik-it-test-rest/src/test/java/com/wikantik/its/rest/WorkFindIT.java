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

import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.wikantik.its.rest.WorkIt.json;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Find across projects ({@code GET /api/work/items} without {@code project}) and saved filters, end to end. Checked
 * against the REST code's JSON shapes.
 *
 * <p>The class makes its own users and its own two projects, takes every key and id from the server, and asserts
 * only on its own rows. No method depends on another: {@code @BeforeAll} builds shared fixtures and nothing else.
 */
public class WorkFindIT {

    /** Per-run letters, so a rerun on the same server (a retried class) finds no project, login or label of the last. */
    private static final String RUN = new java.util.Random().ints( 6, 'a', 'z' + 1 )
        .collect( StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append ).toString();
    private static final String WORD = Character.toUpperCase( RUN.charAt( 0 ) ) + RUN.substring( 1 );
    private static final String PA = "FA" + RUN.toUpperCase( java.util.Locale.ROOT );
    private static final String PB = "FB" + RUN.toUpperCase( java.util.Locale.ROOT );
    private static final String TAG = "fndall" + RUN;
    private static final String ONLY_B = "fndbonly" + RUN;
    private static final String L_A1 = "fndaone" + RUN;
    private static final String L_A2 = "fndatwo" + RUN;
    private static final String L_A3 = "fndathree" + RUN;
    private static final String L_B1 = "fndbone" + RUN;
    private static final String L_BOTH = "fndboth" + RUN;
    private static final String L_OUT = "fndnobody" + RUN;
    private static final String N_A1 = "Fnd A One " + WORD;
    private static final String N_A2 = "Fnd A Two " + WORD;

    private static Caller admin;
    private static Caller aOnly;
    private static Caller aSecond;
    private static Caller aThird;
    private static Caller bOnly;
    private static Caller both;
    private static Caller outsider;
    private static final List< String > KEYS_A = new ArrayList<>();
    private static final List< String > KEYS_B = new ArrayList<>();

    @BeforeAll
    static void setUp() throws Exception {
        admin = WorkIt.admin();
        aOnly = WorkIt.account( admin, L_A1, N_A1 );
        aSecond = WorkIt.account( admin, L_A2, N_A2 );
        aThird = WorkIt.account( admin, L_A3, "Fnd A Three " + WORD );
        bOnly = WorkIt.account( admin, L_B1, "Fnd B One " + WORD );
        both = WorkIt.account( admin, L_BOTH, "Fnd Both " + WORD );
        outsider = WorkIt.account( admin, L_OUT, "Fnd Nobody " + WORD );
        for ( final String key : List.of( PA, PB ) ) {
            assertEquals( 201, admin.send( "POST", "/api/work/projects",
                Map.of( "key", key, "name", key, "description", "d" ) ).statusCode() );
        }
        member( PA, L_A1, "member" );
        member( PA, L_A2, "member" );
        member( PA, L_A3, "member" );
        member( PA, L_BOTH, "lead" );
        member( PB, L_B1, "member" );
        member( PB, L_BOTH, "lead" );
        for ( int i = 0; i < 3; i++ ) {
            KEYS_A.add( item( PA, "Find alpha " + i, List.of( TAG ) ) );
            KEYS_B.add( item( PB, "Find beta " + i, List.of( TAG, ONLY_B ) ) );
        }
    }

    private static void member( final String project, final String login, final String role ) throws Exception {
        assertEquals( 204, admin.send( "PUT", "/api/work/projects/" + project + "/members", Map.of( "principal", login,
            "principalKind", "user", "role", role ) ).statusCode() );
    }

    private static String item( final String project, final String title, final List< String > labels ) throws Exception {
        final HttpResponse< String > made = both.send( "POST", "/api/work/items", Map.of( "project", project,
            "level", "task", "title", title, "labels", labels ) );
        assertEquals( 201, made.statusCode(), made.body() );
        return json( made ).getAsJsonObject().getAsJsonObject( "item" ).get( "key" ).getAsString();
    }

    /** The keys of a find answer; the answer is {"items": [...], "next": ...}. */
    private static List< String > keys( final HttpResponse< String > r ) {
        assertEquals( 200, r.statusCode(), r.body() );
        final List< String > out = new ArrayList<>();
        for ( final JsonElement e : json( r ).getAsJsonObject().getAsJsonArray( "items" ) ) {
            out.add( e.getAsJsonObject().get( "key" ).getAsString() );
        }
        return out;
    }

    private static String next( final HttpResponse< String > r ) {
        final JsonElement n = json( r ).getAsJsonObject().get( "next" );
        return n == null || n.isJsonNull() ? null : n.getAsString();
    }

    private static boolean anyOf( final List< String > found, final String prefix ) {
        return found.stream().anyMatch( k -> k.startsWith( prefix ) );
    }

    private static String uniqueName( final String stem ) {
        return stem + "-" + System.nanoTime();
    }

    /** Saves a filter and returns its id (201, the view at the top level). */
    private static long save( final Caller who, final String name, final String project, final boolean shared,
                              final Map< String, String > filter ) throws Exception {
        final Map< String, Object > body = new java.util.HashMap<>();
        body.put( "name", name );
        if ( project != null ) {
            body.put( "project", project );
        }
        body.put( "shared", shared );
        body.put( "filter", filter );
        final HttpResponse< String > r = who.send( "POST", "/api/work/filters", body );
        assertEquals( 201, r.statusCode(), r.body() );
        return json( r ).getAsJsonObject().get( "id" ).getAsLong();
    }

    private static Set< Long > listedIds( final Caller who ) throws Exception {
        final HttpResponse< String > r = who.get( "/api/work/filters" );
        assertEquals( 200, r.statusCode(), r.body() );
        final Set< Long > ids = new HashSet<>();
        for ( final JsonElement e : json( r ).getAsJsonObject().getAsJsonArray( "filters" ) ) {
            ids.add( e.getAsJsonObject().get( "id" ).getAsLong() );
        }
        return ids;
    }

    @Test
    void aMemberOfOneProjectNeverFindsAnotherWhateverTheyAsk() throws Exception {
        final List< String > plain = keys( aOnly.get( "/api/work/items?label=" + TAG + "&limit=200" ) );
        assertTrue( plain.containsAll( KEYS_A ), "A's member finds A's items: " + plain );
        assertFalse( anyOf( plain, PB + "-" ), "and none of B's: " + plain );

        assertEquals( List.of(), keys( aOnly.get( "/api/work/items?projects=" + PB ) ), "a named hidden project gives no rows" );
        assertEquals( List.of(), keys( aOnly.get( "/api/work/items?projects=" + PB + "&label=" + TAG ) ) );
        assertEquals( List.of(), keys( aOnly.get( "/api/work/items?label=" + ONLY_B ) ), "a label that exists only in B" );
        final List< String > mixed = keys( aOnly.get( "/api/work/items?projects=" + PA + "," + PB + "&label=" + TAG + "&limit=200" ) );
        assertFalse( anyOf( mixed, PB + "-" ), "naming both still yields only the visible one: " + mixed );

        // a cursor from a B member: the other user's search never widens
        final HttpResponse< String > page = bOnly.get( "/api/work/items?label=" + TAG + "&limit=1" );
        assertEquals( 200, page.statusCode(), page.body() );
        final String cursor = next( page );
        assertTrue( cursor != null, "B holds three items, so a page of one has a next cursor" );
        final HttpResponse< String > replay = aOnly.get( "/api/work/items?label=" + TAG + "&limit=200&cursor=" + cursor );
        assertEquals( 200, replay.statusCode(), replay.body() );
        assertFalse( anyOf( keys( replay ), PB + "-" ), "a cursor taken from a B member shows A's member no B row" );
        assertEquals( List.of(), keys( aOnly.get( "/api/work/items?projects=" + PB + "&limit=200&cursor=" + cursor ) ) );
    }

    @Test
    void aMemberOfBothFindsBoth() throws Exception {
        final List< String > found = keys( both.get( "/api/work/items?label=" + TAG + "&limit=200" ) );
        assertTrue( found.containsAll( KEYS_A ), found.toString() );
        assertTrue( found.containsAll( KEYS_B ), found.toString() );
        final List< String > onlyB = keys( both.get( "/api/work/items?projects=" + PB + "&label=" + ONLY_B + "&limit=200" ) );
        assertTrue( onlyB.containsAll( KEYS_B ) );
        assertFalse( anyOf( onlyB, PA + "-" ) );
    }

    @Test
    void thePagerReturnsEveryRowOnce() throws Exception {
        final List< String > seen = new ArrayList<>();
        String cursor = null;
        int pages = 0;
        do {
            final HttpResponse< String > r = both.get( "/api/work/items?label=" + TAG + "&limit=2"
                + ( cursor == null ? "" : "&cursor=" + cursor ) );
            final List< String > page = keys( r );
            assertTrue( page.size() <= 2, "a page holds at most the limit: " + page );
            seen.addAll( page );
            cursor = next( r );
            pages++;
        } while ( cursor != null && pages < 20 );
        assertTrue( cursor == null, "the pages end" );
        assertEquals( seen.size(), new HashSet<>( seen ).size(), "no row twice: " + seen );
        final Set< String > mine = new HashSet<>( KEYS_A );
        mine.addAll( KEYS_B );
        assertEquals( mine, new HashSet<>( seen ), "every one of this class's rows, and only those (the label is its own)" );
    }

    @Test
    void aMalformedCursorIs400NamingCursor() throws Exception {
        for ( final String bad : List.of( "%25%25%25", "bm9wZQ", "x" ) ) {
            final HttpResponse< String > r = both.get( "/api/work/items?label=" + TAG + "&cursor=" + bad );
            assertEquals( 400, r.statusCode(), bad + ": " + r.body() );
            assertTrue( r.body().contains( "cursor" ), r.body() );
        }
    }

    @Test
    void aSharedFilterReachesItsProjectsMembersOnly() throws Exception {
        final long id = save( aOnly, uniqueName( "shared-a" ), PA, true, Map.of( "label", TAG ) );
        assertTrue( listedIds( aOnly ).contains( id ), "the owner sees it" );
        assertTrue( listedIds( aSecond ).contains( id ), "a second FNDA member sees it" );
        assertTrue( listedIds( both ).contains( id ), "so does a lead of FNDA" );
        assertFalse( listedIds( bOnly ).contains( id ), "an FNDB-only member does not" );
        assertFalse( listedIds( outsider ).contains( id ), "nor does a person with no project" );
        for ( final Caller hidden : List.of( bOnly, outsider ) ) {
            assertEquals( 404, hidden.send( "PATCH", "/api/work/filters/" + id, Map.of( "name", uniqueName( "x" ) ) )
                .statusCode() );
            assertEquals( 404, hidden.send( "DELETE", "/api/work/filters/" + id, null ).statusCode() );
        }
        assertTrue( listedIds( aSecond ).contains( id ), "still there after the refused calls" );
        assertEquals( 204, aOnly.send( "DELETE", "/api/work/filters/" + id, null ).statusCode() );
    }

    @Test
    void aPersonalFilterIsTheOwnersAlone() throws Exception {
        final long id = save( aOnly, uniqueName( "mine-a" ), PA, false, Map.of( "label", TAG ) );
        assertTrue( listedIds( aOnly ).contains( id ) );
        assertFalse( listedIds( aSecond ).contains( id ), "a colleague in the same project does not see it" );
        assertFalse( listedIds( both ).contains( id ), "not even a lead" );
        assertEquals( 404, aSecond.send( "PATCH", "/api/work/filters/" + id, Map.of( "name", uniqueName( "x" ) ) ).statusCode() );
        assertEquals( 404, aSecond.send( "DELETE", "/api/work/filters/" + id, null ).statusCode() );
        assertTrue( listedIds( aOnly ).contains( id ), "untouched" );
        assertEquals( 204, aOnly.send( "DELETE", "/api/work/filters/" + id, null ).statusCode() );
        assertFalse( listedIds( aOnly ).contains( id ), "deleted" );
    }

    @Test
    void aNonMemberGetsNotFoundOnEverySavedFilterRoute() throws Exception {
        final long shared = save( aOnly, uniqueName( "sweep-s" ), PA, true, Map.of( "label", TAG ) );
        final long personal = save( aOnly, uniqueName( "sweep-p" ), null, false, Map.of( "label", TAG ) );
        for ( final Caller stranger : List.of( bOnly, outsider ) ) {
            final HttpResponse< String > into = stranger.send( "POST", "/api/work/filters", Map.of( "name",
                uniqueName( "sweep-into" ), "project", PA, "shared", true, "filter", Map.of( "label", TAG ) ) );
            assertEquals( 404, into.statusCode(), into.body() );
            for ( final long id : List.of( shared, personal ) ) {
                assertEquals( 404, stranger.send( "PATCH", "/api/work/filters/" + id,
                    Map.of( "name", uniqueName( "x" ) ) ).statusCode(), "rename " + id );
                assertEquals( 404, stranger.send( "DELETE", "/api/work/filters/" + id, null ).statusCode(), "delete " + id );
            }
            final Set< Long > theirs = listedIds( stranger );
            assertFalse( theirs.contains( shared ) || theirs.contains( personal ) );
        }
        assertEquals( 404, outsider.send( "DELETE", "/api/work/filters/not-a-number", null ).statusCode() );
        assertEquals( 204, aOnly.send( "DELETE", "/api/work/filters/" + shared, null ).statusCode() );
        assertEquals( 204, aOnly.send( "DELETE", "/api/work/filters/" + personal, null ).statusCode() );
    }

    private static String enc( final String v ) {
        return java.net.URLEncoder.encode( v, java.nio.charset.StandardCharsets.UTF_8 ).replace( "+", "%20" );
    }

    private static String makeItem( final Caller who, final String title, final String assignee ) throws Exception {
        final HttpResponse< String > made = who.send( "POST", "/api/work/items", Map.of( "project", PA,
            "level", "task", "title", title, "assignee", assignee ) );
        assertEquals( 201, made.statusCode(), made.body() );
        return json( made ).getAsJsonObject().getAsJsonObject( "item" ).get( "key" ).getAsString();
    }

    private static JsonObject viewOf( final Caller who, final long id ) throws Exception {
        for ( final JsonElement e : json( who.get( "/api/work/filters" ) ).getAsJsonObject().getAsJsonArray( "filters" ) ) {
            if ( e.getAsJsonObject().get( "id" ).getAsLong() == id ) {
                return e.getAsJsonObject();
            }
        }
        throw new AssertionError( "filter " + id + " is not listed" );
    }

    @Test
    void aPersonInASharedFilterSurvivesTheRoundTripForANonAdmin() throws Exception {
        final String key = makeItem( aOnly, "Person chain " + RUN, N_A2 );
        assertTrue( keys( aOnly.get( "/api/work/items?assignee=" + enc( N_A2 ) ) ).contains( key ),
            "find by the display name the item routes accept" );

        final long id = save( aOnly, uniqueName( "person" ), PA, true, Map.of( "assignee", N_A2 ) );
        final HttpResponse< String > listing = aThird.get( "/api/work/filters" );
        assertEquals( 200, listing.statusCode(), listing.body() );
        for ( final String login : List.of( L_A1, L_A2, L_A3, L_B1, L_BOTH, L_OUT ) ) {
            assertFalse( listing.body().contains( login ), "no login in the listing: " + login );
        }
        assertTrue( listing.body().contains( N_A2 ), "the display name is there" );

        final String shown = viewOf( aThird, id ).getAsJsonObject( "filter" ).get( "assignee" ).getAsString();
        assertEquals( N_A2, shown );
        // the value exactly as listed, run by a third member who is neither owner nor assignee
        assertTrue( keys( aThird.get( "/api/work/items?assignee=" + enc( shown ) ) ).contains( key ),
            "the listed value finds the item for the third member" );
        assertEquals( 204, aOnly.send( "DELETE", "/api/work/filters/" + id, null ).statusCode() );
    }

    @Test
    void meMeansTheCallerForWhoeverRunsTheFilter() throws Exception {
        final String mine = makeItem( aOnly, "Mine one " + RUN, N_A1 );
        final String theirs = makeItem( aOnly, "Mine two " + RUN, N_A2 );
        final long id = save( aOnly, uniqueName( "me" ), null, false, Map.of( "assignee", "me" ) );
        assertEquals( "me", viewOf( aOnly, id ).getAsJsonObject( "filter" ).get( "assignee" ).getAsString() );

        final List< String > forOwner = keys( aOnly.get( "/api/work/items?assignee=me" ) );
        assertTrue( forOwner.contains( mine ) );
        assertFalse( forOwner.contains( theirs ) );
        final List< String > forOther = keys( aSecond.get( "/api/work/items?assignee=me" ) );
        assertTrue( forOther.contains( theirs ), "me is the runner" );
        assertFalse( forOther.contains( mine ) );
        assertEquals( 204, aOnly.send( "DELETE", "/api/work/filters/" + id, null ).statusCode() );
    }

    @Test
    void aVisibleSharedFilterOfSomeoneElseIs403AndEverythingHiddenIsTheSame404() throws Exception {
        final long shared = save( aOnly, uniqueName( "boundary-s" ), PA, true, Map.of( "label", TAG ) );
        final long personal = save( aOnly, uniqueName( "boundary-p" ), PA, false, Map.of( "label", TAG ) );

        // visible, not theirs: refused, not hidden
        assertEquals( 403, aSecond.send( "PATCH", "/api/work/filters/" + shared, Map.of( "name", uniqueName( "x" ) ) )
            .statusCode(), "rename is the owner's" );
        assertEquals( 403, aSecond.send( "DELETE", "/api/work/filters/" + shared, null ).statusCode(),
            "a non-lead member may not delete it" );
        assertTrue( listedIds( aOnly ).contains( shared ) );

        // hidden, another person's personal, unknown and non-numeric: one and the same 404
        final HttpResponse< String > hidden = bOnly.send( "DELETE", "/api/work/filters/" + shared, null );
        final HttpResponse< String > others = aSecond.send( "DELETE", "/api/work/filters/" + personal, null );
        final HttpResponse< String > unknown = aSecond.send( "DELETE", "/api/work/filters/9223372036854775807", null );
        final HttpResponse< String > text = aSecond.send( "DELETE", "/api/work/filters/not-a-number", null );
        for ( final HttpResponse< String > r : List.of( hidden, others, unknown, text ) ) {
            assertEquals( 404, r.statusCode(), r.body() );
            assertEquals( hidden.body(), r.body(), "the 404 bodies are identical" );
        }

        // the project's lead may delete the member's shared filter
        assertEquals( 204, both.send( "DELETE", "/api/work/filters/" + shared, null ).statusCode() );
        assertFalse( listedIds( aOnly ).contains( shared ) );
        assertEquals( 204, aOnly.send( "DELETE", "/api/work/filters/" + personal, null ).statusCode() );
    }
}
