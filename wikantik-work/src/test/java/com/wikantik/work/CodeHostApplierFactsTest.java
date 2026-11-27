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
package com.wikantik.work;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;

import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * What {@link CodeHostApplier} hands the change store for each kind of event, over a recording store: which events
 * it ignores outright, which facts it trusts (shas, branches, urls, numbers) and which it replaces with a default.
 * The store's own transaction rules are pinned by the PostgreSQL code-host tests.
 */
class CodeHostApplierFactsTest {

    private static final Instant NOW = Instant.parse( "2026-03-02T00:00:00Z" );
    private static final long PRODUCT = 7;

    private WorkStore store;
    private WorkCodeHostStore hosts;
    private CodeHostApplier applier;
    private final HookDelivery delivery = new HookDelivery( PRODUCT, "wk", "github", "https://github.com/acme/wk",
        "main" );

    private static Item item( final String key, final WorkLevel level ) {
        return new Item( 1, 1, key, level, null, null, WorkKind.SOFTWARE, "t", "", WorkStatus.READY, 2, 1.0, null,
            null, "alice", null, null, null, 1, Instant.EPOCH, Instant.EPOCH, null, null, null, null, null, null,
            null, null, null, null );
    }

    @BeforeEach
    void setUp() throws SQLException {
        store = mock( WorkStore.class );
        final WorkNotifyStores holder = mock( WorkNotifyStores.class );
        hosts = mock( WorkCodeHostStore.class );
        when( store.notifyStores() ).thenReturn( holder );
        when( holder.codeHost() ).thenReturn( hosts );
        final Item task = item( "PLAT-1", WorkLevel.TASK );
        when( store.item( "PLAT-1" ) ).thenReturn( Optional.of( task ) );
        when( store.policyChain( 1 ) ).thenReturn( new PolicyChain( task, List.of(), PolicyItems.project( Autonomy.DELEGATED ),
            new Product( PRODUCT, "wk", "Wk", null, "main", null, Autonomy.AUTONOMOUS, DeployGate.HUMAN, null ) ) );
        final WorkCodeHostStore.HostWrite applied = new WorkCodeHostStore.HostWrite( CodeHostOutcome.APPLIED, null );
        when( hosts.direct( anyLong(), any(), any(), any(), any() ) ).thenReturn( applied );
        when( hosts.merge( anyLong(), any(), any(), any(), any(), any(), any() ) ).thenReturn( applied );
        when( hosts.branchPush( anyLong(), any(), any(), any() ) ).thenReturn( applied );
        when( hosts.review( anyLong(), any(), any(), any(), any(), any() ) ).thenReturn( applied );
        applier = new CodeHostApplier( store, new WorkDirectory() {
            @Override public Set< String > groupsOf( final String login ) { return Set.of(); }
            @Override public boolean exists( final String login ) { return false; }
            @Override public String displayName( final String login ) { return login; }
        }, Clock.fixed( NOW, ZoneOffset.UTC ), Actor.system( "github" ) );
    }

    private static CodeEvent.Commit commit( final String sha, final String message, final Instant at, final boolean distinct ) {
        return CodeEvents.commit( sha, message, at, distinct, CodeEvents.ANA );
    }

    private void assertNothingWritten() throws SQLException {
        verify( hosts, never() ).direct( anyLong(), any(), any(), any(), any() );
        verify( hosts, never() ).merge( anyLong(), any(), any(), any(), any(), any(), any() );
        verify( hosts, never() ).branchPush( anyLong(), any(), any(), any() );
        verify( hosts, never() ).review( anyLong(), any(), any(), any(), any(), any() );
    }

    // ---- repository and default branch

