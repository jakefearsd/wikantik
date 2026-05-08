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
package com.wikantik.page.subsystem.lifecycle;

import com.wikantik.api.core.Context;
import com.wikantik.api.core.Engine;
import com.wikantik.api.core.Page;
import com.wikantik.api.exceptions.WikiException;
import com.wikantik.core.subsystem.CoreSubsystemBridge;
import com.wikantik.filters.FilterManager;
import com.wikantik.search.SearchManager;
import com.wikantik.util.TextUtil;


/**
 * Default implementation of {@link PageLifecycle}.
 *
 * <p>All logic is moved verbatim from {@code DefaultPageManager#saveText}
 * in Phase 5 Checkpoint 3 of the wikantik-main subsystem decomposition.</p>
 */
public class DefaultPageLifecycle implements PageLifecycle {


    private final Engine         engine;
    private final PageRepository repository;

    /**
     * @param engine     Engine instance (used for lazy manager lookups)
     * @param repository PageRepository to call {@code putPageText} / {@code wikiPageExists} on
     */
    public DefaultPageLifecycle( final Engine engine, final PageRepository repository ) {
        this.engine     = engine;
        this.repository = repository;
    }

    // --- Lazy accessors for managers initialised after PageManager ---

    private SearchManager getSearchManager() {
        return com.wikantik.search.subsystem.SearchSubsystemBridge.fromLegacyEngine( engine ).searchManager();
    }

    private FilterManager getFilterManager() {
        return com.wikantik.render.subsystem.RenderingSubsystemBridge.fromLegacyEngine( engine ).filterManager();
    }

    // -------------------------------------------------------------------------
    // PageLifecycle implementation
    // -------------------------------------------------------------------------

    @Override
    public void saveText( final Context context, final String text ) throws WikiException {
        // Check if page data actually changed; bail if not
        final Page page = context.getPage();
        final String oldText = repository.getPureText( page.getName(), page.getVersion() );
        final String proposedText = TextUtil.normalizePostData( text );
        if ( oldText != null && oldText.equals( proposedText ) ) {
            return;
        }

        // Check if creation of empty pages is allowed; bail if not
        final boolean allowEmpty = TextUtil.getBooleanProperty(
                CoreSubsystemBridge.fromLegacyEngine( engine ).properties().asProperties(),
                Engine.PROP_ALLOW_CREATION_OF_EMPTY_PAGES,
                false );
        if ( !allowEmpty && !repository.wikiPageExists( page.getName(), page.getVersion() ) && text.isBlank() ) {
            return;
        }

        // Set the page author
        final Page page2 = context.getPage();
        if ( page2.getAuthor() == null && context.getCurrentUser() != null ) {
            page2.setAuthor( context.getCurrentUser().getName() );
        }

        // Run pre-save filters
        final String saveText = getFilterManager().doPreSaveFiltering( context, proposedText );

        // Save the page text
        repository.putPageText( page, saveText );

        // Refresh the context for post-save filtering
        repository.getPage( page.getName() );
        getFilterManager().doPostSaveFiltering( context, saveText );

        // Reindex the saved page
        page.setVersion( com.wikantik.api.providers.PageProvider.LATEST_VERSION );
        getSearchManager().reindexPage( page );
    }
}
