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
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.wikantik.work.CodeEvent;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GenericHookAdapterTest {

    private static final String B = "1111111111111111111111111111111111111111";
    private static final String H1 = "aaaaaaa1aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final String H2 = "bbbbbbb2bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
    private static final String M = "cccccccccccccccccccccccccccccccccccccc12";

    @Test
    void aPushTranslatesAndADistinctFlagDefaultsToTrue() {
        final List< CodeEvent > events = GenericHookAdapter.translate( GithubHookAdapterTest.fixture( "generic-push" ) );
        assertEquals( List.of( new CodeEvent( CodeEvent.Kind.PUSH, "octo/plat", "main", "PLAT-3-x", null, B, H2,
            Instant.parse( "2026-02-01T10:00:00Z" ), List.of(
                new CodeEvent.Commit( H1, "PLAT-3 one", Instant.parse( "2026-02-01T09:00:00Z" ), true,
                    new CodeEvent.Person( "ana-gh", "Ana A", "ana@example.com" ) ),
                new CodeEvent.Commit( H2, "PLAT-3 two", Instant.parse( "2026-02-01T10:00:00Z" ), false, null ) ),
            null, null, null ) ), events );
    }

    @Test
    void aMergedReviewCarriesTheReviewTheActorAndTheMergeSha() {
        final CodeEvent e = GenericHookAdapter.translate( GithubHookAdapterTest.fixture( "generic-merged" ) ).get( 0 );
        assertEquals( CodeEvent.Kind.REVIEW_MERGED, e.kind() );
        assertEquals( new CodeEvent.Review( "7", "https://git.example.com/mr/7", "PLAT-3 x", H2, false,
            new CodeEvent.Person( "ana-gh", null, null ) ), e.review() );
        assertEquals( new CodeEvent.Person( "lena-gh", null, null ), e.actor() );
        assertEquals( M, e.mergedSha() );
        assertEquals( "main", e.baseBranch() );
    }

    @Test
    void anUnknownKindAndAMissingBranchGiveNothing() {
        assertTrue( GenericHookAdapter.translate( JsonParser.parseString( "{\"kind\":\"tag\",\"branch\":\"x\"}" ) ).isEmpty() );
        final JsonObject noBranch = GithubHookAdapterTest.fixture( "generic-push" );
        noBranch.remove( "branch" );
        assertTrue( GenericHookAdapter.translate( noBranch ).isEmpty() );
    }

    @Test
    void anArrayHoldsSeveralEventsAndSkipsTheBadOnes() {
        final JsonArray array = new JsonArray();
        array.add( GithubHookAdapterTest.fixture( "generic-push" ) );
        array.add( 5 );
        array.add( JsonParser.parseString( "{\"kind\":\"nonsense\",\"branch\":\"b\"}" ) );
        array.add( GithubHookAdapterTest.fixture( "generic-merged" ) );
        assertEquals( 2, GenericHookAdapter.translate( array ).size() );
        final JsonArray flood = new JsonArray();
        for ( int i = 0; i < 1000; i++ ) {
            flood.add( GithubHookAdapterTest.fixture( "generic-push" ) );
        }
        assertEquals( HookJson.MAX_EVENTS, GenericHookAdapter.translate( flood ).size() );
    }

    @Test
    void scalarsNullAndWrongTypesNeverThrow() {
        assertTrue( GenericHookAdapter.translate( null ).isEmpty() );
        assertTrue( GenericHookAdapter.translate( JsonParser.parseString( "5" ) ).isEmpty() );
        assertTrue( GenericHookAdapter.translate( JsonParser.parseString( "\"x\"" ) ).isEmpty() );
        final JsonObject wrong = JsonParser.parseString( "{\"kind\":7,\"branch\":[],\"commits\":{}}" ).getAsJsonObject();
        assertTrue( GenericHookAdapter.translate( wrong ).isEmpty() );
        final JsonObject odd = JsonParser.parseString( "{\"kind\":\"push\",\"branch\":\"b\",\"at\":\"3000000-01-01T00:00:00Z\","
            + "\"commits\":[null,{\"sha\":5},{\"sha\":\"abc\",\"author\":[]}],\"review\":5,\"actor\":7}" ).getAsJsonObject();
        final List< CodeEvent > events = GenericHookAdapter.translate( odd );
        assertEquals( 1, events.size() );
        assertEquals( null, events.get( 0 ).at() );
        assertEquals( 1, events.get( 0 ).commits().size() );
        assertEquals( null, events.get( 0 ).actor() );
    }

    @Test
    void aCapturedGenericDeliveryIsTimedByItsAtAndTranslatesTheSameEveryTime() {
        for ( final String name : List.of( "generic-push", "generic-merged" ) ) {
            final List< CodeEvent > once = GenericHookAdapter.translate( GithubHookAdapterTest.fixture( name ) );
            assertEquals( once, GenericHookAdapter.translate( GithubHookAdapterTest.fixture( name ) ), name );
            assertTrue( !once.isEmpty() && once.stream().allMatch( e -> e.at() != null ), name );
        }
        final List< CodeEvent > reopened = GenericHookAdapter.translate( JsonParser.parseString(
            "{\"kind\":\"review_reopened\",\"branch\":\"b\",\"review\":{\"number\":12}}" ) );
        assertEquals( CodeEvent.Kind.REVIEW_REOPENED, reopened.get( 0 ).kind() );
        assertEquals( null, reopened.get( 0 ).at(), "no at: translated untimed, and the intake rejects the delivery" );
    }
}