    @ParameterizedTest
    @CsvSource( nullValues = "NULL", value = {
        "https://github.com/acme/wk,        acme/wk,   true",
        "https://github.com/acme/wk/,       acme/wk,   true",
        "https://github.com/acme/wk///,     acme/wk,   true",
        "https://github.com/acme/wk.git,    acme/wk,   true",
        "https://GitHub.com/Acme/WK,        acme/wk,   true",
        "https://github.com/acme/wk,        ACME/WK,   true",
        "'  https://github.com/acme/wk  ',   acme/wk,   true",
        "https://github.com/acme/wk2,       acme/wk,   false",
        "https://github.com/otheracme/wk,   acme/wk,   false",
        "https://github.com/acme/other,     acme/wk,   false",
        "https://github.com/acme/wk.git/x,  acme/wk,   false",
        "NULL,                              acme/wk,   true",
        "'  ',                              acme/wk,   true",
        "https://github.com/acme/other,     NULL,      true",
        "https://github.com/acme/other,     '  ',      true",
    } )
    void aDeliveryMatchesItsProductsRepositoryByTheTrailingOwnerAndName( final String url, final String repo,
                                                                         final boolean expected ) {
        final HookDelivery d = new HookDelivery( 1, "wk", "github", url, "main" );
        final CodeEvent e = new CodeEvent( CodeEvent.Kind.PUSH, repo, "main", "main", null, null, null, NOW, List.of(),
            null, null, null );
        assertEquals( expected, CodeHostTargets.repoMatches( d, e ), url + " vs " + repo );
    }

    @Test
    void aDeliveryForAnotherRepositoryIsIgnoredAndWritesNothing() throws SQLException {
        final HookDelivery other = new HookDelivery( PRODUCT, "wk", "github", "https://github.com/acme/other", "main" );
        final CodeEvent e = CodeEvents.opened( "work/PLAT-1-x", "5", CodeEvents.A, CodeEvents.at( "10:00" ) );
        assertEquals( new CodeHostApplier.Counts( 0, 0, 1 ), applier.apply( other, e ) );
        assertEquals( new CodeHostApplier.Counts( 0, 0, 1 ), applier.apply( other, e ), "again, still one ignored" );
        verifyNoInteractions( hosts );
        verify( store, never() ).item( any() );
    }

    @Test
    void theDefaultBranchIsTheProductsUnlessItNamesNone() {
        final CodeEvent e = new CodeEvent( CodeEvent.Kind.PUSH, "acme/wk", "trunk", "trunk", null, null, null, NOW,
            List.of(), null, null, null );
        assertEquals( "main", CodeHostTargets.defaultBranch( delivery, e ) );
        assertEquals( "trunk", CodeHostTargets.defaultBranch( new HookDelivery( 1, "wk", "github", null, null ), e ) );
        assertEquals( "trunk", CodeHostTargets.defaultBranch( new HookDelivery( 1, "wk", "github", null, " " ), e ) );
        assertTrue( CodeHostTargets.isDefaultBranchPush( new HookDelivery( 1, "wk", "github", null, null ), e ) );
        assertFalse( CodeHostTargets.isDefaultBranchPush( delivery, e ), "the product says main" );
    }

    // ---- which keys name items

    @Test
    void aReviewNamesItsItemsByBranchAndTitleAndAPushByBranchOnly() {
        final CodeEvent review = CodeEvents.review( CodeEvent.Kind.REVIEW_OPENED, "work/PLAT-1-x", "5", CodeEvents.A,
            "Fixes PLAT-2", CodeEvents.at( "10:00" ), false, CodeEvents.ANA, CodeEvents.ANA );
        assertEquals( Set.of( "PLAT-1", "PLAT-2" ), CodeHostTargets.keys( delivery, review ) );
        final CodeEvent noReview = new CodeEvent( CodeEvent.Kind.PUSH, "acme/wk", "main", "work/PLAT-3-y", null, null,
            CodeEvents.A, NOW, List.of( commit( CodeEvents.A, "PLAT-4", NOW, true ) ), null, null, null );
        assertEquals( Set.of( "PLAT-3" ), CodeHostTargets.keys( delivery, noReview ),
            "a branch push reads the branch, not its commit messages" );
    }

    @Test
    void aPushToTheDefaultBranchNamesItemsByItsDistinctCommitsOnly() {
        final CodeEvent e = CodeEvents.push( "main", CodeEvents.B, NOW,
            commit( CodeEvents.A, "PLAT-1 first", NOW, true ), commit( CodeEvents.B, "PLAT-2 again", NOW, false ) );
        assertEquals( Set.of( "PLAT-1" ), CodeHostTargets.keys( delivery, e ) );
    }

