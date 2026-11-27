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

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.wikantik.work.CodeEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The tolerant readers every code-host adapter builds on. A delivery is hostile input: a field of the wrong type, a
 * blank, an oversized string or an out-of-range time reads as "absent", never as an exception and never unbounded.
 */
class HookJsonTest {

    private static JsonObject o( final String json ) {
        return JsonParser.parseString( json ).getAsJsonObject();
    }

    // ---- obj / arr ----

    @Test
    void objAndArrReturnOnlyTheirOwnShapeAndNullForAnythingElse() {
        final JsonObject o = o( "{\"o\":{\"x\":1},\"a\":[1],\"s\":\"t\",\"n\":null}" );

        assertEquals( 1, HookJson.obj( o, "o" ).get( "x" ).getAsInt() );
        assertNull( HookJson.obj( o, "a" ) );
        assertNull( HookJson.obj( o, "s" ) );
        assertNull( HookJson.obj( o, "n" ) );
        assertNull( HookJson.obj( o, "missing" ) );
        assertNull( HookJson.obj( null, "o" ) );
        assertEquals( 1, HookJson.arr( o, "a" ).size() );
        assertNull( HookJson.arr( o, "o" ) );
        assertNull( HookJson.arr( o, "s" ) );
        assertNull( HookJson.arr( o, "missing" ) );
        assertNull( HookJson.arr( null, "a" ) );
    }

    // ---- str ----

    @Test
    void strReadsOnlyNonBlankJsonStrings() {
        final JsonObject o = o( "{\"s\":\"v\",\"blank\":\"   \",\"empty\":\"\",\"n\":5,\"b\":true,\"a\":[\"x\"],\"o\":{},\"nul\":null}" );

        assertEquals( "v", HookJson.str( o, "s", 10 ) );
        for ( final String key : List.of( "blank", "empty", "n", "b", "a", "o", "nul", "missing" ) ) {
            assertNull( HookJson.str( o, key, 10 ), key );
        }
        assertNull( HookJson.str( null, "s", 10 ) );
    }

    @ParameterizedTest
    @CsvSource( { "4,abcd", "5,abcde", "6,abcde", "1,a" } )
    void strCutsToExactlyMaxCharacters( final int max, final String expected ) {
        assertEquals( expected, HookJson.str( o( "{\"s\":\"abcde\"}" ), "s", max ) );
    }

    @Test
    void strKeepsInteriorWhitespaceAndDoesNotTrim() {
        assertEquals( " a b ", HookJson.str( o( "{\"s\":\" a b \"}" ), "s", 10 ) );
    }

    // ---- bool ----

    @Test
    void boolReadsOnlyJsonBooleansElseTheFallback() {
        final JsonObject o = o( "{\"t\":true,\"f\":false,\"s\":\"true\",\"n\":1,\"nul\":null}" );

        assertTrue( HookJson.bool( o, "t", false ) );
        assertFalse( HookJson.bool( o, "f", true ) );
        for ( final String key : List.of( "s", "n", "nul", "missing" ) ) {
            assertTrue( HookJson.bool( o, key, true ), key );
            assertFalse( HookJson.bool( o, key, false ), key );
        }
        assertTrue( HookJson.bool( null, "t", true ) );
        assertFalse( HookJson.bool( null, "t", false ) );
    }

    @Test
    void isTrueIsOnlyTheJsonBooleanTrue() {
        final JsonObject o = o( "{\"t\":true,\"f\":false,\"s\":\"true\",\"n\":1}" );

        assertTrue( HookJson.isTrue( o, "t" ) );
        assertFalse( HookJson.isTrue( o, "f" ) );
        assertFalse( HookJson.isTrue( o, "s" ) );
        assertFalse( HookJson.isTrue( o, "n" ) );
        assertFalse( HookJson.isTrue( o, "missing" ) );
    }

    // ---- number ----

    @ParameterizedTest
    @CsvSource( { "12,12", "1,1", "999999999,999999999", "12.0,12", "1.2e1,12", "'\"12\"',12", "'\"999999999\"',999999999" } )
    void numberReadsAnIntegerOfAtMostNineDigits( final String literal, final String expected ) {
        assertEquals( expected, HookJson.number( o( "{\"n\":" + literal + "}" ), "n" ) );
    }

