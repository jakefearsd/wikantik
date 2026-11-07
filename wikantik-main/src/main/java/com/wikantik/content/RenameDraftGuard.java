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
package com.wikantik.content;

import com.wikantik.api.core.Engine;
import com.wikantik.api.exceptions.WikiException;
import com.wikantik.collab.CollabSessionRegistry;
import com.wikantik.collab.adapter.CollabEngineAccess;
import com.wikantik.page.subsystem.PageSubsystemBridge;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * The checks {@link DefaultPageRenamer} runs before any write. The source must exist; it is reported before the draft
 * guard, and {@link RenameMove} checks again under the locks. The target must hold no collaborative draft.
 *
 * <p>A draft is refused whether it is live, stored or frozen. The store keeps one draft per page, so the moved page's own
 * draft could not follow it (MultiEdit §0 Minor). A live new-page draft there would also take the moved page's body as
 * an outside change. The live sessions in memory are checked first: that needs no store, so the guard refuses even while
 * the store is down. The store is checked next. A store that cannot tell refuses the rename (try again later) rather
 * than risk mixing the moved page into a draft the guard could not see.</p>
 */
final class RenameDraftGuard {

    private static final Logger LOG = LogManager.getLogger( RenameDraftGuard.class );

    private RenameDraftGuard() {
    }

    static void check( final Engine engine, final String renameFrom, final String renameTo ) throws WikiException {
        if( PageSubsystemBridge.fromLegacyEngine( engine ).pages().getPage( renameFrom ) == null ) {
            throw new WikiException( "No such page " + renameFrom );
        }
        final CollabSessionRegistry registry = CollabEngineAccess.registry( engine );
        if( registry == null ) {
            return;
        }
        if( registry.liveForPage( renameTo ).isPresent() ) {
            throw draftThere( renameTo );
        }
        final boolean draft;
        try {
            draft = registry.hasDraft( renameTo );
        } catch( final RuntimeException e ) {
            LOG.warn( "Rename to '{}' refused: the collaboration store could not tell whether a draft is there: {}",
                    renameTo, e.getMessage(), e );
            throw new WikiException( "Page '" + renameTo + "' cannot be checked for a collaborative draft: the"
                    + " collaboration store is unavailable; try again", e );
        }
        if( draft ) {
            throw draftThere( renameTo );
        }
    }

    private static WikiException draftThere( final String renameTo ) {
        return new WikiException( "Page '" + renameTo + "' has an unpublished collaborative draft; publish or discard it"
                + " first" );
    }
}
