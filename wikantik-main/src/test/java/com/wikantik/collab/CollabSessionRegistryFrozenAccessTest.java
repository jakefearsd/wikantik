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
package com.wikantik.collab;

import com.wikantik.collab.CollabSessionRegistry.Joined;
import com.wikantik.collab.CollabSessionRegistry.Refused;
import com.wikantik.collab.model.JoinRequest;
import com.wikantik.collab.model.PullCursor;
import com.wikantik.collab.model.PullResponse;
import com.wikantik.collab.model.Update;
import com.wikantik.collab.testing.InMemoryCollabStore;
import com.wikantik.collab.testing.MutableClock;
import com.wikantik.collab.testing.ScriptedPageWriter;
import com.wikantik.collab.text.ChangeSet;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Ruling F1 (final review, Important 1): the draft of a deleted page keeps its page's protection. A join of a frozen
 * draft (or of a page-based draft whose page is missing) is allowed only to its existing participants (live, retired
 * clientID, or tombstone), to administrators, and to callers whom the draft's base text's access rules would let view
 * and edit the page; a base whose rules cannot be evaluated refuses. Nobody else learns the unpublished text.
 */
class CollabSessionRegistryFrozenAccessTest {

    private static final String PAGE = "Secret";
    private static final String RESTRICTED = "[{ALLOW edit alice}]\ntop secret\n";
    private static final Instant T0 = Instant.parse( "2026-01-01T00:00:00Z" );

    /**
     * Everyone holds edit and createPages (the default policy on a page that no longer exists). The base text's rules:
     * a text with {@code [{ALLOW edit <names>}]} admits only those names; one with {@code [{ALLOW broken}]} cannot be
     * evaluated (throws); any other text admits everyone.
     */
    private static final class Permissions implements CollabPermissions {
        final Set< Object > admins = new HashSet<>();
        final Set< Object > noCreate = new HashSet<>();

        @Override
        public boolean canEdit( final Object participantSession, final String pageName ) {
            return true;
        }

        @Override
        public boolean canCreate( final Object participantSession, final String pageName ) {
            return !noCreate.contains( participantSession );
        }

        @Override
        public boolean isAdmin( final Object participantSession ) {
            return admins.contains( participantSession );
        }

        /** The test's rule shapes: an ALLOW restricts, {@code [{ALLOW broken}]} cannot be evaluated (throws). */
        @Override
        public boolean restricts( final String pageName, final String text ) {
            if( text.contains( "[{ALLOW broken}]" ) ) {
                throw new IllegalStateException( "test: the access rules cannot be read" );
            }
            return text.contains( "[{ALLOW " );
        }

        @Override
        public boolean mayAccessAsIf( final Object participantSession, final String pageName, final String baseText ) {
            if( baseText.contains( "[{ALLOW broken}]" ) ) {
                throw new IllegalStateException( "test: the access rules cannot be read" );
            }
            final int at = baseText.indexOf( "[{ALLOW edit " );
            if( at < 0 ) {
                return canEdit( participantSession, pageName ) && canCreate( participantSession, pageName );
            }
            final String names = baseText.substring( at + "[{ALLOW edit ".length(), baseText.indexOf( "}]", at ) );
            return List.of( names.split( "," ) ).contains( participantSession );
        }
    }

    private InMemoryCollabStore store;
    private ScriptedPageWriter writer;
    private MutableClock clock;
    private Permissions permissions;
    private CollabSessionRegistry registry;

    @BeforeEach
    void setUp() {
        store = new InMemoryCollabStore();
        writer = new ScriptedPageWriter();
        writer.seed( PAGE, RESTRICTED, Map.of() );
        clock = new MutableClock( T0 );
        permissions = new Permissions();
        registry = new CollabSessionRegistry( store, writer, permissions, key -> null, clock,
            new CollabConfig( true, 20, 65536, 25, 45, 5, 100, 500, 10, 0, 1000 ), 1 << 20 );
    }

    private CollabSessionRegistry.JoinOutcome joinAs( final String author ) {
        return joinAs( author, null );
    }

    private CollabSessionRegistry.JoinOutcome joinAs( final String author, final String priorClientId ) {
        return registry.join( PAGE, new JoinRequest( author, priorClientId, author, author, null, null ) );
    }

    private static void type( final Joined j, final String author, final String text ) {
        final CollabSession.View v = j.session().view();
        assertEquals( 200, j.session().push( j.response().participantId(), author, v.head(),
            List.of( new Update( j.response().clientId(), ChangeSet.replace( v.doc().length(), 0, 0, text ) ) ) )
            .status() );
    }

