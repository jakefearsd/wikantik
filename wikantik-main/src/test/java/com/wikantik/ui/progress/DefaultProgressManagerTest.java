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
package com.wikantik.ui.progress;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Tests for {@link DefaultProgressManager}.
 */
public class DefaultProgressManagerTest {

    private DefaultProgressManager progressManager;

    /**
     * A concrete {@link ProgressItem} subclass for testing that allows
     * the progress value to be controlled externally.
     */
    private static class TestProgressItem extends ProgressItem {

        private int progress;

        TestProgressItem( final int progress ) {
            this.progress = progress;
        }

        void setProgress( final int progress ) {
            this.progress = progress;
        }

        @Override
        public int getProgress() {
            return progress;
        }
    }

    @BeforeEach
    void setUp() {
        progressManager = new DefaultProgressManager();
    }

    // --- Identifier uniqueness ---

    @Test
    void testGetNewProgressIdentifierReturnsNonNull() {
        final String id = progressManager.getNewProgressIdentifier();
        assertNotNull( id );
    }

    @Test
    void testGetNewProgressIdentifierReturnsValidUUID() {
        final String id = progressManager.getNewProgressIdentifier();
        // UUID.fromString will throw if the format is invalid
        assertDoesNotThrow( () -> UUID.fromString( id ) );
    }

    @Test
    void testGetNewProgressIdentifierReturnsUniqueValues() {
        final String id1 = progressManager.getNewProgressIdentifier();
        final String id2 = progressManager.getNewProgressIdentifier();
        assertNotEquals( id1, id2 );
    }

    @Test
    void testGetNewProgressIdentifierUniquenessAcrossMany() {
        final Set< String > ids = new HashSet<>();
        for( int i = 0; i < 100; i++ ) {
            ids.add( progressManager.getNewProgressIdentifier() );
        }
        assertEquals( 100, ids.size(), "All 100 generated identifiers should be unique" );
    }

    // --- Start/stop lifecycle ---

    @Test
    void testStartProgressSetsStateToStarted() {
        final TestProgressItem item = new TestProgressItem( 0 );
        assertEquals( ProgressItem.CREATED, item.getState(), "Item should initially be in CREATED state" );

        progressManager.startProgress( item, "test-id-1" );
        assertEquals( ProgressItem.STARTED, item.getState() );
    }

    @Test
    void testAfterStartProgressGetProgressReturnsItemValue() {
        final TestProgressItem item = new TestProgressItem( 42 );
        final String id = progressManager.getNewProgressIdentifier();

        progressManager.startProgress( item, id );
        assertEquals( 42, progressManager.getProgress( id ) );
    }

    @Test
    void testStopProgressSetsStateToStopped() {
        final TestProgressItem item = new TestProgressItem( 50 );
        final String id = "stop-test-id";

        progressManager.startProgress( item, id );
        assertEquals( ProgressItem.STARTED, item.getState() );

        progressManager.stopProgress( id );
        assertEquals( ProgressItem.STOPPED, item.getState() );
    }

    @Test
    void testAfterStopProgressGetProgressThrows() {
        final TestProgressItem item = new TestProgressItem( 50 );
        final String id = "stop-then-get-id";

        progressManager.startProgress( item, id );
        progressManager.stopProgress( id );

        assertThrows( IllegalArgumentException.class, () -> progressManager.getProgress( id ) );
    }

    // --- Progress tracking ---

    @Test
    void testGetProgressReturnsCurrentValue() {
        final TestProgressItem item = new TestProgressItem( 0 );
        final String id = "progress-tracking-id";
        progressManager.startProgress( item, id );

        assertEquals( 0, progressManager.getProgress( id ) );

        item.setProgress( 25 );
        assertEquals( 25, progressManager.getProgress( id ) );

        item.setProgress( 75 );
        assertEquals( 75, progressManager.getProgress( id ) );

        item.setProgress( 100 );
        assertEquals( 100, progressManager.getProgress( id ) );
    }

    @Test
    void testGetProgressReflectsChangingValues() {
        final TestProgressItem item = new TestProgressItem( 10 );
        final String id = "changing-progress-id";
        progressManager.startProgress( item, id );

        for( int i = 0; i <= 100; i += 10 ) {
            item.setProgress( i );
            assertEquals( i, progressManager.getProgress( id ),
                    "Progress should reflect the current value of " + i );
        }
    }

    // --- Error cases ---

    @Test
    void testGetProgressWithUnknownIdThrowsIllegalArgumentException() {
        assertThrows( IllegalArgumentException.class,
                () -> progressManager.getProgress( "nonexistent-id" ) );
    }

    @Test
    void testStopProgressWithUnknownIdIsNoOp() {
        assertDoesNotThrow( () -> progressManager.stopProgress( "nonexistent-id" ) );
    }

    // --- Concurrent access ---

    @Test
    void testMultipleProgressItemsTrackedSimultaneously() {
        final TestProgressItem item1 = new TestProgressItem( 10 );
        final TestProgressItem item2 = new TestProgressItem( 50 );
        final TestProgressItem item3 = new TestProgressItem( 90 );

        final String id1 = progressManager.getNewProgressIdentifier();
        final String id2 = progressManager.getNewProgressIdentifier();
        final String id3 = progressManager.getNewProgressIdentifier();

        progressManager.startProgress( item1, id1 );
        progressManager.startProgress( item2, id2 );
        progressManager.startProgress( item3, id3 );

        assertEquals( 10, progressManager.getProgress( id1 ) );
        assertEquals( 50, progressManager.getProgress( id2 ) );
        assertEquals( 90, progressManager.getProgress( id3 ) );

        // Modify individually and verify isolation
        item1.setProgress( 20 );
        assertEquals( 20, progressManager.getProgress( id1 ) );
        assertEquals( 50, progressManager.getProgress( id2 ) );
        assertEquals( 90, progressManager.getProgress( id3 ) );

        // Stop one and verify others remain
        progressManager.stopProgress( id2 );
        assertEquals( 20, progressManager.getProgress( id1 ) );
        assertThrows( IllegalArgumentException.class, () -> progressManager.getProgress( id2 ) );
        assertEquals( 90, progressManager.getProgress( id3 ) );
    }

    @Test
    void testStopProgressDoesNotAffectOtherItems() {
        final TestProgressItem item1 = new TestProgressItem( 30 );
        final TestProgressItem item2 = new TestProgressItem( 60 );

        final String id1 = "item-1";
        final String id2 = "item-2";

        progressManager.startProgress( item1, id1 );
        progressManager.startProgress( item2, id2 );

        progressManager.stopProgress( id1 );

        assertEquals( ProgressItem.STOPPED, item1.getState() );
        assertEquals( ProgressItem.STARTED, item2.getState() );
        assertEquals( 60, progressManager.getProgress( id2 ) );
    }

}
