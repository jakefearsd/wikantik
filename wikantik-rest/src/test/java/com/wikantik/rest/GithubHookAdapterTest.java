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

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.wikantik.work.CodeEvent;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GithubHookAdapterTest {

    private static final String B = "1111111111111111111111111111111111111111";
    private static final String H1 = "aaaaaaa1aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final String H2 = "bbbbbbb2bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
    private static final String M = "cccccccccccccccccccccccccccccccccccccc12";
    private static final CodeEvent.Person ANA = new CodeEvent.Person( "ana-gh", "Ana A", "ana@example.com" );
    private static final CodeEvent.Person WEB_FLOW = new CodeEvent.Person( "web-flow", "GitHub", "noreply@github.com" );

    static JsonObject fixture( final String name ) {
        try ( InputStream in = GithubHookAdapterTest.class.getResourceAsStream( "/work-hooks/" + name + ".json" ) ) {
            return JsonParser.parseReader( new InputStreamReader( in, StandardCharsets.UTF_8 ) ).getAsJsonObject();
        } catch ( final IOException e ) {
            throw new IllegalStateException( "fixture " + name, e );
        }
    }

    private static CodeEvent only( final String header, final String fixture ) {
        final List< CodeEvent > events = GithubHookAdapter.translate( header, fixture( fixture ) );
        assertEquals( 1, events.size(), fixture );
        return events.get( 0 );
    }

    private static CodeEvent.Commit commit( final String sha, final String message, final String at, final boolean distinct,
                                            final CodeEvent.Person who ) {
        return new CodeEvent.Commit( sha, message, Instant.parse( at ), distinct, who );
    }

    private static final CodeEvent.Review REVIEW = new CodeEvent.Review( "12", "https://github.com/octo/plat/pull/12",
        "PLAT-1 fix login", H2, false, new CodeEvent.Person( "ana-gh", null, null ) );

    @Test
    void aBranchPushCarriesItsCommitsWithOffsetTimesConverted() {
        assertEquals( new CodeEvent( CodeEvent.Kind.PUSH, "octo/plat", "main", "PLAT-1-fix-login", null, B, H2,
            Instant.parse( "2026-01-05T09:30:00Z" ), List.of(
                commit( H1, "PLAT-1 start", "2026-01-05T08:00:00Z", true, ANA ),
                commit( H2, "PLAT-1 finish", "2026-01-05T09:30:00Z", true, ANA ) ), null, null, null ),
            only( "push", "push-branch" ) );
    }

    @Test
    void aDirectPushToTheDefaultBranchIsAPush() {
        final CodeEvent e = only( "push", "push-default-direct" );
        assertEquals( "main", e.branch() );
        assertEquals( H1, e.after() );
        assertEquals( List.of( commit( H1, "PLAT-2 hotfix", "2026-01-06T12:00:00Z", true, ANA ) ), e.commits() );
    }

    @Test
    void aPushNamesThePusherByTheSendersLoginOnly() {
        // the pusher object's name and address are not bound to an account; the sender's login is (M-1)
        assertEquals( new CodeEvent.Person( "mallory-gh", null, null ), only( "push", "push-default-direct" ).actor() );
        assertNull( only( "push", "push-branch" ).actor(), "a push with no sender has no pusher" );
    }

    @Test
    void aMergeCommitPushMarksThePullRequestsCommitsNotDistinct() {
        final CodeEvent e = only( "push", "push-default-merge-commit" );
        assertEquals( M, e.after() );
        assertEquals( List.of(
            commit( H1, "PLAT-1 start", "2026-01-05T09:00:00Z", false, ANA ),
            commit( H2, "PLAT-1 finish", "2026-01-05T10:30:00Z", false, ANA ),
            commit( M, "Merge pull request #12 from octo/PLAT-1-fix-login", "2026-01-07T08:00:00Z", true, WEB_FLOW ) ),
            e.commits() );
    }

    @Test
    void aSquashPushHasOneDistinctCommitNamingTheNumber() {
        final CodeEvent e = only( "push", "push-default-squash" );
        assertEquals( List.of( commit( M, "PLAT-1 fix login (#12)", "2026-01-07T08:00:00Z", true, ANA ) ), e.commits() );
    }

    @Test
    void aRebasePushHasTwoDistinctCommitsAndNoNumber() {
        final CodeEvent e = only( "push", "push-default-rebase" );
        assertEquals( H2, e.after() );
        assertEquals( 2, e.commits().size() );
        assertTrue( e.commits().stream().allMatch( CodeEvent.Commit::distinct ) );
        assertTrue( e.commits().stream().noneMatch( c -> c.message().contains( "#12" ) ) );
    }

    @Test
    void aTagAndABranchDeletionGiveNothing() {
        assertTrue( GithubHookAdapter.translate( "push", fixture( "push-tag" ) ).isEmpty() );
        assertTrue( GithubHookAdapter.translate( "push", fixture( "push-delete" ) ).isEmpty() );
    }

    @Test
    void anOpenedPullRequest() {
        assertEquals( new CodeEvent( CodeEvent.Kind.REVIEW_OPENED, "octo/plat", "main", "PLAT-1-fix-login", "main", null,
            null, Instant.parse( "2026-01-05T11:00:00Z" ), List.of(), REVIEW, null, null ), only( "pull_request", "pr-opened" ) );
    }

    @Test
    void aDraftIsMarkedDraft() {
        assertTrue( only( "pull_request", "pr-opened-draft" ).review().draft() );
    }

    @Test
    void reopenedReadyAndReviewRequestedAndSynchronizeMapToTheirKinds() {
        assertEquals( CodeEvent.Kind.REVIEW_REOPENED, only( "pull_request", "pr-reopened" ).kind() );
        assertEquals( Instant.parse( "2026-01-06T11:00:00Z" ), only( "pull_request", "pr-reopened" ).at() );
        assertEquals( CodeEvent.Kind.REVIEW_READY, only( "pull_request", "pr-ready" ).kind() );
        assertEquals( CodeEvent.Kind.REVIEW_READY, only( "pull_request", "pr-review-requested" ).kind() );
        final CodeEvent sync = only( "pull_request", "pr-synchronize" );
        assertEquals( CodeEvent.Kind.REVIEW_UPDATED, sync.kind() );
        assertEquals( H2, sync.review().headSha() );
    }

    @Test
    void aMergedPullRequestCarriesTheMergeShaAndTheMerger() {
        final CodeEvent e = only( "pull_request", "pr-merged" );
        assertEquals( CodeEvent.Kind.REVIEW_MERGED, e.kind() );
        assertEquals( M, e.mergedSha() );
        assertEquals( new CodeEvent.Person( "lena-gh", null, null ), e.actor() );
        assertEquals( Instant.parse( "2026-01-07T08:00:00Z" ), e.at() );
    }

    @Test
    void aClosedUnmergedPullRequestIsClosedWithNoMergeSha() {
        final CodeEvent e = only( "pull_request", "pr-closed" );
        assertEquals( CodeEvent.Kind.REVIEW_CLOSED, e.kind() );
        assertNull( e.mergedSha() );
        assertNull( e.actor() );
    }

    @Test
    void anApprovalCarriesTheReviewedCommitAndTheReviewer() {
        final CodeEvent e = only( "pull_request_review", "review-approved" );
        assertEquals( CodeEvent.Kind.REVIEW_APPROVED, e.kind() );
        assertEquals( H2, e.review().headSha() );
        assertEquals( "12", e.review().number() );
        assertEquals( "PLAT-1-fix-login", e.branch() );
        assertEquals( new CodeEvent.Person( "lena-gh", null, null ), e.actor() );
        assertEquals( Instant.parse( "2026-01-05T13:00:00Z" ), e.at() );
    }

    @Test
    void aCommentedReviewAChangesRequestedAndADismissalGiveNothing() {
        assertTrue( GithubHookAdapter.translate( "pull_request_review", fixture( "review-commented" ) ).isEmpty() );
        final JsonObject changes = fixture( "review-approved" );
        changes.getAsJsonObject( "review" ).addProperty( "state", "changes_requested" );
        assertTrue( GithubHookAdapter.translate( "pull_request_review", changes ).isEmpty() );
        final JsonObject dismissed = fixture( "review-approved" );
        dismissed.addProperty( "action", "dismissed" );
        assertTrue( GithubHookAdapter.translate( "pull_request_review", dismissed ).isEmpty() );
    }

    @Test
    void aPingAndAnyOtherHeaderGiveNothing() {
        assertTrue( GithubHookAdapter.translate( "ping", fixture( "ping" ) ).isEmpty() );
        assertTrue( GithubHookAdapter.translate( "issues", fixture( "pr-opened" ) ).isEmpty() );
        assertTrue( GithubHookAdapter.translate( null, fixture( "pr-opened" ) ).isEmpty() );
        assertTrue( GithubHookAdapter.translate( "push", null ).isEmpty() );
    }

    @Test
    void aPullRequestActionWeDoNotTrackGivesNothing() {
        final JsonObject labeled = fixture( "pr-opened" );
        labeled.addProperty( "action", "labeled" );
        assertTrue( GithubHookAdapter.translate( "pull_request", labeled ).isEmpty() );
    }

    @Test
    void aBodyMissingWhatItsKindNeedsGivesNothing() {
        for ( final String missing : List.of( "ref", "after", "repository" ) ) {
            final JsonObject push = fixture( "push-branch" );
            push.remove( missing );
            assertTrue( GithubHookAdapter.translate( "push", push ).isEmpty(), missing );
        }
        for ( final String missing : List.of( "number", "head" ) ) {
            final JsonObject pr = fixture( "pr-opened" );
            pr.getAsJsonObject( "pull_request" ).remove( missing );
            assertTrue( GithubHookAdapter.translate( "pull_request", pr ).isEmpty(), missing );
        }
        final JsonObject noMerged = fixture( "pr-merged" );
        noMerged.getAsJsonObject( "pull_request" ).remove( "merged" );
        assertTrue( GithubHookAdapter.translate( "pull_request", noMerged ).isEmpty(), "closed with no merged flag" );
        final JsonObject noCommit = fixture( "review-approved" );
        noCommit.getAsJsonObject( "review" ).remove( "commit_id" );
        assertTrue( GithubHookAdapter.translate( "pull_request_review", noCommit ).isEmpty(), "approval of no commit" );
    }

    @Test
    void wrongTypesAndHostileShapesNeverThrow() {
        final JsonObject wrong = JsonParser.parseString( "{\"ref\":5,\"after\":[1],\"repository\":\"x\",\"commits\":\"nope\","
            + "\"deleted\":\"true\",\"pull_request\":[],\"review\":3,\"action\":{}}" ).getAsJsonObject();
        for ( final String header : List.of( "push", "pull_request", "pull_request_review", "ping", "x" ) ) {
            assertTrue( GithubHookAdapter.translate( header, wrong ).isEmpty(), header );
        }
        final JsonObject typed = fixture( "push-branch" );
        typed.add( "commits", JsonParser.parseString( "[1,null,\"x\",[],{\"id\":7},{\"id\":\"" + H1 + "\",\"author\":5,"
            + "\"timestamp\":99,\"distinct\":\"yes\"}]" ) );
        final List< CodeEvent > events = GithubHookAdapter.translate( "push", typed );
        assertEquals( 1, events.size() );
        assertEquals( 1, events.get( 0 ).commits().size(), "only the commit with a text id survives" );
        assertNull( events.get( 0 ).commits().get( 0 ).at() );
        assertEquals( false, events.get( 0 ).commits().get( 0 ).distinct(), "a doubtful flag is not distinct" );
    }

    @Test
    void aPushIsTimedByThePushNotByItsHeadCommit() {
        final JsonObject push = fixture( "push-branch" );
        push.getAsJsonObject( "repository" ).addProperty( "pushed_at", 1767619800L );
        assertEquals( Instant.ofEpochSecond( 1767619800L ), GithubHookAdapter.translate( "push", push ).get( 0 ).at() );
        push.getAsJsonObject( "repository" ).addProperty( "pushed_at", "2026-01-05T10:00:00Z" );
        assertEquals( Instant.parse( "2026-01-05T10:00:00Z" ), GithubHookAdapter.translate( "push", push ).get( 0 ).at() );
        for ( final long absurd : new long[] { -1L, 253_402_300_800L } ) {
            push.getAsJsonObject( "repository" ).addProperty( "pushed_at", absurd );
            assertEquals( Instant.parse( "2026-01-05T09:30:00Z" ), GithubHookAdapter.translate( "push", push ).get( 0 ).at(),
                "out of range: the head commit's time stands in" );
        }
    }

    @Test
    void aUsernameComesOnlyFromAnAccountFieldNeverFromACommitsNameOrAddress() {
        final JsonObject push = fixture( "push-branch" );
        final JsonObject author = new JsonObject();
        author.addProperty( "name", "lena" );
        author.addProperty( "email", "lena@example.com" );
        for ( final var c : push.getAsJsonArray( "commits" ) ) {
            c.getAsJsonObject().add( "author", author );
        }
        final CodeEvent e = GithubHookAdapter.translate( "push", push ).get( 0 );
        for ( final CodeEvent.Commit c : e.commits() ) {
            assertNull( c.author().username(), "a commit's free-text name is never a username" );
            assertEquals( "lena", c.author().name() );
        }
        assertEquals( "ana-gh", only( "pull_request", "pr-opened" ).review().author().username(), "the account's login" );
    }

    @Test
    void absurdTimesAreDroppedToNullAndKeepTheEvent() {
        for ( final String bad : List.of( "1969-12-31T23:59:59Z", "+99999-01-01T00:00:00Z", "yesterday", "2026-13-45T00:00:00Z" ) ) {
            final JsonObject pr = fixture( "pr-opened" );
            pr.getAsJsonObject( "pull_request" ).addProperty( "updated_at", bad );
            final List< CodeEvent > events = GithubHookAdapter.translate( "pull_request", pr );
            assertEquals( 1, events.size(), bad );
            assertNull( events.get( 0 ).at(), bad );
        }
    }

    @Test
    void everyStringIsBoundedAndTheCommitListIsCapped() {
        final String huge = "x".repeat( 200_000 );
        final JsonObject push = fixture( "push-branch" );
        push.addProperty( "after", huge );
        push.getAsJsonObject( "repository" ).addProperty( "full_name", huge );
        final com.google.gson.JsonArray many = new com.google.gson.JsonArray();
        for ( int i = 0; i < 500; i++ ) {
            final JsonObject c = new JsonObject();
            c.addProperty( "id", "a" + i );
            c.addProperty( "message", huge );
            many.add( c );
        }
        push.add( "commits", many );
        final CodeEvent e = GithubHookAdapter.translate( "push", push ).get( 0 );
        assertEquals( HookJson.SHA, e.after().length() );
        assertEquals( HookJson.NAME, e.repo().length() );
        assertEquals( HookJson.MAX_COMMITS, e.commits().size() );
        assertEquals( HookJson.MESSAGE, e.commits().get( 0 ).message().length() );
    }

    @Test
    void aPullRequestNumberMustBeSmallAndPositive() {
        for ( final String bad : List.of( "0", "-4", "12345678901", "\"abc\"", "1.5", "true" ) ) {
            final JsonObject pr = fixture( "pr-opened" );
            pr.getAsJsonObject( "pull_request" ).add( "number", JsonParser.parseString( bad ) );
            assertTrue( GithubHookAdapter.translate( "pull_request", pr ).isEmpty(), bad );
        }
    }

    @Test
    void eachEventIsTimedByItsOwnField() {
        final JsonObject opened = fixture( "pr-opened" );
        opened.getAsJsonObject( "pull_request" ).addProperty( "created_at", "2026-01-05T10:59:00Z" );
        assertEquals( Instant.parse( "2026-01-05T10:59:00Z" ), GithubHookAdapter.translate( "pull_request", opened ).get( 0 ).at() );
        final JsonObject closed = fixture( "pr-closed" );
        closed.getAsJsonObject( "pull_request" ).addProperty( "closed_at", "2026-01-07T08:59:00Z" );
        assertEquals( Instant.parse( "2026-01-07T08:59:00Z" ), GithubHookAdapter.translate( "pull_request", closed ).get( 0 ).at(),
            "a close is timed by closed_at, not by a later update" );
        final JsonObject merged = fixture( "pr-merged" );
        merged.getAsJsonObject( "pull_request" ).addProperty( "closed_at", "2026-01-07T07:58:00Z" );
        assertEquals( Instant.parse( "2026-01-07T07:58:00Z" ), GithubHookAdapter.translate( "pull_request", merged ).get( 0 ).at() );
        merged.getAsJsonObject( "pull_request" ).addProperty( "merged_at", "2026-01-07T07:57:00Z" );
        assertEquals( Instant.parse( "2026-01-07T07:57:00Z" ), GithubHookAdapter.translate( "pull_request", merged ).get( 0 ).at(),
            "a merge is timed by merged_at" );
        assertEquals( Instant.parse( "2026-01-07T09:00:00Z" ), only( "pull_request", "pr-closed" ).at(),
            "updated_at when the event's own field is missing" );
    }

    @Test
    void aCapturedDeliveryTranslatesToTheSameTimedEventsEveryTime() {
        final String[][] captured = { { "push", "push-branch" }, { "push", "push-default-direct" },
            { "push", "push-default-merge-commit" }, { "push", "push-default-rebase" }, { "push", "push-default-squash" },
            { "pull_request", "pr-opened" }, { "pull_request", "pr-opened-draft" }, { "pull_request", "pr-reopened" },
            { "pull_request", "pr-ready" }, { "pull_request", "pr-review-requested" }, { "pull_request", "pr-synchronize" },
            { "pull_request", "pr-closed" }, { "pull_request", "pr-merged" }, { "pull_request_review", "review-approved" } };
        for ( final String[] c : captured ) {
            final List< CodeEvent > once = GithubHookAdapter.translate( c[ 0 ], fixture( c[ 1 ] ) );
            assertEquals( once, GithubHookAdapter.translate( c[ 0 ], fixture( c[ 1 ] ) ), c[ 1 ] + ": a pure function of the body" );
            assertTrue( !once.isEmpty() && once.stream().allMatch( e -> e.at() != null ), c[ 1 ] + ": every event has its time" );
        }
    }

    @Test
    void aTimeBeyondTheYearRangeOfADateIsNullNotAnException() {
        for ( final String extreme : List.of( "+1000000000-12-31T23:59:59Z", "-1000000000-01-01T00:00:00Z" ) ) {
            final JsonObject o = new JsonObject();
            o.addProperty( "t", extreme );
            assertNull( HookJson.time( o, "t" ), extreme );
            final JsonObject pr = fixture( "pr-opened" );
            pr.getAsJsonObject( "pull_request" ).addProperty( "updated_at", extreme );
            assertNull( GithubHookAdapter.translate( "pull_request", pr ).get( 0 ).at(), "the event is kept, untimed: " + extreme );
        }
    }
}