    /** Alice drafts on the restricted page, which is then deleted (the draft is frozen). */
    private Joined aliceDraftsThenThePageIsDeleted() {
        final Joined alice = assertInstanceOf( Joined.class, joinAs( "alice" ) );
        type( alice, "alice", "unpublished " );
        writer.delete( PAGE );
        registry.freeze( PAGE );
        assertTrue( alice.session().frozen() );
        return alice;
    }

    private static void assertForbidden( final CollabSessionRegistry.JoinOutcome out ) {
        final Refused r = assertInstanceOf( Refused.class, out );
        assertEquals( 403, r.status() );
        assertEquals( CollabSessionRegistry.FORBIDDEN, r.reason() );
    }

    @Test
    void aNonMemberWithCreatePagesCannotJoinOrPullAFrozenRestrictedDraft() {
        final Joined alice = aliceDraftsThenThePageIsDeleted();

        assertForbidden( joinAs( "mallory" ) );

        // and no pull either: mallory holds no participant, and alice's is not hers
        final PullResponse pulled = alice.session().pullOrPark( alice.response().participantId(), "mallory",
            new PullCursor( 0, 0, 0, 0 ), null, r -> { } );
        assertEquals( 403, pulled.status() );
        assertEquals( CollabSession.NOT_YOURS, pulled.reason() );
    }

    @Test
    void aMissingPagesDraftIsGuardedBeforeTheSweepFreezesIt() {
        final Joined alice = assertInstanceOf( Joined.class, joinAs( "alice" ) );
        type( alice, "alice", "unpublished " );
        writer.delete( PAGE );   // no delete event (yet): the draft is still active, its page gone

        assertForbidden( joinAs( "mallory" ) );
    }

    @Test
    void anExistingParticipantKeepsAccess() {
        final Joined alice = aliceDraftsThenThePageIsDeleted();
        // a second tab of a live participant
        assertInstanceOf( Joined.class, joinAs( "alice" ) );
        // a participant who left rejoins by its retired clientID
        assertEquals( 200, alice.session().leave( alice.response().participantId(), "alice" ).status() );
        final Joined again = assertInstanceOf( Joined.class, joinAs( "alice", alice.response().clientId() ) );
        assertEquals( "unpublished " + RESTRICTED, again.response().doc() );
    }

    @Test
    void aParticipantNotAdmittedByTheBaseRulesKeepsAccessThroughItsTombstoneOrRetiredClient() {
        writer.seed( PAGE, "[{ALLOW edit nobody}]\nlocked\n", Map.of() );
        permissions.admins.add( "bob" );   // bob joined as an admin, then lost it
        final Joined bob = assertInstanceOf( Joined.class, joinAs( "bob" ) );
        writer.delete( PAGE );
        registry.freeze( PAGE );
        permissions.admins.clear();
        assertEquals( 200, bob.session().leave( bob.response().participantId(), "bob" ).status() );

        assertInstanceOf( Joined.class, joinAs( "bob", bob.response().clientId() ) );
        assertForbidden( joinAs( "carol" ) );
    }

    /** Re-review (c): an author evicted for losing edit is no participant of the frozen draft any more. */
    @Test
    void anAuthorEvictedForLostEditIsNoLongerAParticipant() {
        writer.seed( PAGE, "[{ALLOW edit nobody}]\nlocked\n", Map.of() );
        permissions.admins.add( "bob" );
        final Joined bob = assertInstanceOf( Joined.class, joinAs( "bob" ) );
        permissions.admins.clear();
        bob.session().evict( bob.response().participantId(), CollabSession.LOST_EDIT );
        writer.delete( PAGE );
        registry.freeze( PAGE );

        assertForbidden( joinAs( "bob", bob.response().clientId() ) );
        clock.advance( Duration.ofMinutes( 5 ) );   // past the tombstone's retention: the retired clientID alone
        assertForbidden( joinAs( "bob", bob.response().clientId() ) );
    }

    // ---- ruling F2: a frozen draft stays guarded through its page's recreation -------------------------------------

    /** Mallory recreates the deleted page with an ordinary save, and its merge unfreezes the draft. */
    private Joined theDeletedPageIsRecreatedByMallory() {
        final Joined alice = aliceDraftsThenThePageIsDeleted();
        writer.externalSave( PAGE, "mallory's page\n", Map.of(), "mallory" );
        alice.session().settle();   // the save's event: the recreated page merges into the draft
        assertTrue( !alice.session().frozen(), "recreated: the draft is editable again" );
        return alice;
    }

