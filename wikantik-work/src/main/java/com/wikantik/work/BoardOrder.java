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

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.sql.SQLException;
import java.util.Locale;
import java.util.Map;

/** Where a moved card lands in its column: resolving the card it goes before, and settling the column's ranks. */
final class BoardOrder {

    private static final Logger LOG = LogManager.getLogger( BoardOrder.class );

    private final WorkStore store;

    BoardOrder( final WorkStore store ) {
        this.store = store;
    }

    /**
     * The card a move asks to be placed before: it must be another card of the moved card's project and target
     * column. An unknown key, another project's and another column's are indistinguishable by design. Null when the
     * patch names none.
     */
    Long beforeCard( final Item moved, final Map< String, Object > changes ) throws SQLException {
        if ( !changes.containsKey( "before" ) ) {
            return null;
        }
        final String key = ItemPatchApplier.beforeKey( changes ).trim().toUpperCase( Locale.ROOT );
        final Item before = ItemKeys.PATTERN.matcher( key ).matches() ? store.item( key ).orElse( null ) : null;
        if ( before == null || before.projectId() != moved.projectId() || before.status() != moved.status()
                || before.id() == moved.id() ) {
            throw new WorkValidationException( "before", "The card to place this before is not in that column." );
        }
        return before.id();
    }

    /**
     * Renumbers the column when the move needs it and returns the item as stored. A failure is logged and the saved
     * move returned: it already committed, and the order heals on the next move.
     */
    Item settle( final Item saved, final Map< String, Object > changes, final Long beforeId ) {
        if ( changes.keySet().stream().noneMatch( ItemPatchApplier.MOVE_KEYS::contains ) ) {
            return saved;
        }
        try {
            if ( store.renumberColumn( saved.projectId(), saved.status(), saved.id(), beforeId ) ) {
                return store.itemById( saved.id() ).orElse( saved );
            }
        } catch ( final SQLException e ) {
            LOG.warn( "Renumbering the board column failed after the move was saved (project {}, status {}, item {}): {}",
                saved.projectId(), saved.status(), saved.key(), e.getMessage(), e );
        }
        return saved;
    }
}