    @Test
    void atMostTenKeysAreRead() {
        final StringBuilder msg = new StringBuilder();
        for ( int i = 1; i <= 12; i++ ) {
            msg.append( "PLAT-" ).append( i ).append( ' ' );
        }
        final CodeEvent e = CodeEvents.push( "main", CodeEvents.B, NOW, commit( CodeEvents.A, msg.toString(), NOW, true ) );
        final Set< String > keys = CodeHostTargets.keys( delivery, e );
        assertEquals( 10, keys.size() );
        assertTrue( keys.contains( "PLAT-10" ) );
        assertFalse( keys.contains( "PLAT-11" ) );
    }

    @Test
    void anEventNamingNothingUsableIsOneIgnoredAndAnEpicOrUnknownKeyIsCounted() throws SQLException {
        assertEquals( new CodeHostApplier.Counts( 0, 0, 1 ), applier.apply( delivery,
            CodeEvents.opened( "feature/no-key", "5", CodeEvents.A, CodeEvents.at( "10:00" ) ) ) );
        when( store.item( "PLAT-9" ) ).thenReturn( Optional.of( item( "PLAT-9", WorkLevel.EPIC ) ) );
        assertEquals( new CodeHostApplier.Counts( 0, 0, 2 ), applier.apply( delivery,
            CodeEvents.opened( "work/PLAT-9-x/PLAT-404", "5", CodeEvents.A, CodeEvents.at( "10:00" ) ) ),
            "an epic and an unknown key are each one ignored key" );
        assertNothingWritten();
    }

    @Test
    void anItemOfAnotherProductOrAnArchivedProjectIsNotATarget() throws SQLException {
        final Item task = item( "PLAT-1", WorkLevel.TASK );
        when( store.policyChain( 1 ) ).thenReturn( new PolicyChain( task, List.of(), PolicyItems.project( Autonomy.DELEGATED ),
            new Product( PRODUCT + 1, "other", "O", null, "main", null, Autonomy.AUTONOMOUS, DeployGate.HUMAN, null ) ) );
        final CodeEvent e = CodeEvents.opened( "work/PLAT-1-x", "5", CodeEvents.A, CodeEvents.at( "10:00" ) );
        assertEquals( new CodeHostApplier.Counts( 0, 0, 1 ), applier.apply( delivery, e ) );
        when( store.policyChain( 1 ) ).thenReturn( new PolicyChain( task, List.of(), PolicyItems.project( Autonomy.DELEGATED ), null ) );
        assertEquals( new CodeHostApplier.Counts( 0, 0, 1 ), applier.apply( delivery, e ), "no product at all" );
        when( store.policyChain( 1 ) ).thenReturn( new PolicyChain( task, List.of(),
            new Project( 1, "PLAT", "P", "", Autonomy.DELEGATED, Instant.EPOCH, Instant.EPOCH, null ),
            new Product( PRODUCT, "wk", "Wk", null, "main", null, Autonomy.AUTONOMOUS, DeployGate.HUMAN, null ) ) );
        assertEquals( new CodeHostApplier.Counts( 0, 0, 1 ), applier.apply( delivery, e ), "archived project" );
        assertNothingWritten();
    }

    @Test
    void anEventWithNoUsableTimeIsIgnoredForEveryItemItNames() throws SQLException {
        final CodeEvent e = CodeEvents.opened( "work/PLAT-1-x", "5", CodeEvents.A, Instant.parse( "1960-01-01T00:00:00Z" ) );
        assertEquals( new CodeHostApplier.Counts( 0, 0, 1 ), applier.apply( delivery, e ) );
        assertNothingWritten();
    }

    // ---- pushes to the default branch

    @Test
    void aDefaultBranchPushWithoutAUsableAfterShaIsIgnored() throws SQLException {
        for ( final String after : new String[] { null, "", "0000000", "00000000000000000000", "zzzzzzz", "abc" } ) {
            assertEquals( new CodeHostApplier.Counts( 0, 0, 1 ), applier.apply( delivery,
                CodeEvents.push( "main", after, CodeEvents.at( "10:00" ), commit( CodeEvents.A, "PLAT-1", CodeEvents.at( "10:00" ), true ) ) ),
                String.valueOf( after ) );
        }
        assertNothingWritten();
    }