    @Test
    void theRecreatorOfADeletedRestrictedPageCannotJoinItsDraft() {
        theDeletedPageIsRecreatedByMallory();

        assertForbidden( joinAs( "mallory" ) );

        assertInstanceOf( Joined.class, joinAs( "alice" ), "an existing participant still gets in" );
        permissions.admins.add( "root" );
        assertInstanceOf( Joined.class, joinAs( "root" ), "and an administrator" );
    }

    @Test
    void theGuardOutlivesAReload() {
        final Joined alice = theDeletedPageIsRecreatedByMallory();
        assertEquals( 200, alice.session().leave( alice.response().participantId(), "alice" ).status() );
        registry.tick();
        clock.advance( Duration.ofMinutes( 10 ) );
        registry.tick();
        assertTrue( registry.liveForPage( PAGE ).isEmpty(), "idled out: reloaded on the next join" );

        assertForbidden( joinAs( "mallory" ) );
        assertInstanceOf( Joined.class, joinAs( "alice" ), "the base rules admit alice" );
    }

    /** Ruling F3: while guarded, only an administrator may publish: a routine publish could declassify the text. */
    @Test
    void aGuardedDraftIsPublishedOnlyByAnAdministrator() {
        final Joined alice = theDeletedPageIsRecreatedByMallory();
        final Joined again = assertInstanceOf( Joined.class, joinAs( "alice", alice.response().clientId() ) );

        final com.wikantik.collab.model.PublishResponse refused = again.session().publish(
            again.response().participantId(), "alice", again.session().view().head(), "" );

        assertEquals( 409, refused.status(), refused::toString );
        assertEquals( CollabSession.GUARDED, refused.reason() );
        assertForbidden( joinAs( "mallory" ) );
        permissions.admins.add( "root" );
        final Joined root = assertInstanceOf( Joined.class, joinAs( "root" ) );
        final com.wikantik.collab.model.PublishResponse published = root.session().publish(
            root.response().participantId(), "root", root.session().view().head(), "" );
        assertEquals( 200, published.status(), published::toString );
        assertInstanceOf( Joined.class, joinAs( "mallory" ), "published by an administrator: the page's own rules apply" );
    }

    /** Ruling F3': a deleted page with no rules is recreated and published by anyone with createPages. */
    @Test
    void aGuardWithoutRulesLetsACreatePagesUserRecreateAndPublish() {
        writer.seed( PAGE, "an open page\n", Map.of() );
        final Joined alice = assertInstanceOf( Joined.class, joinAs( "alice" ) );
        type( alice, "alice", "edit " );
        writer.delete( PAGE );
        registry.freeze( PAGE );
        final Joined bob = assertInstanceOf( Joined.class, joinAs( "bob" ) );

        final com.wikantik.collab.model.PublishResponse published = bob.session().publish(
            bob.response().participantId(), "bob", bob.session().view().head(), "" );

        assertEquals( 200, published.status(), published::toString );
    }

    @Test
    void aFrontmatterRuleInTheGuardMakesPublishingAdminOnly() {
        writer.seed( PAGE, "plain body\n", Map.of( "acl", "[{ALLOW edit alice}]" ) );
        final Joined alice = assertInstanceOf( Joined.class, joinAs( "alice" ) );
        type( alice, "alice", "x" );
        writer.delete( PAGE );
        registry.freeze( PAGE );

        final com.wikantik.collab.model.PublishResponse refused = alice.session().publish(
            alice.response().participantId(), "alice", alice.session().view().head(), "" );

        assertEquals( 409, refused.status(), refused::toString );
        assertEquals( CollabSession.GUARDED, refused.reason() );
    }

    @Test
    void aGuardThatCannotBeEvaluatedMakesPublishingAdminOnly() {
        writer.seed( PAGE, "[{ALLOW broken}]\nx\n", Map.of() );
        permissions.admins.add( "alice" );   // joins as an administrator, then is not one any more
        final Joined alice = assertInstanceOf( Joined.class, joinAs( "alice" ) );
        type( alice, "alice", "y" );
        writer.delete( PAGE );
        registry.freeze( PAGE );
        permissions.admins.clear();

        final com.wikantik.collab.model.PublishResponse refused = alice.session().publish(
            alice.response().participantId(), "alice", alice.session().view().head(), "" );
        assertEquals( 409, refused.status(), refused::toString );
        assertEquals( CollabSession.GUARDED, refused.reason() );

        permissions.admins.add( "alice" );
        assertEquals( 200, alice.session().publish( alice.response().participantId(), "alice",
            alice.session().view().head(), "" ).status(), "an administrator still publishes it" );
    }

    // ---- a missed or failed freeze (security re-review 1) ----------------------------------------------------------