    @ParameterizedTest
    @ValueSource( strings = { "0", "-1", "1000000000", "1.5", "1e9", "\"0\"", "\"012\"", "\"1000000000\"", "\"12a\"",
            "\" 12\"", "\"\"", "\"+5\"", "true", "null", "[1]", "{}", "1e400", "-0" } )
    void numberRefusesZeroNegativesFractionsAndTenDigitsAndAnythingNotANumber( final String literal ) {
        assertNull( HookJson.number( o( "{\"n\":" + literal + "}" ), "n" ), literal );
    }

    @Test
    void numberOfAMissingKeyOrNullObjectIsNull() {
        assertNull( HookJson.number( o( "{}" ), "n" ) );
        assertNull( HookJson.number( null, "n" ) );
    }

    // ---- time ----

    @ParameterizedTest
    @CsvSource( {
            "2026-03-01T10:00:00Z,2026-03-01T10:00:00Z",
            "2026-03-01T12:00:00+02:00,2026-03-01T10:00:00Z",
            "2026-03-01T05:30:00-04:30,2026-03-01T10:00:00Z",
            "1970-01-01T00:00:00Z,1970-01-01T00:00:00Z",
            "9999-12-31T23:59:59Z,9999-12-31T23:59:59Z" } )
    void timeReadsIsoInstantsWithAnOffsetOrZ( final String text, final String expected ) {
        assertEquals( Instant.parse( expected ), HookJson.time( o( "{\"at\":\"" + text + "\"}" ), "at" ) );
    }

    @ParameterizedTest
    @ValueSource( strings = { "1969-12-31T23:59:59Z", "0001-01-01T00:00:00Z", "10000-01-01T00:00:00Z",
            "+1000000000-12-31T23:59:59Z", "-1000000000-01-01T00:00:00Z", "yesterday", "2026-03-01", "2026-03-01T10:00:00",
            "2026-13-01T10:00:00Z", "2026-02-30T10:00:00Z", "", "   " } )
    void timeOutsideTheDateRangeOrNotIsoWithAnOffsetIsNull( final String text ) {
        assertNull( HookJson.time( o( "{\"at\":\"" + text + "\"}" ), "at" ), text );
    }

    @Test
    void timeBecauseOfAWesternOffsetCanLandBefore1970AndIsNull() {
        // 1970-01-01T00:00 at -05:00 is 05:00Z, fine; 1969-12-31T23:00 at -01:00 is 1970-01-01T00:00Z, fine;
        // 1970-01-01T00:00 at +01:00 is 1969-12-31T23:00Z: out of range
        assertEquals( Instant.parse( "1970-01-01T05:00:00Z" ), HookJson.time( o( "{\"at\":\"1970-01-01T00:00:00-05:00\"}" ), "at" ) );
        assertNull( HookJson.time( o( "{\"at\":\"1970-01-01T00:00:00+01:00\"}" ), "at" ) );
    }

    @Test
    void timeFromANonStringIsNull() {
        assertNull( HookJson.time( o( "{\"at\":1772366400}" ), "at" ) );
        assertNull( HookJson.time( o( "{\"at\":null}" ), "at" ) );
        assertNull( HookJson.time( o( "{}" ), "at" ) );
        assertNull( HookJson.time( null, "at" ) );
    }

    // ---- epochOrTime ----

    @ParameterizedTest
    @CsvSource( { "0,1970-01-01T00:00:00Z", "1772366400,2026-03-01T12:00:00Z", "253402300799,9999-12-31T23:59:59Z",
            "1772366400.9,2026-03-01T12:00:00Z" } )
    void epochSecondsReadAsAnInstantUpToTheYear9999( final String seconds, final String expected ) {
        assertEquals( Instant.parse( expected ), HookTimes.epochOrTime( o( "{\"t\":" + seconds + "}" ), "t" ) );
    }

    @ParameterizedTest
    @ValueSource( strings = { "-1", "-0.5", "253402300800", "9223372036854775807", "1e30" } )
    void epochSecondsOutsideThe1970To9999RangeAreNull( final String seconds ) {
        assertNull( HookTimes.epochOrTime( o( "{\"t\":" + seconds + "}" ), "t" ), seconds );
    }

