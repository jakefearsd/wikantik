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

import com.wikantik.api.core.Attachment;
import com.wikantik.api.core.Context;
import com.wikantik.api.core.Engine;
import com.wikantik.api.core.Page;
import com.wikantik.api.exceptions.ProviderException;
import com.wikantik.api.exceptions.WikiException;
import com.wikantik.api.managers.PageManager;
import com.wikantik.auth.AuthorKeys;
import com.wikantik.page.subsystem.PageSubsystem;
import com.wikantik.page.subsystem.PageSubsystemBridge;
import com.wikantik.page.subsystem.lifecycle.PageLifecycle;
import com.wikantik.pagegraph.subsystem.PageGraphSubsystemBridge;

import java.util.List;
import java.util.Set;
import java.util.function.Function;

/**
 * The part of a rename that moves the page, run holding the save locks of the old and the new name (taken in name order,
 * so two renames cannot deadlock): the existence checks, the read of the text, the move and the re-write under the new
 * name. A save of the old page therefore lands either before the read (its text moves with the page) or after the move
 * (it finds no page, or recreates it under the old name as any save of a missing page does), never between the read and
 * the re-write, where the re-write would revert it.
 */
final class RenameMove {

    /** What the move did: the page before and after, and the referrers of the old and the new name before the move. */
    record Moved( Page fromPage, Page toPage, Set< String > referrers, Set< String > newNameReferrers ) { }

    private RenameMove() {
    }

    /**
     * @param referrersOf the pages whose links to the old page (or its attachments) the rename rewrites
     */
    static Moved move( final Context context, final String renameFrom, final String renameTo,
                       final Function< Page, Set< String > > referrersOf ) throws WikiException {
        final PageLifecycle lifecycle = PageSubsystemBridge.fromLegacyEngine( context.getEngine() ).pageLifecycle();
        return PageLifecycle.underPageLocks( lifecycle, renameFrom, renameTo,
                () -> moveLocked( context, renameFrom, renameTo, referrersOf ) );
    }

    private static Moved moveLocked( final Context context, final String renameFrom, final String renameTo,
                                     final Function< Page, Set< String > > referrersOf ) throws WikiException {
        //  Preconditions: "from" page must exist, and "to" page must not yet exist.
        final Engine engine = context.getEngine();
        final PageSubsystem.Services pages = PageSubsystemBridge.fromLegacyEngine( engine );
        final PageManager pageManager = pages.pages();
        final Page fromPage = pageManager.getPage( renameFrom );
        if( fromPage == null ) {
            throw new WikiException( "No such page " + renameFrom );
        }
        if( pageManager.getPage( renameTo ) != null ) {
            throw new WikiException( "Page already exists " + renameTo );
        }

        // Read the text before anything moves: the rename re-saves it under the new name, and a text that cannot be read
        // must abort the rename with nothing changed — never be re-saved as "" (getPureText answers "" on a provider error).
        final String pageText = RenameTextReader.textOf( engine, renameFrom );

        final Set< String > referrers = referrersOf.apply( fromPage );
        final Set< String > newNameReferrers = RenameRenderEviction.referrersOf( engine, renameTo );

        //  Remove references to attachments under old name
        final List< Attachment > attachmentsOldName = pages.attachments().listAttachments( fromPage );
        for( final Attachment att : attachmentsOldName ) {
            final Page fromAttPage = pageManager.getPage( att.getName() );
            PageGraphSubsystemBridge.fromLegacyEngine( engine ).referenceManager().pageRemoved( fromAttPage );
        }

        pageManager.getProvider().movePage( renameFrom, renameTo );
        if( pages.attachments().attachmentsEnabled() ) {
            pages.attachments().getCurrentProvider().moveAttachmentsForPage( renameFrom, renameTo );
        }

        //  Add a comment to the page notifying what changed.  This adds a new revision to the repo with no actual change.
        final Page toPage = pageManager.getPage( renameTo );
        if( toPage == null ) {
            throw new ProviderException( "Rename seems to have failed for some strange reason - please check logs!" );
        }
        toPage.setAttribute( Page.CHANGENOTE, fromPage.getName() + " ==> " + toPage.getName() );
        toPage.setAuthor( AuthorKeys.authorKeyFor( context.getWikiSession() ) );
        pageManager.putPageText( toPage, pageText );
        return new Moved( fromPage, toPage, referrers, newNameReferrers );
    }
}
