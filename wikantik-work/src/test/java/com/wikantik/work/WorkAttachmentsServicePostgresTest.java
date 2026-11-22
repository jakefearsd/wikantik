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

import com.wikantik.jdbc.testing.PostgresTestDb;
import com.wikantik.jdbc.testing.RequiresPostgres;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The attachments facade against real PostgreSQL: who may attach and remove, types from bytes, limits, hiding. */
@RequiresPostgres
class WorkAttachmentsServicePostgresTest {

    private static final byte[] PNG = { (byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3 };
    private static final byte[] HTML = "<html><script>alert(1)</script>".getBytes( StandardCharsets.UTF_8 );

    private final Actor lena = Actor.human( "lena", Set.of(), false );
    private final Actor bob = Actor.human( "bob", Set.of(), false );
    private final Actor carol = Actor.human( "carol", Set.of(), false );
    private final Actor vera = Actor.human( "vera", Set.of(), false );

    private WorkClaimFixtures fx;
    private Item first;
    private Item second;

    @BeforeEach
    void setUp() throws SQLException {
        PostgresTestDb.truncate( WorkAgentFixtures.TABLES );
        fx = new WorkClaimFixtures( PostgresTestDb.createDataSource() );
        fx.store.putMember( fx.plat.id(), "lena", "user", WorkRole.LEAD, lena );
        fx.store.putMember( fx.plat.id(), "bob", "user", WorkRole.MEMBER, lena );
        fx.store.putMember( fx.plat.id(), "carol", "user", WorkRole.MEMBER, lena );
        fx.store.putMember( fx.plat.id(), "vera", "user", WorkRole.VIEWER, lena );
        first = fx.ready( "one" );
        second = fx.ready( "two" );
    }

    private WorkAttachmentsService service( final WorkSettings settings ) {
        return new WorkService( fx.store, md -> md, new WorkDirectory() {
            @Override public Set< String > groupsOf( final String login ) { return Set.of(); }
            @Override public boolean exists( final String login ) { return WorkAgentFixtures.isPerson( login ); }
            @Override public String displayName( final String login ) { return login; }
        }, Clock.systemUTC(), settings, new WorkListener() { } ).features().attachments();
    }

    private WorkAttachmentsService service() {
        return service( WorkSettings.DEFAULTS );
    }

    @Test
    void aMemberUploadsAndTheStoredTypeComesFromTheBytes() throws SQLException {
        final WorkAttachmentsService svc = service();
        final Attachment html = svc.upload( bob, first.key(), null, "x.png", HTML );
        assertEquals( "application/octet-stream", html.contentType() );
        final Attachment png = svc.upload( bob, first.key(), null, "x.txt", PNG );
        assertEquals( "image/png", png.contentType() );
        assertEquals( PNG.length, png.sizeBytes() );
        assertEquals( 64, png.sha256().length() );
        assertArrayEquals( PNG, svc.content( lena, first.key(), png.id() ).content() );
        assertEquals( 2, svc.list( lena, first.key() ).size() );
    }

    @Test
    void theNameIsCleaned() throws SQLException {
        assertEquals( "a.png", service().upload( bob, first.key(), null, "../../a.png", PNG ).filename() );
    }

    @Test
    void aViewerMayReadButNotUpload() throws SQLException {
        final WorkAttachmentsService svc = service();
        assertThrows( WorkDeniedException.class, () -> svc.upload( vera, first.key(), null, "a.png", PNG ) );
        svc.upload( bob, first.key(), null, "a.png", PNG );
        assertEquals( 1, svc.list( vera, first.key() ).size() );
        assertThrows( WorkDeniedException.class, () -> svc.remove( vera, first.key(), 1L ) );
    }

    @Test
    void anotherItemsAttachmentIsNotFoundThroughThisItem() throws SQLException {
        final WorkAttachmentsService svc = service();
        final Attachment onSecond = svc.upload( bob, second.key(), null, "a.png", PNG );
        final WorkNotFoundException foreign = assertThrows( WorkNotFoundException.class,
            () -> svc.content( lena, first.key(), onSecond.id() ) );
        final WorkNotFoundException unknown = assertThrows( WorkNotFoundException.class,
            () -> svc.content( lena, first.key(), 999_999L ) );
        assertEquals( unknown.getMessage(), foreign.getMessage() );
        assertThrows( WorkNotFoundException.class, () -> svc.remove( lena, first.key(), onSecond.id() ) );
        assertEquals( 1, svc.list( lena, second.key() ).size() );
    }

    @Test
    void aFileOverTheLimitIsTooLargeAndNothingIsStored() throws SQLException {
        final WorkAttachmentsService svc = service( new WorkSettings( Duration.ofMinutes( 240 ), 8, 100 ) );
        final WorkConflictException e = assertThrows( WorkConflictException.class,
            () -> svc.upload( bob, first.key(), null, "a.png", PNG ) );
        assertEquals( WorkConflictException.TOO_LARGE, e.reason() );
        assertTrue( svc.list( bob, first.key() ).isEmpty() );
        assertEquals( 8, svc.maxFileBytes() );
    }

    @Test
    void theItemLimitIsEnforcedThroughTheService() throws SQLException {
        final WorkAttachmentsService svc = service( new WorkSettings( Duration.ofMinutes( 240 ), 100, 20 ) );
        svc.upload( bob, first.key(), null, "a.png", PNG );
        final WorkConflictException e = assertThrows( WorkConflictException.class,
            () -> svc.upload( bob, first.key(), null, "b.png", PNG ) );
        assertEquals( WorkConflictException.ITEM_FULL, e.reason() );
        assertEquals( 1, svc.list( bob, first.key() ).size() );
    }

    @Test
    void theListingCarriesTheLimitsTheUsedBytesAndWhetherTheCallerMayAttach() throws SQLException {
        final WorkAttachmentsService svc = service( new WorkSettings( Duration.ofMinutes( 240 ), 100, 500 ) );
        svc.upload( bob, first.key(), null, "a.png", PNG );
        final AttachmentListing member = svc.listing( bob, first.key() );
        assertTrue( member.mayAttach() );
        assertEquals( 100, member.maxFileBytes() );
        assertEquals( 500, member.maxItemBytes() );
        assertEquals( PNG.length, member.usedBytes() );
        assertEquals( 1, member.attachments().size() );
        final AttachmentListing viewer = svc.listing( vera, first.key() );
        assertFalse( viewer.mayAttach() );
        assertEquals( 1, viewer.attachments().size() );
        assertEquals( 0, svc.listing( bob, second.key() ).usedBytes() );
    }

    @Test
    void anEmptyOrNamelessFileIsRefusedNamingFile() {
        final WorkAttachmentsService svc = service();
        for ( final org.junit.jupiter.api.function.Executable attempt : List.< org.junit.jupiter.api.function.Executable >of(
            () -> svc.upload( bob, first.key(), null, "a.png", new byte[ 0 ] ),
            () -> svc.upload( bob, first.key(), null, "a.png", null ),
            () -> svc.upload( bob, first.key(), null, "..", PNG ),
            () -> svc.upload( bob, first.key(), null, null, PNG ) ) ) {
            assertEquals( "file", assertThrows( WorkValidationException.class, attempt ).field() );
        }
    }

    @Test
    void anArchivedProjectRefusesUploadAndRemoveButStillReads() throws SQLException {
        final WorkAttachmentsService svc = service();
        final Attachment a = svc.upload( bob, first.key(), null, "a.png", PNG );
        fx.store.archiveProject( fx.plat.id(), Instant.parse( "2026-01-01T00:00:00Z" ), lena );
        assertEquals( WorkConflictException.ARCHIVED,
            assertThrows( WorkConflictException.class, () -> svc.upload( bob, first.key(), null, "b.png", PNG ) ).reason() );
        assertEquals( WorkConflictException.ARCHIVED,
            assertThrows( WorkConflictException.class, () -> svc.remove( lena, first.key(), a.id() ) ).reason() );
        assertEquals( 1, svc.list( bob, first.key() ).size() );
        assertArrayEquals( PNG, svc.content( bob, first.key(), a.id() ).content() );
    }

    @Test
    void mayAttachAnswersWhatTheUploadWouldAnswerAndWritesNothing() throws SQLException {
        final WorkAttachmentsService svc = service();
        assertTrue( svc.mayAttach( bob, first.key() ) );
        assertTrue( svc.mayAttach( lena, first.key() ) );
        assertThrows( WorkDeniedException.class, () -> svc.mayAttach( vera, first.key() ) );
        assertThrows( WorkNotFoundException.class,
            () -> svc.mayAttach( Actor.human( "mallory", Set.of(), false ), first.key() ) );
        assertThrows( WorkNotFoundException.class, () -> svc.mayAttach( bob, "NOPE-1" ) );
        fx.store.claims().claim( first, fx.actorB, Duration.ofHours( 1 ) ).orElseThrow();
        assertEquals( "This item is claimed by another agent.",
            assertThrows( WorkDeniedException.class, () -> svc.mayAttach( fx.actorA, first.key() ) ).getMessage() );
        assertTrue( svc.list( lena, first.key() ).isEmpty() );
        fx.store.archiveProject( fx.plat.id(), Instant.parse( "2026-01-01T00:00:00Z" ), lena );
        assertEquals( WorkConflictException.ARCHIVED,
            assertThrows( WorkConflictException.class, () -> svc.mayAttach( bob, second.key() ) ).reason() );
    }

    @Test
    void anAgentFencedByAnotherAgentsClaimIsRefused() throws SQLException {
        final WorkAttachmentsService svc = service();
        fx.store.claims().claim( first, fx.actorB, Duration.ofHours( 1 ) ).orElseThrow();
        final WorkDeniedException e = assertThrows( WorkDeniedException.class,
            () -> svc.upload( fx.actorA, first.key(), null, "a.png", PNG ) );
        assertEquals( "This item is claimed by another agent.", e.getMessage() );
        assertTrue( svc.list( lena, first.key() ).isEmpty() );
        svc.upload( fx.actorB, first.key(), null, "b.png", PNG );
        assertEquals( 1, svc.list( lena, first.key() ).size() );
    }

    @Test
    void aClaimThatLandsAfterTheReadIsFencedAtTheStoreToo() throws SQLException {
        // the service read the item unclaimed; B claims it; A's store write finds the claim under the lock
        final WorkAttachmentStore store = fx.store.notifyStores().attachments();
        fx.store.claims().claim( first, fx.actorB, Duration.ofHours( 1 ) ).orElseThrow();
        assertThrows( ClaimFenceException.class, () -> store.add( first, null,
            new WorkAttachmentStore.NewAttachment( "a.png", "image/png", PNG, "0000000000000000000000000000000000000000000000000000000000000000" ), Long.MAX_VALUE, fx.actorA ) );
    }

    @Test
    void onlyTheUploaderOrALeadRemoves() throws SQLException {
        final WorkAttachmentsService svc = service();
        final Attachment a = svc.upload( bob, first.key(), null, "a.png", PNG );
        final WorkDeniedException e = assertThrows( WorkDeniedException.class,
            () -> svc.remove( carol, first.key(), a.id() ) );
        assertEquals( "Only the uploader or a project lead may remove an attachment.", e.getMessage() );
        assertEquals( 1, svc.list( bob, first.key() ).size() );
        svc.remove( bob, first.key(), a.id() );
        final Attachment b = svc.upload( bob, first.key(), null, "b.png", PNG );
        svc.remove( lena, first.key(), b.id() );
        assertTrue( svc.list( lena, first.key() ).isEmpty() );
    }

    @Test
    void anAgentRemovesItsOwnUploadButNotAPersons() throws SQLException {
        final WorkAttachmentsService svc = service();
        final Attachment mine = svc.upload( fx.actorA, first.key(), null, "a.png", PNG );
        final Attachment persons = svc.upload( bob, first.key(), null, "b.png", PNG );
        assertThrows( WorkDeniedException.class, () -> svc.remove( fx.actorA, first.key(), persons.id() ) );
        assertThrows( WorkDeniedException.class, () -> svc.remove( fx.actorB, first.key(), mine.id() ) );
        svc.remove( fx.actorA, first.key(), mine.id() );
        assertEquals( List.of( persons.id() ), svc.list( bob, first.key() ).stream().map( Attachment::id ).toList() );
    }

    @Test
    void removalHidesTheAttachmentFromListAndContent() throws SQLException {
        final WorkAttachmentsService svc = service();
        final Attachment a = svc.upload( bob, first.key(), null, "a.png", PNG );
        final Attachment gone = svc.remove( bob, first.key(), a.id() );
        assertEquals( a.id(), gone.id() );
        assertTrue( svc.list( bob, first.key() ).isEmpty() );
        assertThrows( WorkNotFoundException.class, () -> svc.content( bob, first.key(), a.id() ) );
        assertThrows( WorkNotFoundException.class, () -> svc.remove( bob, first.key(), a.id() ) );
        assertEquals( Optional.empty(), fx.store.notifyStores().attachments().content( first.id(), a.id() ) );
    }

    @Test
    void aStrangerGetsNotFoundFromEveryCall() throws SQLException {
        final WorkAttachmentsService svc = service();
        final Actor mallory = Actor.human( "mallory", Set.of(), false );
        final Attachment a = svc.upload( bob, first.key(), null, "a.png", PNG );
        assertThrows( WorkNotFoundException.class, () -> svc.list( mallory, first.key() ) );
        assertThrows( WorkNotFoundException.class, () -> svc.content( mallory, first.key(), a.id() ) );
        assertThrows( WorkNotFoundException.class, () -> svc.upload( mallory, first.key(), null, "b.png", PNG ) );
        assertThrows( WorkNotFoundException.class, () -> svc.remove( mallory, first.key(), a.id() ) );
    }
}