    @Test
    void aSecondsFractionJustBelowZeroIsStillNegative() {
        assertNull( HookTimes.epochOrTime( o( "{\"t\":-0.0000001}" ), "t" ) );
    }

    @Test
    void epochOrTimeFallsBackToTheTextFormAndToNullForTheRest() {
        assertEquals( Instant.parse( "2026-03-01T10:00:00Z" ), HookTimes.epochOrTime( o( "{\"t\":\"2026-03-01T10:00:00Z\"}" ), "t" ) );
        assertNull( HookTimes.epochOrTime( o( "{\"t\":\"soon\"}" ), "t" ) );
        assertNull( HookTimes.epochOrTime( o( "{\"t\":true}" ), "t" ) );
        assertNull( HookTimes.epochOrTime( o( "{}" ), "t" ) );
        assertNull( HookTimes.epochOrTime( null, "t" ) );
    }

    // ---- person / login / loggable ----

    @Test
    void aPersonNeedsAtLeastOneOfItsThreeNames() {
        assertEquals( new CodeEvent.Person( "octo", "Octo Cat", "o@x.io" ),
                HookJson.person( o( "{\"login\":\"octo\",\"name\":\"Octo Cat\",\"email\":\"o@x.io\"}" ), "login" ) );
        assertEquals( new CodeEvent.Person( null, "Octo", null ), HookJson.person( o( "{\"name\":\"Octo\"}" ), "login" ) );
        assertEquals( new CodeEvent.Person( null, null, "o@x.io" ), HookJson.person( o( "{\"email\":\"o@x.io\"}" ), "login" ) );
        assertEquals( new CodeEvent.Person( "octo", null, null ), HookJson.person( o( "{\"login\":\"octo\"}" ), "login" ) );
        assertNull( HookJson.person( o( "{}" ), "login" ) );
        assertNull( HookJson.person( o( "{\"login\":\" \",\"name\":5,\"email\":null}" ), "login" ) );
        assertNull( HookJson.person( null, "login" ) );
    }

    @Test
    void aPersonsNamesAreCutToTheNameLimit() {
        final String long300 = "n".repeat( 300 );

        final CodeEvent.Person p = HookJson.person( o( "{\"login\":\"" + long300 + "\",\"name\":\"" + long300 + "\",\"email\":\""
                + long300 + "\"}" ), "login" );

        assertEquals( HookJson.NAME, p.username().length() );
        assertEquals( HookJson.NAME, p.name().length() );
        assertEquals( HookJson.NAME, p.email().length() );
    }

    @Test
    void aLoginAloneIsAPersonAndNullIsNobody() {
        assertEquals( new CodeEvent.Person( "octo", null, null ), HookJson.login( "octo" ) );
        assertNull( HookJson.login( null ) );
    }

    @Test
    void aLoggedKindIsOneOfTheKnownOnesElseOther() {
        final Set< String > known = Set.of( "push", "ping" );

        assertEquals( "push", HookJson.loggable( "push", known ) );
        assertEquals( "other", HookJson.loggable( "evil\nforged line", known ) );
        assertEquals( "other", HookJson.loggable( null, known ) );
        assertEquals( "other", HookJson.loggable( "", known ) );
    }

    // ---- hostile deliveries through the adapters ----

    private static final String GITHUB_PUSH = """
        {"ref":"refs/heads/PLAT-1-x","before":"0000000000000000000000000000000000000000",
         "after":"aaaaaaa1aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
         "repository":{"full_name":"octo/plat","default_branch":"main","pushed_at":1772366400},
         "pusher":{"name":"octo","email":"o@x.io"},"sender":{"login":"octo"},
         "commits":[{"id":"aaaaaaa1aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","message":"PLAT-1 do it","distinct":true,
                     "timestamp":"2026-03-01T10:00:00Z","author":{"name":"A","email":"a@x.io","username":"au"}}]}""";

    private static final String GITHUB_PR = """
        {"action":"opened","number":12,"sender":{"login":"octo"},
         "repository":{"full_name":"octo/plat","default_branch":"main"},
         "pull_request":{"number":12,"html_url":"https://x/p/12","title":"PLAT-1 t","draft":false,
            "created_at":"2026-03-01T10:00:00Z","updated_at":"2026-03-01T10:00:00Z","merged":false,
            "head":{"ref":"PLAT-1-x","sha":"aaaaaaa1aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"},"base":{"ref":"main"},
            "user":{"login":"octo"}}}""";

