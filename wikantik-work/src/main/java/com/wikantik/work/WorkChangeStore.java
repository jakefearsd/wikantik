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

import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * Change records. Reached through {@link WorkStore#changes()}. Every write runs in one transaction that locks the
 * change row, checks its state, lets the caller decide on the row as it is under that lock, updates it and
 * writes one event on the item. A change that is merged or abandoned refuses every write
 * ({@link WorkConflictException#CHANGE_CLOSED}).
 */
public interface WorkChangeStore {

    /** A decision taken on the locked row: it throws to refuse. */
    @FunctionalInterface
    interface Check {
        void on( Change locked );
    }

    /** How many changes of an item are live, and how many of those are in review. */
    record Counts( int live, int inReview ) {
    }

    /**
     * Opens a change on {@code item} against {@code productId}. With {@code mergedSha} the change is recorded as
     * merged directly. Refuses a closed item (validation, field {@code status}) and a second live change on the
     * same branch (validation, field {@code branch}).
     */
    Change open( Item item, long productId, ChangeDraft draft, Actor by ) throws SQLException;

    Optional< Change > change( long id ) throws SQLException;

    /** The item's changes, oldest first. */
    List< Change > forItem( long itemId ) throws SQLException;

    /** Sets the head commit and/or the review URL (a null leaves the field alone). A new head clears the approval. */
    Change update( Item item, long id, String headSha, String reviewUrl, Check check, Actor by ) throws SQLException;

    /** Open to in review; a change already in review is returned as it is. */
    Change requestReview( Item item, long id, Check check, Actor by ) throws SQLException;

    /** Records an approval of the current head, or sends the change back to open. Needs a change in review. */
    Change review( Item item, long id, boolean approved, String note, Check check, Actor by ) throws SQLException;

    /** Merges; {@code decide} names how, from the locked row, or throws to refuse. */
    Change merge( Item item, long id, String mergedSha, Function< Change, MergeKind > decide, Actor by )
        throws SQLException;

    Change abandon( Item item, long id, String note, Check check, Actor by ) throws SQLException;

    /** Whether the item has a change that is open or in review. */
    boolean hasLive( long itemId ) throws SQLException;

    /** Live-change counts by item id, for the items of one project that have any. */
    Map< Long, Counts > counts( long projectId ) throws SQLException;
}
