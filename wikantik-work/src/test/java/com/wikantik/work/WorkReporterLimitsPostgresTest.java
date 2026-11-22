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

import java.io.ByteArrayOutputStream;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Hardening of the surfaces a person who only reports meets (#157, lane D): downloads read in bounded pieces, the
 * reporter's own share of an item's attachment room, the daily cap on filing, and no user id in the text shown.
 */
@RequiresPostgres
class WorkReporterLimitsPostgresTest {

    private static final byte[] PNG = { (byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3 };
    private static final Actor LENA = Actor.human( "lena", Set.of(), false );
    private static final Actor OTTO = Actor.human( "otto", Set.of(), false );
    private static final Actor RITA = Actor.human( "rita", Set.of(), false );
    private static final String UID = "u-7f3a9c";

    private WorkClaimFixtures fx;

    @BeforeEach
    void setUp() throws SQLException {
        PostgresTestDb.truncate( WorkAgentFixtures.TABLES );
        fx = new WorkClaimFixtures( PostgresTestDb.createDataSource() );
        fx.store.putMember( fx.plat.id(), "lena", "user", WorkRole.LEAD, LENA );
        fx.store.putMember( fx.plat.id(), "otto", "user", WorkRole.MEMBER, LENA );
        fx.store.putMember( fx.plat.id(), "rita", "user", WorkRole.REPORTER, LENA );
        fx.store.putMember( fx.plat.id(), "sam", "user", WorkRole.REPORTER, LENA );
    }

    private WorkService service( final WorkSettings settings ) {
        return new WorkService( fx.store, md -> md, new WorkDirectory() {
            @Override public Set< String > groupsOf( final String login ) { return Set.of(); }
            @Override public boolean exists( final String login ) { return Set.of( "lena", "otto", "rita", "sam" ).contains( login ); }
            @Override public String displayName( final String login ) { return "lena".equals( login ) ? "Lena L" : login; }
            @Override public Optional< String > loginForMentionId( final String id ) {
                return UID.equals( id ) ? Optional.of( "lena" ) : Optional.empty();
            }
        }, Clock.systemUTC(), settings, new WorkListener() { } );
    }

    private static WorkSettings settings( final long file, final long item, final long reporterItem, final int perDay ) {
        return new WorkSettings( Duration.ofMinutes( 240 ), file, item, reporterItem, perDay );
    }

    private static byte[] png( final int size ) {
        final byte[] b = new byte[ size ];
        System.arraycopy( PNG, 0, b, 0, PNG.length );
        return b;
    }

    // ---- item 1: a download is read in pieces

    @Test
    void aDownloadArrivesInBoundedPiecesAndIsByteForByteTheFile() throws Exception {
        final WorkService svc = service( settings( 2_000_000, 4_000_000, 2_000_000, 0 ) );
        final String key = svc.features().intake().submit( RITA, new IntakeDraft( "PLAT", null, null, "t", "b" ) ).key();
        final byte[] file = png( 600_000 );
        for ( int i = PNG.length; i < file.length; i++ ) {
            file[ i ] = (byte) ( i * 31 );
        }
        final Attachment a = svc.features().attachments().upload( OTTO, key, null, "big.png", file );
        final ByteArrayOutputStream all = new ByteArrayOutputStream();
        final List< Integer > pieces = new ArrayList<>();
        final List< Attachment > begun = new ArrayList<>();
        svc.features().attachments().download( RITA, key, a.id(), new WorkAttachmentsService.Download() {
            @Override public void begin( final Attachment att ) { begun.add( att ); }
            @Override public void chunk( final byte[] buffer, final int length ) {
                pieces.add( length );
                all.write( buffer, 0, length );
            }
        } );
        assertEquals( 1, begun.size() );
        assertEquals( a.id(), begun.get( 0 ).id() );
        assertArrayEquals( file, all.toByteArray() );
        assertEquals( 3, pieces.size(), "600000 bytes at 256 KiB a piece" );
        assertTrue( pieces.stream().allMatch( n -> n <= WorkAttachmentsService.CHUNK_BYTES ), pieces.toString() );
    }

    @Test
    void aDownloadOfAnAttachmentNotOnThisItemIsNotFoundBeforeAnythingIsSent() throws Exception {
        final WorkService svc = service( WorkSettings.DEFAULTS );
        final String mine = svc.features().intake().submit( RITA, new IntakeDraft( "PLAT", null, null, "t", "b" ) ).key();
        final Item lenas = fx.ready( "lena's" );
        final Attachment onLenas = svc.features().attachments().upload( LENA, lenas.key(), null, "x.png", PNG );
        final List< String > sent = new ArrayList<>();
        final WorkAttachmentsService.Download sink = new WorkAttachmentsService.Download() {
            @Override public void begin( final Attachment att ) { sent.add( "begin" ); }
            @Override public void chunk( final byte[] buffer, final int length ) { sent.add( "chunk" ); }
        };
        assertThrows( WorkNotFoundException.class,
            () -> svc.features().attachments().download( RITA, mine, onLenas.id(), sink ) );
        assertThrows( WorkNotFoundException.class,
            () -> svc.features().attachments().download( RITA, lenas.key(), onLenas.id(), sink ) );
        assertTrue( sent.isEmpty(), "nothing was handed over: " + sent );
    }

    // ---- item 2: the reporter's share of the attachment room, and the filing cap

    @Test
    void aReporterMayUploadOnlyUpToTheirOwnShareOfTheItemButMembersAreNotHeldToIt() throws Exception {
        final WorkService svc = service( settings( 1000, 5000, 25, 0 ) );
        final String key = svc.features().intake().submit( RITA, new IntakeDraft( "PLAT", null, null, "t", "b" ) ).key();
        final WorkAttachmentsService files = svc.features().attachments();
        files.upload( OTTO, key, null, "members.png", png( 20 ) );
        files.upload( RITA, key, null, "one.png", png( 20 ) );
        final WorkConflictException e = assertThrows( WorkConflictException.class,
            () -> files.upload( RITA, key, null, "two.png", png( 20 ) ) );
        assertEquals( WorkConflictException.REPORTER_FULL, e.reason() );
        assertTrue( e.getMessage().contains( "25" ), e.getMessage() );
        assertEquals( 2, files.list( RITA, key ).size(), "the refused file was not stored" );
        files.upload( OTTO, key, null, "more.png", png( 400 ) );   // a member is held to the item room only
        files.remove( RITA, key, files.list( RITA, key ).stream().filter( a -> a.uploadedBy().equals( "rita" ) )
            .findFirst().orElseThrow().id() );
        // Removing a file gives no room back: upload, remove, upload cannot churn past the cap.
        assertEquals( WorkConflictException.REPORTER_FULL, assertThrows( WorkConflictException.class,
            () -> files.upload( RITA, key, null, "two.png", png( 20 ) ) ).reason() );
    }

    @Test
    void theItemRoomStillWinsOverTheReportersShare() throws Exception {
        final WorkService svc = service( settings( 1000, 30, 25, 0 ) );
        final String key = svc.features().intake().submit( RITA, new IntakeDraft( "PLAT", null, null, "t", "b" ) ).key();
        svc.features().attachments().upload( OTTO, key, null, "members.png", png( 20 ) );
        final WorkConflictException e = assertThrows( WorkConflictException.class,
            () -> svc.features().attachments().upload( RITA, key, null, "one.png", png( 20 ) ) );
        assertEquals( WorkConflictException.ITEM_FULL, e.reason() );
    }

    @Test
    void aReporterMayFileOnlyTheirDailyCapAndOthersAreNotCounted() throws Exception {
        final WorkService svc = service( settings( 1000, 5000, 25, 2 ) );
        final WorkIntakeService intake = svc.features().intake();
        intake.submit( RITA, new IntakeDraft( "PLAT", null, null, "one", "b" ) );
        intake.submit( RITA, new IntakeDraft( "PLAT", null, null, "two", "b" ) );
        final WorkConflictException e = assertThrows( WorkConflictException.class,
            () -> intake.submit( RITA, new IntakeDraft( "PLAT", null, null, "three", "b" ) ) );
        assertEquals( WorkConflictException.INTAKE_LIMIT, e.reason() );
        assertEquals( 2, intake.reported( RITA ).size(), "nothing was filed by the refused request" );
        // A member filing through the same door is not a reporter, and is not held to the cap.
        intake.submit( OTTO, new IntakeDraft( "PLAT", null, null, "otto one", "b" ) );
        intake.submit( OTTO, new IntakeDraft( "PLAT", null, null, "otto two", "b" ) );
        intake.submit( OTTO, new IntakeDraft( "PLAT", null, null, "otto three", "b" ) );
        // The window rolls: what was filed more than 24 hours ago no longer counts.
        WorkAgentFixtures.exec( fx.ds, "UPDATE work_item SET created_at = now() - INTERVAL '25 hours' "
            + "WHERE reporter = 'rita' AND title = 'one'" );
        intake.submit( RITA, new IntakeDraft( "PLAT", null, null, "four", "b" ) );
    }

    @Test
    void theCapIsPerReporterAndCountsHumansOnly() throws Exception {
        final WorkService svc = service( settings( 1000, 5000, 25, 2 ) );
        final WorkIntakeService intake = svc.features().intake();
        // An agent that happens to carry the login "rita" has filed three items: they are not rita's.
        for ( int i = 0; i < 3; i++ ) {
            fx.store.createItem( fx.plat, new Item( 0, 0, null, WorkLevel.TASK, null, null, WorkKind.SOFTWARE, "bot " + i,
                "b", WorkStatus.DRAFT, 3, 0, null, null, null, null, null, null, 0, null, null, null,
                null, null, null, null, null, null, null, null, null ), fx.actorA );
        }
        WorkAgentFixtures.exec( fx.ds, "UPDATE work_item SET reporter = 'rita' WHERE reporter_agent_id IS NOT NULL" );
        final Actor sam = Actor.human( "sam", Set.of(), false );
        intake.submit( RITA, new IntakeDraft( "PLAT", null, null, "r1", "b" ) );
        intake.submit( RITA, new IntakeDraft( "PLAT", null, null, "r2", "b" ) );
        assertThrows( WorkConflictException.class,
            () -> intake.submit( RITA, new IntakeDraft( "PLAT", null, null, "r3", "b" ) ) );
        // sam has filed nothing: rita's two do not count against him
        intake.submit( sam, new IntakeDraft( "PLAT", null, null, "s1", "b" ) );
        intake.submit( sam, new IntakeDraft( "PLAT", null, null, "s2", "b" ) );
        assertEquals( WorkConflictException.INTAKE_LIMIT, assertThrows( WorkConflictException.class,
            () -> intake.submit( sam, new IntakeDraft( "PLAT", null, null, "s3", "b" ) ) ).reason() );
    }

    @Test
    void aParallelBurstFromOneReporterCannotPassTheCap() throws Exception {
        final WorkIntakeService intake = service( settings( 1000, 5000, 25, 3 ) ).features().intake();
        final int threads = 8;
        final java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool( threads );
        final java.util.concurrent.CountDownLatch go = new java.util.concurrent.CountDownLatch( 1 );
        final List< java.util.concurrent.Future< String > > results = new ArrayList<>();
        for ( int i = 0; i < threads; i++ ) {
            final int n = i;
            results.add( pool.submit( () -> {
                go.await();
                try {
                    intake.submit( RITA, new IntakeDraft( "PLAT", null, null, "burst " + n, "b" ) );
                    return "filed";
                } catch ( final WorkConflictException e ) {
                    return e.reason();
                }
            } ) );
        }
        go.countDown();
        int filed = 0;
        int refused = 0;
        for ( final java.util.concurrent.Future< String > f : results ) {
            final String r = f.get( 60, java.util.concurrent.TimeUnit.SECONDS );
            if ( "filed".equals( r ) ) {
                filed++;
            } else {
                assertEquals( WorkConflictException.INTAKE_LIMIT, r );
                refused++;
            }
        }
        pool.shutdown();
        assertEquals( 3, filed, "exactly the cap" );
        assertEquals( threads - 3, refused );
        assertEquals( 3, WorkAgentFixtures.scalar( fx.ds, "SELECT COUNT(*) FROM work_item WHERE reporter = 'rita'" ) );
    }

    @Test
    void zeroMeansNoCap() throws Exception {
        final WorkIntakeService intake = service( settings( 1000, 5000, 25, 0 ) ).features().intake();
        for ( int i = 0; i < 25; i++ ) {
            intake.submit( RITA, new IntakeDraft( "PLAT", null, null, "t" + i, "b" ) );
        }
        assertFalse( intake.reported( RITA ).isEmpty() );
    }

    // ---- item 3: no user id in the text a reporter is shown

    @Test
    void aMentionTokenReachesAReporterAsTheDisplayNameNeverTheUserId() throws Exception {
        final WorkService svc = service( WorkSettings.DEFAULTS );
        final String key = svc.features().intake().submit( RITA, new IntakeDraft( "PLAT", null, null, "t",
            "ask @[Lena L](user:" + UID + ") please" ) ).key();
        final Item item = fx.store.item( key ).orElseThrow();
        fx.store.addComment( item, OTTO, "cc @[Lena L](user:" + UID + ") and @[Gone](user:u-nobody)", List.of( "lena" ) );
        final ReportedItemView view = svc.features().intake().item( RITA, key );
        assertEquals( "ask @Lena L please", view.body() );
        final ReportedComment c = view.comments().get( 0 );
        assertEquals( "cc @Lena L and @Gone", c.body() );
        assertFalse( c.body().contains( "user:" ) || c.body().contains( UID ) );
        assertEquals( List.of( "Lena L", "Gone" ), c.mentions() );
        // a member still gets the stored text, token and all
        assertTrue( svc.item( LENA, key ).comments().get( 0 ).body().contains( "(user:" + UID + ")" ) );
    }

    /** The test renderer returns its Markdown unchanged, so a uid in the source shows in the HTML field. */
    @Test
    void theRenderedHtmlFieldsAreMadeFromTheReshapedTextNotTheStoredOne() throws Exception {
        final WorkService svc = service( WorkSettings.DEFAULTS );
        final String key = svc.features().intake().submit( RITA, new IntakeDraft( "PLAT", null, null, "t",
            "ask @[Lena L](user:" + UID + ") please" ) ).key();
        fx.store.addComment( fx.store.item( key ).orElseThrow(), OTTO, "cc @[Lena L](user:" + UID + ")", List.of( "lena" ) );
        final ReportedItemView view = svc.features().intake().item( RITA, key );
        assertEquals( "ask @Lena L please", view.bodyHtml() );
        assertEquals( "cc @Lena L", view.comments().get( 0 ).bodyHtml() );
        // the answer to a comment she posts herself is reshaped the same way
        final ReportedComment posted = svc.features().intake().comment( RITA, key, "re @[Lena L](user:" + UID + ")" );
        assertFalse( posted.body().contains( UID ) || posted.bodyHtml().contains( UID ), posted.toString() );
    }

    @Test
    void noTokenSurvivesForAReporterPastTheMentionLimitOrWhenMalformed() throws Exception {
        final WorkService svc = service( WorkSettings.DEFAULTS );
        final StringBuilder many = new StringBuilder();
        for ( int i = 0; i < 60; i++ ) {
            many.append( "@[Person" ).append( i ).append( "](user:uid-secret-" ).append( i ).append( ") " );
        }
        final String malformed = "x @[Odd](user:bad_id!!secret) y [Plain](user:secret-link) z "
            + "@[Long](user:" + "s".repeat( 500 ) + ")";
        final String key = svc.features().intake().submit( RITA, new IntakeDraft( "PLAT", null, null, "t",
            many.toString() ) ).key();
        final Item item = fx.store.item( key ).orElseThrow();
        fx.store.addComment( item, OTTO, many + malformed, List.of() );
        final ReportedItemView view = svc.features().intake().item( RITA, key );
        for ( final String text : List.of( view.body(), view.bodyHtml(), view.comments().get( 0 ).body(),
                view.comments().get( 0 ).bodyHtml() ) ) {
            assertFalse( text.contains( "user:" ) || text.contains( "secret" ), text );
        }
        assertTrue( view.body().contains( "@Person59" ), "past the limit the snapshot name is shown" );
        assertTrue( view.comments().get( 0 ).body().contains( "@Odd" ) );
    }
}