    @Test
    void aDefaultBranchPushRecordsTheCommitsThatNameTheItem() throws SQLException {
        final CodeEvent e = CodeEvents.push( "main", "FFFFFFF9", CodeEvents.at( "10:30" ),
            commit( CodeEvents.A, "PLAT-1 start (#12)", CodeEvents.at( "09:00" ), true ),
            commit( "bad sha", "PLAT-1 junk", CodeEvents.at( "08:00" ), true ),
            commit( CodeEvents.B, "unrelated work", CodeEvents.at( "07:00" ), true ),
            commit( CodeEvents.C, "PLAT-1 finish", null, true ),
            commit( "dddddd4", "PLAT-1 not new", CodeEvents.at( "06:00" ), false ) );
        assertEquals( new CodeHostApplier.Counts( 1, 0, 0 ), applier.apply( delivery, e ) );
        final ArgumentCaptor< WorkCodeHostStore.DirectFacts > facts = ArgumentCaptor.forClass( WorkCodeHostStore.DirectFacts.class );
        verify( hosts ).direct( eq( PRODUCT ), any(), facts.capture(), any(), any() );
        final WorkCodeHostStore.DirectFacts f = facts.getValue();
        assertEquals( "main", f.branch() );
        assertEquals( "fffffff9", f.after(), "a sha is trimmed and lower-cased" );
        assertEquals( CodeEvents.C, f.headSha(), "the last commit that names the item, not the push head" );
        assertEquals( CodeEvents.at( "08:00" ), f.firstCommitAt(), "the earliest time of a commit naming it, ignoring a missing one" );
        assertEquals( Set.of( CodeEvents.A, CodeEvents.B, CodeEvents.C, "dddddd4" ), f.pushShas(),
            "every commit of the push with a valid sha, distinct or not" );
        assertEquals( Set.of( "12" ), f.reviewNumbers() );
        assertEquals( CodeEvents.at( "10:30" ), f.at() );
    }

    @Test
    void theHeadIsThePushHeadWhenNoCommitNamesTheItemOrItsShaIsUnusable() throws SQLException {
        applier.apply( delivery, CodeEvents.push( "main", CodeEvents.M, CodeEvents.at( "10:30" ),
            commit( CodeEvents.A, "PLAT-1", CodeEvents.at( "09:00" ), true ),
            commit( "not a sha", "PLAT-1 last", CodeEvents.at( "09:10" ), true ) ) );
        final ArgumentCaptor< WorkCodeHostStore.DirectFacts > facts = ArgumentCaptor.forClass( WorkCodeHostStore.DirectFacts.class );
        verify( hosts ).direct( eq( PRODUCT ), any(), facts.capture(), any(), any() );
        assertEquals( CodeEvents.M, facts.getValue().headSha(), "the last naming commit has no usable sha" );
    }

    @Test
    void aDirectPushIsFlaggedOnlyWhereThePolicyForbidsIt() throws SQLException {
        applier.apply( delivery, CodeEvents.push( "main", CodeEvents.M, CodeEvents.at( "10:30" ),
            commit( CodeEvents.A, "PLAT-1", CodeEvents.at( "09:00" ), true ) ) );
        verify( hosts ).direct( eq( PRODUCT ), any(), any(), eq( "direct" ), any() );
    }

    // ---- pushes to other branches

    @Test
    void aBranchPushNeedsAUsableHeadAndBranch() throws SQLException {
        final Instant t = CodeEvents.at( "10:00" );
        for ( final String after : new String[] { null, "0000000", "xyz" } ) {
            assertEquals( new CodeHostApplier.Counts( 0, 0, 1 ),
                applier.apply( delivery, CodeEvents.push( "work/PLAT-1-x", after, t ) ), String.valueOf( after ) );
        }
        final CodeEvent spaced = new CodeEvent( CodeEvent.Kind.PUSH, "acme/wk", "main", "PLAT-1 with space", null,
            "0000000", CodeEvents.A, t, List.of(), null, null, null );
        assertEquals( new CodeHostApplier.Counts( 0, 0, 1 ), applier.apply( delivery, spaced ), "a branch with spaces" );
        assertNothingWritten();
    }

