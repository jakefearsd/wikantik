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
package com.wikantik.connectors;

import com.wikantik.api.connectors.SourceItem;
import com.wikantik.api.connectors.SyncBatch;
import com.wikantik.api.connectors.SyncCursor;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SyncBatchesTest {

    private static final SyncCursor INPUT = new SyncCursor( "prev" );

    private static SourceItem item( final String uri ) {
        return new SourceItem( uri, new byte[0], "text/plain", java.util.Map.of(), List.of(), "h" );
    }

    @Test
    void trustedFinishAdvancesCursorToItemCountAndIsComplete() {
        final List< SourceItem > items = List.of( item( "a" ), item( "b" ) );
        final SyncBatch batch = SyncBatches.finish( items, true, INPUT );
        assertEquals( items, batch.items() );
        assertTrue( batch.tombstonedUris().isEmpty() );
        assertEquals( "2", batch.nextCursor().value() );
        assertTrue( batch.complete() );
    }

    @Test
    void untrustedFinishKeepsInputCursorAndIsIncomplete() {
        final List< SourceItem > items = List.of( item( "a" ) );
        final SyncBatch batch = SyncBatches.finish( items, false, INPUT );
        assertEquals( items, batch.items() );
        assertTrue( batch.tombstonedUris().isEmpty() );
        assertSame( INPUT, batch.nextCursor() );
        assertFalse( batch.complete() );
    }

    @Test
    void failedIsEmptyIncompleteAndKeepsInputCursor() {
        final SyncBatch batch = SyncBatches.failed( INPUT );
        assertTrue( batch.items().isEmpty() );
        assertTrue( batch.tombstonedUris().isEmpty() );
        assertSame( INPUT, batch.nextCursor() );
        assertFalse( batch.complete() );
    }
}