    private static final String GENERIC = """
        {"kind":"review_opened","repo":"octo/plat","defaultBranch":"main","branch":"PLAT-1-x","baseBranch":"main",
         "after":"aaaaaaa1aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","at":"2026-03-01T10:00:00Z",
         "commits":[{"sha":"aaaaaaa1","message":"m","at":"2026-03-01T10:00:00Z","distinct":true,"author":{"username":"u"}}],
         "review":{"number":12,"url":"https://x/p/12","title":"t","headSha":"aaaaaaa1","draft":false,"author":{"username":"u"}},
         "actor":{"username":"octo"}}""";

    /** Every way one position of a valid payload can be replaced by something of the wrong type or size. */
    private static List< JsonElement > hostileValues() {
        final List< JsonElement > out = new ArrayList<>();
        for ( final String j : List.of( "null", "1", "-1", "1.5", "1e400", "true", "\"\"", "\" \"", "\"x\"", "[]", "{}",
                "[null]", "[[]]", "[{}]", "{\"a\":[]}", "\"0000000000000000000000000000000000000000\"",
                "\"3000000-01-01T00:00:00Z\"", "\"\\u0000\\n\\r\"" ) ) {
            out.add( JsonParser.parseString( j ) );
        }
        out.add( JsonParser.parseString( "\"" + "z".repeat( 20_000 ) + "\"" ) );
        final JsonArray many = new JsonArray();
        for ( int i = 0; i < 500; i++ ) {
            many.add( JsonParser.parseString( "{\"id\":\"a" + i + "\",\"sha\":\"a" + i + "\",\"kind\":\"push\",\"branch\":\"b\"}" ) );
        }
        out.add( many );
        return out;
    }

    /** Paths (as a list of keys/indexes) to every element below the root. */
    private static void paths( final JsonElement e, final List< Object > prefix, final List< List< Object > > into ) {
        if ( e.isJsonObject() ) {
            e.getAsJsonObject().entrySet().forEach( en -> {
                final List< Object > p = new ArrayList<>( prefix );
                p.add( en.getKey() );
                into.add( p );
                paths( en.getValue(), p, into );
            } );
        } else if ( e.isJsonArray() ) {
            for ( int i = 0; i < e.getAsJsonArray().size(); i++ ) {
                final List< Object > p = new ArrayList<>( prefix );
                p.add( i );
                into.add( p );
                paths( e.getAsJsonArray().get( i ), p, into );
            }
        }
    }

    private static JsonElement replaced( final JsonElement root, final List< Object > path, final JsonElement value ) {
        final JsonElement copy = root.deepCopy();
        JsonElement cur = copy;
        for ( int i = 0; i < path.size() - 1; i++ ) {
            cur = path.get( i ) instanceof Integer n ? cur.getAsJsonArray().get( n ) : cur.getAsJsonObject().get( (String) path.get( i ) );
        }
        if ( path.get( path.size() - 1 ) instanceof Integer n ) {
            cur.getAsJsonArray().set( n, value );
        } else {
            cur.getAsJsonObject().add( (String) path.get( path.size() - 1 ), value );
        }
        return copy;
    }

    private static void assertBounded( final List< CodeEvent > events, final String why ) {
        assertTrue( events.size() <= HookJson.MAX_EVENTS, why + ": " + events.size() + " events" );
        for ( final CodeEvent e : events ) {
            bounded( e.repo(), HookJson.NAME, why );
            bounded( e.defaultBranch(), HookJson.BRANCH, why );
            bounded( e.branch(), HookJson.BRANCH, why );
            bounded( e.baseBranch(), HookJson.BRANCH, why );
            bounded( e.before(), HookJson.SHA, why );
            bounded( e.after(), HookJson.SHA, why );
            bounded( e.mergedSha(), HookJson.SHA, why );
            assertTrue( e.commits().size() <= HookJson.MAX_COMMITS, why + ": " + e.commits().size() + " commits" );
            for ( final CodeEvent.Commit c : e.commits() ) {
                bounded( c.sha(), HookJson.SHA, why );
                bounded( c.message(), HookJson.MESSAGE, why );
            }
            if ( e.review() != null ) {
                bounded( e.review().number(), 9, why );
                bounded( e.review().url(), HookJson.URL, why );
                bounded( e.review().title(), HookJson.TITLE, why );
                bounded( e.review().headSha(), HookJson.SHA, why );
            }
        }
    }