    @Test
    void aBranchPushRecordsHeadBaseAndAFirstCommitNoLaterThanThePush() throws SQLException {
        final Instant push = CodeEvents.at( "10:00" );
        final CodeEvent e = CodeEvents.push( "work/PLAT-1-x", "AAAAAAA1", push,
            commit( CodeEvents.B, "one", CodeEvents.at( "09:30" ), true ),
            commit( CodeEvents.C, "two", CodeEvents.at( "09:10" ), true ),
            commit( "dddddd4", "three", CodeEvents.at( "08:00" ), false ),
            commit( "eeeeeee5", "four", null, true ) );
        applier.apply( delivery, e );
        final ArgumentCaptor< WorkCodeHostStore.BranchFacts > facts = ArgumentCaptor.forClass( WorkCodeHostStore.BranchFacts.class );
        verify( hosts ).branchPush( eq( PRODUCT ), any(), facts.capture(), any() );
        final WorkCodeHostStore.BranchFacts f = facts.getValue();
        assertEquals( "work/PLAT-1-x", f.branch() );
        assertEquals( "main", f.baseBranch() );
        assertEquals( CodeEvents.A, f.headSha() );
        assertEquals( CodeEvents.at( "09:10" ), f.firstCommitAt(), "the earliest distinct commit time" );
        assertEquals( push, f.at() );
    }

    @Test
    void aBranchPushWithNoCommitTimesHasNoFirstCommitAndTheBaseDefaultsToMain() throws SQLException {
        final HookDelivery noDefault = new HookDelivery( PRODUCT, "wk", "github", null, null );
        final CodeEvent e = new CodeEvent( CodeEvent.Kind.PUSH, "acme/wk", null, "work/PLAT-1-x", null, null,
            CodeEvents.A, CodeEvents.at( "10:00" ), List.of( commit( CodeEvents.B, "x", null, true ) ), null, null, null );
        applier.apply( noDefault, e );
        final ArgumentCaptor< WorkCodeHostStore.BranchFacts > facts = ArgumentCaptor.forClass( WorkCodeHostStore.BranchFacts.class );
        verify( hosts ).branchPush( eq( PRODUCT ), any(), facts.capture(), any() );
        assertNull( facts.getValue().firstCommitAt() );
        assertEquals( "main", facts.getValue().baseBranch() );
    }

    // ---- pull requests

    private WorkCodeHostStore.ReviewFacts reviewFactsOf( final CodeEvent e ) throws SQLException {
        applier.apply( delivery, e );
        final ArgumentCaptor< WorkCodeHostStore.ReviewFacts > facts = ArgumentCaptor.forClass( WorkCodeHostStore.ReviewFacts.class );
        verify( hosts ).review( eq( PRODUCT ), any(), facts.capture(), any(), any(), any() );
        return facts.getValue();
    }

    @Test
    void aPullRequestEventCarriesItsCleanedFacts() throws SQLException {
        final CodeEvent e = CodeEvents.review( CodeEvent.Kind.REVIEW_UPDATED, "work/PLAT-1-x", "42", " ABCDEF1 ",
            "t", CodeEvents.at( "10:00" ), false, CodeEvents.ANA, CodeEvents.ANA );
        final WorkCodeHostStore.ReviewFacts f = reviewFactsOf( e );
        assertEquals( "42", f.number() );
        assertEquals( "https://github.com/acme/wk/pull/42", f.url() );
        assertEquals( "work/PLAT-1-x", f.branch() );
        assertEquals( "main", f.baseBranch() );
        assertEquals( "abcdef1", f.headSha() );
        assertEquals( CodeEvents.at( "10:00" ), f.at() );
        assertEquals( "anagh", f.author().external() );
    }

    @Test
    void aPullRequestBaseBranchOfTheEventIsKeptWhenWellFormedAndOtherwiseTheDefault() throws SQLException {
        final CodeEvent.Review r = new CodeEvent.Review( "5", "ftp://x/y", "t", "bad", false, null );
        final CodeEvent good = new CodeEvent( CodeEvent.Kind.REVIEW_UPDATED, "acme/wk", "main", "work/PLAT-1-x",
            "release/1", null, null, CodeEvents.at( "10:00" ), List.of(), r, null, null );
        final WorkCodeHostStore.ReviewFacts f = reviewFactsOf( good );
        assertEquals( "release/1", f.baseBranch() );
        assertNull( f.url(), "only an http(s) address is kept" );
        assertNull( f.headSha(), "an unusable head sha is dropped" );
        assertEquals( CodeHostWho.UNKNOWN, f.author() );
    }

