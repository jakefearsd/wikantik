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
import com.wikantik.work.WorkAttachmentStore.NewAttachment;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The attachment store against real PostgreSQL: the two-id rule, the cap under concurrency, fences, events. */
@RequiresPostgres
class JdbcWorkAttachmentsPostgresTest {

    static final byte[] PNG = { (byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 1, 2 };

    private WorkClaimFixtures fx;
    private WorkAttachmentStore attachments;
    private Item item;
    private Actor lena;

    @BeforeEach
    void setUp() throws SQLException {
        PostgresTestDb.truncate( WorkAgentFixtures.TABLES );
        fx = new WorkClaimFixtures( PostgresTestDb.createDataSource() );
        attachments = fx.store.notifyStores().attachments();
        item = fx.ready( "x" );
        lena = WorkClaimFixtures.LENA;
    }

    static NewAttachment file( final String name, final byte[] bytes ) {
        return new NewAttachment( name, "image/png", bytes, "abababababababababababababababababababababababababababababababab" );
    }

    private Attachment add( final String name, final int size ) throws SQLException {
        return attachments.add( item, null, file( name, new byte[ size ] ), Long.MAX_VALUE, lena );
    }

    @Test
    void addWritesTheRowTheBlobAndOneAttachedEvent() throws SQLException {
        final Attachment a = attachments.add( item, null, file( "shot.png", PNG ), Long.MAX_VALUE, lena );
        assertEquals( item.id(), a.itemId() );
        assertEquals( PNG.length, a.sizeBytes() );
        assertEquals( "ab".repeat( 32 ), a.sha256() );
        assertEquals( "lena", a.uploadedBy() );
        assertNull( a.uploadedByAgentId() );
        assertNotNull( a.uploadedAt() );
        assertNull( a.removedAt() );
        assertArrayEquals( PNG, attachments.content( item.id(), a.id() ).orElseThrow().content() );
        final List< WorkEvent > ev = fx.events( item, WorkEvent.ATTACHED );
        assertEquals( 1, ev.size() );
        assertEquals( "attachment:" + a.id(), ev.get( 0 ).fromValue() );
        assertEquals( "shot.png", ev.get( 0 ).toValue() );
        assertNull( ev.get( 0 ).note() );
    }

    @Test
    void aCommentAttachmentNamesTheCommentInItsEvent() throws SQLException {
        final Comment c = fx.store.addComment( item, lena, "hi", List.of() );
        final Attachment a = attachments.add( item, c.id(), file( "a.png", PNG ), Long.MAX_VALUE, lena );
        assertEquals( c.id(), a.commentId() );
        assertEquals( "comment:" + c.id(), fx.events( item, WorkEvent.ATTACHED ).get( 0 ).note() );
    }

    @Test
    void aCommentOfAnotherItemOrARemovedOneIsRefused() throws SQLException {
        final Item other = fx.ready( "y" );
        final Comment foreign = fx.store.addComment( other, lena, "hi", List.of() );
        final Comment removed = fx.store.addComment( item, lena, "gone", List.of() );
        fx.store.removeComment( removed.id(), lena );
        for ( final long id : new long[] { foreign.id(), removed.id(), 999_999L } ) {
            final WorkValidationException e = assertThrows( WorkValidationException.class,
                () -> attachments.add( item, id, file( "a.png", PNG ), Long.MAX_VALUE, lena ) );
            assertEquals( "comment", e.field() );
        }
        assertTrue( attachments.forItem( item.id() ).isEmpty() );
        assertTrue( fx.events( item, WorkEvent.ATTACHED ).isEmpty() );
    }

    @Test
    void contentNeedsBothIds() throws SQLException {
        final Item other = fx.ready( "y" );
        final Attachment a = add( "a.png", 3 );
        assertEquals( 3, attachments.content( item.id(), a.id() ).orElseThrow().content().length );
        assertTrue( attachments.content( other.id(), a.id() ).isEmpty() );
        assertTrue( attachments.attachment( other.id(), a.id() ).isEmpty() );
        assertTrue( attachments.attachment( item.id(), a.id() ).isPresent() );
        assertTrue( attachments.content( item.id(), 999_999L ).isEmpty() );
    }

    @Test
    void removeKeepsTheRowAndEventAndDeletesTheBytes() throws SQLException {
        final Attachment a = add( "a.png", 3 );
        final Attachment gone = attachments.remove( item, a.id(), lena );
        assertNotNull( gone.removedAt() );
        assertEquals( 1, WorkAgentFixtures.scalar( fx.ds,
            "SELECT count(*) FROM work_attachment WHERE removed_at IS NOT NULL AND id = " + a.id() ) );
        assertEquals( 0, WorkAgentFixtures.scalar( fx.ds, "SELECT count(*) FROM work_attachment_blob" ) );
        assertTrue( attachments.content( item.id(), a.id() ).isEmpty() );
        assertTrue( attachments.attachment( item.id(), a.id() ).isEmpty() );
        assertTrue( attachments.forItem( item.id() ).isEmpty() );
        final List< WorkEvent > ev = fx.events( item, WorkEvent.ATTACHMENT_REMOVED );
        assertEquals( 1, ev.size() );
        assertEquals( "attachment:" + a.id(), ev.get( 0 ).fromValue() );
        assertEquals( "a.png", ev.get( 0 ).toValue() );
    }

    @Test
    void removingTwiceOrThroughAnotherItemIsNotFound() throws SQLException {
        final Item other = fx.ready( "y" );
        final Attachment a = add( "a.png", 3 );
        assertThrows( WorkNotFoundException.class, () -> attachments.remove( other, a.id(), lena ) );
        assertEquals( 1, attachments.forItem( item.id() ).size() );
        attachments.remove( item, a.id(), lena );
        assertThrows( WorkNotFoundException.class, () -> attachments.remove( item, a.id(), lena ) );
        assertEquals( 1, fx.events( item, WorkEvent.ATTACHMENT_REMOVED ).size() );
    }

    @Test
    void theItemCapCountsLiveAttachmentsOnly() throws SQLException {
        final Attachment first = attachments.add( item, null, file( "a.png", new byte[ 6 ] ), 10, lena );
        final WorkConflictException e = assertThrows( WorkConflictException.class,
            () -> attachments.add( item, null, file( "b.png", new byte[ 6 ] ), 10, lena ) );
        assertEquals( WorkConflictException.ITEM_FULL, e.reason() );
        assertEquals( 1, attachments.forItem( item.id() ).size() );
        assertEquals( 1, WorkAgentFixtures.scalar( fx.ds, "SELECT count(*) FROM work_attachment_blob" ) );
        attachments.remove( item, first.id(), lena );
        attachments.add( item, null, file( "b.png", new byte[ 6 ] ), 10, lena );
        assertEquals( 1, attachments.forItem( item.id() ).size() );
    }

    @Test
    void concurrentUploadsNeverPassTheItemCap() throws Exception {
        final CountDownLatch go = new CountDownLatch( 1 );
        final ExecutorService pool = Executors.newFixedThreadPool( 2 );
        try {
            final List< Future< Attachment > > runs = new ArrayList<>();
            for ( int i = 0; i < 2; i++ ) {
                final String name = "f" + i + ".png";
                runs.add( pool.submit( () -> {
                    go.await();
                    return attachments.add( item, null, file( name, new byte[ 6 ] ), 10, lena );
                } ) );
            }
            go.countDown();
            int landed = 0;
            int refused = 0;
            for ( final Future< Attachment > run : runs ) {
                try {
                    run.get();
                    landed++;
                } catch ( final ExecutionException e ) {
                    assertTrue( e.getCause() instanceof WorkConflictException, String.valueOf( e.getCause() ) );
                    assertEquals( WorkConflictException.ITEM_FULL, ( ( WorkConflictException ) e.getCause() ).reason() );
                    refused++;
                }
            }
            assertEquals( 1, landed );
            assertEquals( 1, refused );
        } finally {
            pool.shutdownNow();
        }
        assertEquals( 1, attachments.forItem( item.id() ).size() );
        assertEquals( 1, WorkAgentFixtures.scalar( fx.ds, "SELECT count(*) FROM work_attachment_blob" ) );
    }

    @Test
    void anAgentIsFencedByAnotherAgentsClaim() throws SQLException {
        final Item claimed = fx.store.claims().claim( item, fx.actorB, Duration.ofHours( 1 ) ).orElseThrow();
        assertThrows( ClaimFenceException.class,
            () -> attachments.add( claimed, null, file( "a.png", PNG ), Long.MAX_VALUE, fx.actorA ) );
        final Attachment mine = attachments.add( claimed, null, file( "b.png", PNG ), Long.MAX_VALUE, fx.actorB );
        assertThrows( ClaimFenceException.class, () -> attachments.remove( claimed, mine.id(), fx.actorA ) );
        assertEquals( 1, attachments.forItem( item.id() ).size() );
        attachments.remove( claimed, mine.id(), fx.actorB );
    }

    @Test
    void anAgentsUploadRecordsItsId() throws SQLException {
        final Attachment a = attachments.add( item, null, file( "a.png", PNG ), Long.MAX_VALUE, fx.actorA );
        assertEquals( fx.a.id(), a.uploadedByAgentId() );
        assertEquals( fx.a.id(), fx.events( item, WorkEvent.ATTACHED ).get( 0 ).agentId() );
    }
}
