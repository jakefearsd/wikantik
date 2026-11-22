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

import com.wikantik.jdbc.testing.FaultInjectingDataSource;
import com.wikantik.jdbc.testing.PostgresTestDb;
import com.wikantik.jdbc.testing.RequiresPostgres;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** An attachment write and its event land together or not at all. */
@RequiresPostgres
class JdbcWorkAttachmentsRollbackTest {

    private WorkClaimFixtures fx;
    private Item item;

    @BeforeEach
    void setUp() throws SQLException {
        PostgresTestDb.truncate( WorkAgentFixtures.TABLES );
        fx = new WorkClaimFixtures( PostgresTestDb.createDataSource() );
        item = fx.ready( "x" );
    }

    private WorkAttachmentStore faulty( final int nth ) {
        final FaultInjectingDataSource f = new FaultInjectingDataSource( fx.ds );
        f.failOn( nth, new RuntimeException( "boom: statement " + nth ) );
        faultyHolder = f;
        return new JdbcWorkStore( f ).notifyStores().attachments();
    }

    private FaultInjectingDataSource faultyHolder;

    @Test
    void aFailedEventLeavesNoRowNoBlobNoEvent() throws SQLException {
        // statements: 1 lock the item, 2 sum, 3 insert the row, 4 insert the bytes, 5 the attached event
        final WorkAttachmentStore store = faulty( 5 );
        assertThrows( RuntimeException.class, () -> store.add( item, null,
            JdbcWorkAttachmentsPostgresTest.file( "a.png", JdbcWorkAttachmentsPostgresTest.PNG ), Long.MAX_VALUE,
            WorkClaimFixtures.LENA ) );
        assertEquals( 1, faultyHolder.rollbacks() );
        assertEquals( 0, WorkAgentFixtures.scalar( fx.ds, "SELECT count(*) FROM work_attachment" ) );
        assertEquals( 0, WorkAgentFixtures.scalar( fx.ds, "SELECT count(*) FROM work_attachment_blob" ) );
        assertTrue( fx.events( item, WorkEvent.ATTACHED ).isEmpty() );
    }

    @Test
    void aFailedRemovalEventLeavesTheBytes() throws SQLException {
        final WorkAttachmentStore good = fx.store.notifyStores().attachments();
        final Attachment a = good.add( item, null,
            JdbcWorkAttachmentsPostgresTest.file( "a.png", JdbcWorkAttachmentsPostgresTest.PNG ), Long.MAX_VALUE,
            WorkClaimFixtures.LENA );
        // statements: 1 lock the item, 2 mark removed, 3 delete the bytes, 4 the attachment_removed event
        final WorkAttachmentStore store = faulty( 4 );
        assertThrows( RuntimeException.class, () -> store.remove( item, a.id(), WorkClaimFixtures.LENA ) );
        assertEquals( 1, faultyHolder.rollbacks() );
        assertTrue( good.attachment( item.id(), a.id() ).isPresent() );
        assertArrayEquals( JdbcWorkAttachmentsPostgresTest.PNG, good.content( item.id(), a.id() ).orElseThrow().content() );
        assertTrue( fx.events( item, WorkEvent.ATTACHMENT_REMOVED ).isEmpty() );
    }
}