    @Test
    void aMalformedBaseBranchFallsBackToTheProductsDefault() throws SQLException {
        final CodeEvent.Review r = new CodeEvent.Review( "5", null, "t", CodeEvents.A, false, null );
        for ( final String base : new String[] { "has space", null, "" } ) {
            final CodeEvent e = new CodeEvent( CodeEvent.Kind.REVIEW_UPDATED, "acme/wk", "main", "work/PLAT-1-x", base,
                null, null, CodeEvents.at( "10:00" ), List.of(), r, null, null );
            applier.apply( delivery, e );
        }
        final ArgumentCaptor< WorkCodeHostStore.ReviewFacts > facts = ArgumentCaptor.forClass( WorkCodeHostStore.ReviewFacts.class );
        verify( hosts, org.mockito.Mockito.times( 3 ) ).review( eq( PRODUCT ), any(), facts.capture(), any(), any(), any() );
        assertEquals( List.of( "main", "main", "main" ), facts.getAllValues().stream().map( WorkCodeHostStore.ReviewFacts::baseBranch ).toList() );
    }

    @Test
    void aPullRequestWithoutAUsableNumberOrBranchIsIgnored() throws SQLException {
        final Instant t = CodeEvents.at( "10:00" );
        for ( final String number : new String[] { null, "", "abc", "1234567890", "-1" } ) {
            assertEquals( new CodeHostApplier.Counts( 0, 0, 1 ),
                applier.apply( delivery, CodeEvents.opened( "work/PLAT-1-x", number, CodeEvents.A, t ) ), String.valueOf( number ) );
        }
        assertEquals( new CodeHostApplier.Counts( 1, 0, 0 ),
            applier.apply( delivery, CodeEvents.opened( "work/PLAT-1-x", "123456789", CodeEvents.A, t ) ), "nine digits is the limit" );
        final CodeEvent noReview = new CodeEvent( CodeEvent.Kind.REVIEW_OPENED, "acme/wk", "main", "work/PLAT-1-x",
            "main", null, null, t, List.of(), null, null, null );
        assertEquals( new CodeHostApplier.Counts( 0, 0, 1 ), applier.apply( delivery, noReview ) );
        final CodeEvent spaced = new CodeEvent( CodeEvent.Kind.REVIEW_OPENED, "acme/wk", "main", "PLAT-1 x", "main",
            null, null, t, List.of(), new CodeEvent.Review( "5", null, "t", CodeEvents.A, false, null ), null, null );
        assertEquals( new CodeHostApplier.Counts( 0, 0, 1 ), applier.apply( delivery, spaced ) );
    }

    @Test
    void aMergeIsRecordedWithTheHostsMergeCommitElseTheMergedHead() throws SQLException {
        final Instant t = CodeEvents.at( "10:00" );
        applier.apply( delivery, CodeEvents.merged( "work/PLAT-1-x", "5", CodeEvents.A, "FFFFFFF9", t, CodeEvents.OCTO ) );
        applier.apply( delivery, CodeEvents.merged( "work/PLAT-1-x", "6", CodeEvents.B, null, t, CodeEvents.OCTO ) );
        applier.apply( delivery, CodeEvents.merged( "work/PLAT-1-x", "7", CodeEvents.C, "nonsense", t, CodeEvents.OCTO ) );
        final ArgumentCaptor< String > merged = ArgumentCaptor.forClass( String.class );
        verify( hosts, org.mockito.Mockito.times( 3 ) ).merge( eq( PRODUCT ), any(), any(), merged.capture(), any(), any(), any() );
        assertEquals( List.of( "fffffff9", CodeEvents.B, CodeEvents.C ), merged.getAllValues() );
    }

    @Test
    void aMergeWithNoCommitToRecordOrNoPullRequestIsIgnored() throws SQLException {
        final Instant t = CodeEvents.at( "10:00" );
        assertEquals( new CodeHostApplier.Counts( 0, 0, 1 ),
            applier.apply( delivery, CodeEvents.merged( "work/PLAT-1-x", "5", null, null, t, CodeEvents.OCTO ) ), "no head, no merge sha" );
        assertEquals( new CodeHostApplier.Counts( 0, 0, 1 ),
            applier.apply( delivery, CodeEvents.merged( "work/PLAT-1-x", "x", CodeEvents.A, CodeEvents.M, t, CodeEvents.OCTO ) ), "no number" );
        verify( hosts, never() ).merge( anyLong(), any(), any(), any(), any(), any(), any() );
    }