    /** The freeze's store write fails: the draft stays active, its page gone; then mallory recreates the page. */
    private void theFreezeFailsThenMalloryRecreates() {
        final Joined alice = assertInstanceOf( Joined.class, joinAs( "alice" ) );
        type( alice, "alice", "unpublished " );
        writer.delete( PAGE );
        store.failOn( InMemoryCollabStore.Op.UPDATE_SESSION, 1 );
        try {
            registry.freeze( PAGE );
        } catch( final RuntimeException expected ) {
            // the listener logs it; the draft stays active
        }
        assertTrue( !alice.session().frozen(), "the freeze did not land" );
        writer.externalSave( PAGE, "mallory's page\n", Map.of(), "mallory" );
        alice.session().settle();
    }

    @Test
    void aFailedFreezeStillGuardsTheDraftOfAPageRecreatedAtItsBaseVersion() {
        theFreezeFailsThenMalloryRecreates();   // base 1, recreated as version 1 with other content

        assertForbidden( joinAs( "mallory" ) );
        assertInstanceOf( Joined.class, joinAs( "alice" ) );
    }

    @Test
    void aFailedFreezeStillGuardsTheDraftOfAPageRecreatedBelowItsBase() {
        writer.externalSave( PAGE, RESTRICTED + "v2\n", Map.of(), "alice" );   // base 2: the recreate restarts at 1
        theFreezeFailsThenMalloryRecreates();

        assertForbidden( joinAs( "mallory" ) );
    }

        /** Re-review (b): the base text's frontmatter is part of the page's text, and its rules count. */
    @Test
    void aRuleInTheBaseFrontmatterGuardsTheDraft() {
        writer.seed( PAGE, "plain body\n", Map.of( "acl", "[{ALLOW edit alice}]" ) );
        final Joined alice = assertInstanceOf( Joined.class, joinAs( "alice" ) );
        type( alice, "alice", "x" );
        writer.delete( PAGE );
        registry.freeze( PAGE );

        assertForbidden( joinAs( "mallory" ) );
    }

    @Test
    void anAdministratorCanJoin() {
        aliceDraftsThenThePageIsDeleted();
        permissions.admins.add( "root" );

        final Joined root = assertInstanceOf( Joined.class, joinAs( "root" ) );

        assertTrue( root.response().frozen() );
        assertEquals( "unpublished " + RESTRICTED, root.response().doc() );
    }

    @Test
    void aCallerTheBaseRulesAdmitCanJoinEvenAfterAReload() {
        writer.seed( PAGE, "[{ALLOW edit alice,bob}]\nshared\n", Map.of() );
        final Joined alice = assertInstanceOf( Joined.class, joinAs( "alice" ) );
        type( alice, "alice", "x" );
        assertEquals( 200, alice.session().leave( alice.response().participantId(), "alice" ).status() );
        writer.delete( PAGE );
        registry.freeze( PAGE );
        registry.tick();
        clock.advance( Duration.ofMinutes( 10 ) );
        registry.tick();
        assertTrue( registry.liveForPage( PAGE ).isEmpty(), "idled out: the roster is gone" );

        assertInstanceOf( Joined.class, joinAs( "bob" ) );
        assertForbidden( joinAs( "mallory" ) );
    }

    @Test
    void aBaseWithoutRulesCanStillBeRecreatedByACreatePagesUser() {
        writer.seed( PAGE, "an open page\n", Map.of() );
        final Joined alice = assertInstanceOf( Joined.class, joinAs( "alice" ) );
        type( alice, "alice", "edit " );
        writer.delete( PAGE );
        registry.freeze( PAGE );

        final Joined bob = assertInstanceOf( Joined.class, joinAs( "bob" ) );

        assertTrue( bob.response().frozen() );
        permissions.noCreate.add( "carol" );
        assertForbidden( joinAs( "carol" ) );
    }

    @Test
    void aBaseWhoseRulesCannotBeEvaluatedRefuses() {
        writer.seed( PAGE, "[{ALLOW broken}]\nx\n", Map.of() );
        assertInstanceOf( Joined.class, joinAs( "alice" ) );
        writer.delete( PAGE );
        registry.freeze( PAGE );

        assertForbidden( joinAs( "mallory" ) );
    }

    @Test
    void aNewPagesDraftNeedsOnlyCreatePages() {
        final Joined first = assertInstanceOf( Joined.class, registry.join( "Brand", new JoinRequest( "alice", null,
            "alice", "alice", "fresh\n", null ) ) );
        type( first, "alice", "y" );

        assertInstanceOf( Joined.class, registry.join( "Brand", new JoinRequest( "bob", null, "bob", "bob", null,
            null ) ) );
    }
}