    private static void bounded( final String s, final int max, final String why ) {
        assertTrue( s == null || s.length() <= max, why + ": a field of " + ( s == null ? 0 : s.length() ) + " characters passed a limit of " + max );
        assertTrue( s == null || !s.isBlank(), why + ": a blank field survived" );
    }

    @Test
    void aGithubPushWithAnyOneFieldReplacedByHostileJsonIsBoundedAndNeverThrows() {
        final JsonObject valid = o( GITHUB_PUSH );
        final List< List< Object > > paths = new ArrayList<>();
        paths( valid, List.of(), paths );
        int tried = 0;
        for ( final List< Object > path : paths ) {
            for ( final JsonElement hostile : hostileValues() ) {
                final JsonObject mutated = replaced( valid, path, hostile ).getAsJsonObject();
                final List< CodeEvent > events = GithubHookAdapter.translate( "push", mutated );
                assertBounded( events, "push " + path + " <- " + String.valueOf( hostile ).substring( 0, Math.min( 30, String.valueOf( hostile ).length() ) ) );
                tried++;
            }
        }
        assertTrue( tried > 200, "the corpus was too small to mean anything: " + tried );
    }

    @ParameterizedTest
    @ValueSource( strings = { "opened", "reopened", "closed", "ready_for_review", "synchronize", "edited" } )
    void aGithubPullRequestWithAnyOneFieldReplacedByHostileJsonIsBoundedAndNeverThrows( final String action ) {
        final JsonObject valid = o( GITHUB_PR );
        valid.addProperty( "action", action );
        final List< List< Object > > paths = new ArrayList<>();
        paths( valid, List.of(), paths );
        for ( final List< Object > path : paths ) {
            for ( final JsonElement hostile : hostileValues() ) {
                final List< CodeEvent > events = GithubHookAdapter.translate( "pull_request", replaced( valid, path, hostile ).getAsJsonObject() );
                assertBounded( events, "pull_request " + action + " " + path );
            }
        }
    }

    @Test
    void aGenericEventWithAnyOneFieldReplacedByHostileJsonIsBoundedAndNeverThrows() {
        final JsonObject valid = o( GENERIC );
        final List< List< Object > > paths = new ArrayList<>();
        paths( valid, List.of(), paths );
        for ( final List< Object > path : paths ) {
            for ( final JsonElement hostile : hostileValues() ) {
                assertBounded( GenericHookAdapter.translate( replaced( valid, path, hostile ) ), "generic " + path );
            }
        }
    }

    @Test
    void aGenericArrayBeyondTheEventLimitIsCutNotRefused() {
        final JsonArray array = new JsonArray();
        for ( int i = 0; i < HookJson.MAX_EVENTS + 25; i++ ) {
            array.add( o( "{\"kind\":\"push\",\"branch\":\"b" + i + "\",\"at\":\"2026-03-01T10:00:00Z\"}" ) );
        }

        final List< CodeEvent > events = GenericHookAdapter.translate( array );

        assertEquals( HookJson.MAX_EVENTS, events.size() );
        assertEquals( "b0", events.get( 0 ).branch() );
        assertEquals( "b" + ( HookJson.MAX_EVENTS - 1 ), events.get( HookJson.MAX_EVENTS - 1 ).branch() );
    }

    @Test
    void aPushOfMoreCommitsThanTheLimitKeepsOnlyTheLimit() {
        final JsonObject push = o( GITHUB_PUSH );
        final JsonArray commits = new JsonArray();
        for ( int i = 0; i < HookJson.MAX_COMMITS + 40; i++ ) {
            commits.add( o( "{\"id\":\"c" + i + "\",\"message\":\"m\",\"distinct\":true}" ) );
        }
        push.add( "commits", commits );

        final List< CodeEvent > events = GithubHookAdapter.translate( "push", push );

        assertEquals( 1, events.size() );
        assertEquals( HookJson.MAX_COMMITS, events.get( 0 ).commits().size() );
    }
}