    @Test
    void anApprovalNamesItsReviewerAndEveryOtherStepDoesNot() throws SQLException {
        final Instant t = CodeEvents.at( "10:00" );
        applier.apply( delivery, CodeEvents.approved( "work/PLAT-1-x", "5", CodeEvents.A, t, CodeEvents.MIA ) );
        final ArgumentCaptor< CodeHostWho > who = ArgumentCaptor.forClass( CodeHostWho.class );
        verify( hosts ).review( eq( PRODUCT ), any(), any(), eq( WorkCodeHostStore.ReviewStep.APPROVED ), who.capture(), any() );
        assertEquals( "miagh", who.getValue().external() );
        applier.apply( delivery, CodeEvents.closed( "work/PLAT-1-x", "5", CodeEvents.A, t ) );
        verify( hosts ).review( eq( PRODUCT ), any(), any(), eq( WorkCodeHostStore.ReviewStep.CLOSED ), eq( CodeHostWho.UNKNOWN ), any() );
    }

    @Test
    void everyReviewKindMapsToItsStepAndADraftOpeningIsADraft() throws SQLException {
        final Instant t = CodeEvents.at( "10:00" );
        applier.apply( delivery, CodeEvents.opened( "work/PLAT-1-x", "5", CodeEvents.A, t ) );
        applier.apply( delivery, CodeEvents.openedDraft( "work/PLAT-1-x", "5", CodeEvents.A, t ) );
        applier.apply( delivery, CodeEvents.ready( "work/PLAT-1-x", "5", CodeEvents.A, t ) );
        applier.apply( delivery, CodeEvents.updated( "work/PLAT-1-x", "5", CodeEvents.A, t ) );
        applier.apply( delivery, CodeEvents.reopened( "work/PLAT-1-x", "5", CodeEvents.A, t ) );
        final ArgumentCaptor< WorkCodeHostStore.ReviewStep > step = ArgumentCaptor.forClass( WorkCodeHostStore.ReviewStep.class );
        verify( hosts, org.mockito.Mockito.times( 5 ) ).review( eq( PRODUCT ), any(), any(), step.capture(), any(), any() );
        assertEquals( List.of( WorkCodeHostStore.ReviewStep.OPENED, WorkCodeHostStore.ReviewStep.OPENED_DRAFT,
            WorkCodeHostStore.ReviewStep.READY, WorkCodeHostStore.ReviewStep.UPDATED, WorkCodeHostStore.ReviewStep.REOPENED ),
            step.getAllValues() );
    }

    // ---- counts

    @Test
    void theOverallOutcomeIsAppliedThenDuplicateThenIgnored() {
        assertEquals( CodeHostOutcome.APPLIED, new CodeHostApplier.Counts( 1, 0, 0 ).overall() );
        assertEquals( CodeHostOutcome.APPLIED, new CodeHostApplier.Counts( 1, 3, 5 ).overall() );
        assertEquals( CodeHostOutcome.DUPLICATE, new CodeHostApplier.Counts( 0, 1, 0 ).overall() );
        assertEquals( CodeHostOutcome.DUPLICATE, new CodeHostApplier.Counts( 0, 2, 4 ).overall() );
        assertEquals( CodeHostOutcome.IGNORED, new CodeHostApplier.Counts( 0, 0, 1 ).overall() );
        assertEquals( CodeHostOutcome.IGNORED, new CodeHostApplier.Counts( 0, 0, 0 ).overall(), "nothing happened" );
    }

    @Test
    void countsAddUpPerOutcome() {
        CodeHostApplier.Counts c = CodeHostApplier.Counts.NONE;
        c = c.plus( CodeHostOutcome.APPLIED ).plus( CodeHostOutcome.DUPLICATE ).plus( CodeHostOutcome.DUPLICATE )
            .plus( CodeHostOutcome.IGNORED ).plusIgnored( 3 );
        assertEquals( new CodeHostApplier.Counts( 1, 2, 4 ), c );
    }
}
