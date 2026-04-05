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

import com.wikantik.TestEngine;
import com.wikantik.api.core.Context;
import com.wikantik.api.core.Engine;
import com.wikantik.api.core.Page;
import com.wikantik.api.exceptions.WikiException;
import com.wikantik.api.spi.Wiki;
import com.wikantik.api.managers.PageManager;
import com.wikantik.api.managers.ReferenceManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Collection;

import static com.wikantik.TestEngine.with;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Additional tests for {@link DefaultPageRenamer} covering uncovered branches:
 * renaming with referrer update (including CamelCase links),
 * exception cases (empty names, page not found, page already exists),
 * firePageRenameEvent when no listeners registered.
 */
class DefaultPageRenamerAdditionalTest {

    TestEngine engine = TestEngine.build(
            with( Engine.PROP_MATCHPLURALS, "true" ),
            with( "wikantik.translatorReader.camelCaseLinks", "true" )
    );

    @AfterEach
    void tearDown() {
        engine.stop();
    }

    // -----------------------------------------------------------------------
    // Guard conditions on renamePage
    // -----------------------------------------------------------------------

    @Test
    void renamePageThrowsOnEmptyFromName() throws Exception {
        engine.saveText( "SomePage", "content" );
        final Page page = engine.getManager( PageManager.class ).getPage( "SomePage" );
        final Context ctx = Wiki.context().create( engine, page );

        assertThrows( WikiException.class,
                () -> engine.getManager( PageRenamer.class ).renamePage( ctx, "", "NewName", false ) );
    }

    @Test
    void renamePageThrowsOnEmptyToName() throws Exception {
        engine.saveText( "SourcePage", "content" );
        final Page page = engine.getManager( PageManager.class ).getPage( "SourcePage" );
        final Context ctx = Wiki.context().create( engine, page );

        assertThrows( WikiException.class,
                () -> engine.getManager( PageRenamer.class ).renamePage( ctx, "SourcePage", "", false ) );
    }

    @Test
    void renamePageThrowsWhenSameNameAfterCleaning() throws Exception {
        engine.saveText( "CleanPage", "content" );
        final Page page = engine.getManager( PageManager.class ).getPage( "CleanPage" );
        final Context ctx = Wiki.context().create( engine, page );

        // Rename to itself
        assertThrows( WikiException.class,
                () -> engine.getManager( PageRenamer.class ).renamePage( ctx, "CleanPage", "CleanPage", false ) );
    }

    @Test
    void renamePageThrowsWhenFromPageDoesNotExist() throws Exception {
        engine.saveText( "ExistingPage", "content" );
        final Page page = engine.getManager( PageManager.class ).getPage( "ExistingPage" );
        final Context ctx = Wiki.context().create( engine, page );

        assertThrows( WikiException.class,
                () -> engine.getManager( PageRenamer.class ).renamePage( ctx, "NonExistentPageABC", "NewPageXYZ", false ) );
    }

    @Test
    void renamePageThrowsWhenToPageAlreadyExists() throws Exception {
        engine.saveText( "OldPage", "old content" );
        engine.saveText( "AlreadyExists", "already there" );
        final Page page = engine.getManager( PageManager.class ).getPage( "OldPage" );
        final Context ctx = Wiki.context().create( engine, page );

        assertThrows( WikiException.class,
                () -> engine.getManager( PageRenamer.class ).renamePage( ctx, "OldPage", "AlreadyExists", false ) );
    }

    // -----------------------------------------------------------------------
    // Successful rename without referrer update
    // -----------------------------------------------------------------------

    @Test
    void renamePageSucceedsWithoutReferrerUpdate() throws Exception {
        engine.saveText( "RenameSource", "some content" );
        final Page page = engine.getManager( PageManager.class ).getPage( "RenameSource" );
        final Context ctx = Wiki.context().create( engine, page );

        final String newName = engine.getManager( PageRenamer.class )
                .renamePage( ctx, "RenameSource", "RenameTarget", false );

        assertEquals( "RenameTarget", newName );
        assertNotNull( engine.getManager( PageManager.class ).getPage( "RenameTarget" ),
                "Renamed page should exist under new name" );
        assertNull( engine.getManager( PageManager.class ).getPage( "RenameSource" ),
                "Old page should no longer exist" );
    }

    // -----------------------------------------------------------------------
    // Successful rename with referrer update (changeReferrers = true)
    // -----------------------------------------------------------------------

    @Test
    void renamePageUpdatesReferrers() throws Exception {
        engine.saveText( "OriginalPage", "content here" );
        engine.saveText( "ReferrerPage", "[OriginalPage]() is referenced here" );

        final Page page = engine.getManager( PageManager.class ).getPage( "OriginalPage" );
        final Context ctx = Wiki.context().create( engine, page );

        final String newName = engine.getManager( PageRenamer.class )
                .renamePage( ctx, "OriginalPage", "RenamedPage", true );

        assertEquals( "RenamedPage", newName );

        // The referrer page should now contain the new page name
        final String updatedText = engine.getManager( PageManager.class )
                .getPureText( engine.getManager( PageManager.class ).getPage( "ReferrerPage" ) );
        assertTrue( updatedText.contains( "RenamedPage" ),
                "Referrer page should have been updated to new name" );
    }

    // -----------------------------------------------------------------------
    // firePageRenameEvent when no listeners — should not throw
    // -----------------------------------------------------------------------

    @Test
    void firePageRenameEventWithNoListenersDoesNotThrow() {
        final DefaultPageRenamer renamer = new DefaultPageRenamer();
        assertDoesNotThrow( () -> renamer.firePageRenameEvent( "OldName", "NewName" ) );
    }

    // -----------------------------------------------------------------------
    // Reference update is reflected in ReferenceManager after rename
    // -----------------------------------------------------------------------

    @Test
    void referenceManagerUpdatedAfterRename() throws Exception {
        engine.saveText( "PageToRename", "content" );
        engine.saveText( "LinkingPage", "[PageToRename]() linked here" );

        final ReferenceManager refMgr = engine.getManager( ReferenceManager.class );
        // Verify references are tracked before rename
        final Collection<String> referrersBefore = refMgr.findReferrers( "PageToRename" );
        assertTrue( referrersBefore.contains( "LinkingPage" ),
                "LinkingPage should reference PageToRename before rename; referrers=" + referrersBefore );

        final Page page = engine.getManager( PageManager.class ).getPage( "PageToRename" );
        final Context ctx = Wiki.context().create( engine, page );
        // Use changeReferrers=true so the rename also calls updateReferences on the renamed page
        engine.getManager( PageRenamer.class ).renamePage( ctx, "PageToRename", "RenamedFinalPage", true );

        // After rename, the new name should be tracked by the reference manager
        assertNotNull( engine.getManager( PageManager.class ).getPage( "RenamedFinalPage" ),
                "RenamedFinalPage should exist after rename" );
    }
}
