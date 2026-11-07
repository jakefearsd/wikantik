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
import com.wikantik.api.exceptions.ProviderException;
import com.wikantik.api.exceptions.WikiException;
import com.wikantik.api.pages.SaveGuard;
import com.wikantik.api.pages.SaveOutcome;
import com.wikantik.api.pages.SavedButPostSaveFailedException;
import com.wikantik.api.providers.PageProvider;
import com.wikantik.auth.AuthorKeys;
import com.wikantik.auth.acl.AccessRuleShapeCheck;
import com.wikantik.core.subsystem.CoreSubsystemBridge;
import com.wikantik.filters.FilterManager;
import com.wikantik.search.SearchManager;
import com.wikantik.util.TextUtil;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;


/**
 * Default implementation of {@link PageLifecycle}.
 *
 * <p>All logic is moved verbatim from {@code DefaultPageManager#saveText}
 * in Phase 5 Checkpoint 3 of the wikantik-main subsystem decomposition.</p>
 */
public class DefaultPageLifecycle implements PageLifecycle {

    private static final Logger LOG = LogManager.getLogger( DefaultPageLifecycle.class );

    private final Engine               engine;
    private final PageRepository       repository;
    private final AccessRuleShapeCheck accessRuleShapes;

    /**
     * @param engine     Engine instance (used for lazy manager lookups)
     * @param repository PageRepository to call {@code putPageText} / {@code wikiPageExists} on
     */
    public DefaultPageLifecycle( final Engine engine, final PageRepository repository ) {
        this.engine     = engine;
        this.repository = repository;
        this.accessRuleShapes = new AccessRuleShapeCheck(
                name -> repository.wikiPageExists( name ) ? repository.getPureText( name, PageProvider.LATEST_VERSION ) : null );
        if( repository instanceof DefaultPageRepository defaultRepository ) {
            defaultRepository.lockProviderWritesWith( this::lockedProviderWrite );
        }
    }

    /** A provider delete of {@code name} under its page lock (see {@link DefaultPageRepository#lockProviderWritesWith}). */
    private void lockedProviderWrite( final String name, final DefaultPageRepository.ProviderWrite write )
            throws ProviderException {
        final PageLock pl = acquire( name );
        try {
            write.run();
        } finally {
            release( name, pl );
        }
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

    /** A per-page lock, reference-counted so the registry does not grow with every page name ever saved. */
    private static final class PageLock {
        final ReentrantLock lock = new ReentrantLock();
        int refs;
    }

    private final ConcurrentHashMap< String, PageLock > pageLocks = new ConcurrentHashMap<>();

    private PageLock acquire( final String name ) {
        final PageLock pl = pageLocks.compute( name, ( k, v ) -> {
            final PageLock l = v == null ? new PageLock() : v;
            l.refs++;
            return l;
        } );
        pl.lock.lock();
        return pl;
    }

    private void release( final String name, final PageLock pl ) {
        pl.lock.unlock();
        pageLocks.computeIfPresent( name, ( k, v ) -> --v.refs == 0 ? null : v );
    }

    /** Number of page locks currently registered (held or awaited); 0 when no save is in flight. Test seam. */
    int lockCount() {
        return pageLocks.size();
    }

    /** Number of threads waiting for the lock of {@code name} (0 when it is free or unregistered). Test seam. */
    int waitingOn( final String name ) {
        final PageLock pl = pageLocks.get( name );
        return pl == null ? 0 : pl.lock.getQueueLength();
    }

    @Override
    public void saveText( final Context context, final String text ) throws WikiException {
        saveText( context, text, SaveGuard.NONE );
    }

    /**
     * The guard is evaluated twice: once before the pre-save filters, so a stale save fails fast without running them
     * (filters such as the spam filter count every attempt), and again under the page lock immediately before the
     * write, which is the authoritative check. The identical-text comparison and the latest read reported for an
     * unchanged outcome also happen under the lock, so an unchanged outcome names the version whose text matched.
     */
    @Override
    public SaveOutcome saveText( final Context context, final String text, final SaveGuard guard ) throws WikiException {
        final Page page = context.getPage();
        final String name = page.getName();
        final String proposedText = TextUtil.normalizePostData( text );

        // Under the lock: bail on identical text, and fail fast on a stale guard before running any filter.
        final SaveOutcome early = unchangedOrPrecheck( page, proposedText, text, guard );
        if ( early != null ) {
            return early;
        }

        // Set the page author: the login (the stable storage key), never the session's full name; PublicIdentity turns it
        // into the current display name when it is shown.
        final String authorKey = AuthorKeys.authorKeyFor( context.getWikiSession() );
        if ( page.getAuthor() == null && authorKey != null ) {
            page.setAuthor( authorKey );
        }

        // Run pre-save filters. They depend on the input text, not on the stored page, so they run before the lock.
        final String saveText = getFilterManager().doPreSaveFiltering( context, proposedText );

        // The per-page lock covers guard check, access-rule check, write and reading back the written version. Post-save
        // filters, reindex and events run after unlock so a listener that saves another page cannot deadlock.
        final int written;
        final PageLock pl = acquire( name );
        try {
            guard.check( repository.getPage( name ), repository.getPureText( name, PageProvider.LATEST_VERSION ) );

            // Refuse a text that adds an access rule the wiki cannot read (it would lock the page, #104). Checked here, on
            // the text putPageText stores, and not as a filter in the chain: filters rewrite the text (the structural
            // spine and the frontmatter defaults re-emit the frontmatter, decoding a YAML escape into a literal rule),
            // and no filter priority is sure to run after every other filter, whichever optional subsystems are wired.
            accessRuleShapes.check( name, saveText );

            repository.putPageText( page, saveText );

            final Page latest = repository.getPage( name );
            written = latest == null ? 0 : latest.getVersion();
        } finally {
            release( name, pl );
        }

        final SaveOutcome outcome = new SaveOutcome( repository.getPage( name, written ), written, false );
        try {
            // Post-save filters see the text as stored (after the pre-save filters).
            getFilterManager().doPostSaveFiltering( context, saveText );

            // Reindex the saved page
            page.setVersion( PageProvider.LATEST_VERSION );
            getSearchManager().reindexPage( page );
        } catch( final WikiException | RuntimeException e ) {
            // The page is stored: the caller still learns which version this call wrote (ruling I2).
            LOG.warn( "Page '{}' was saved as version {}, but the handling after the save failed: {}", name, written,
                    e.toString(), e );
            throw new SavedButPostSaveFailedException( name, outcome, e );
        }
        return outcome;
    }

    @Override
    public void putPageText( final Page page, final String text ) throws ProviderException {
        if( page == null || page.getName() == null || page.getName().isEmpty() ) {
            repository.putPageText( page, text );   // refuses the illegal name; there is no page lock to take
            return;
        }
        final String name = page.getName();
        final PageLock pl = acquire( name );
        try {
            repository.putPageText( page, text );
        } finally {
            release( name, pl );
        }
    }

    @Override
    public < T > T underPageLock( final String pageName, final PageLockedAction< T > action ) throws WikiException {
        final PageLock pl = acquire( pageName );
        try {
            return action.run();
        } finally {
            release( pageName, pl );
        }
    }

    @Override
    public < T > T underPageLocks( final String first, final String second, final PageLockedAction< T > action )
            throws WikiException {
        if( first.equals( second ) ) {
            return underPageLock( first, action );
        }
        final boolean inOrder = first.compareTo( second ) < 0;
        final String low = inOrder ? first : second;
        final String high = inOrder ? second : first;
        return underPageLock( low, () -> underPageLock( high, action ) );
    }

    /**
     * Under the page lock: the unchanged outcome when the text is identical to the stored one (or an empty page would
     * be created and that is not allowed), otherwise {@code null} after a fail-fast evaluation of {@code guard}. The
     * guard is checked in both cases, so a stale strict save of identical text still conflicts.
     */
    private SaveOutcome unchangedOrPrecheck( final Page page, final String proposedText, final String rawText,
                                             final SaveGuard guard ) throws WikiException {
        final String name = page.getName();
        final PageLock pl = acquire( name );
        try {
            final Page latest = repository.getPage( name );
            guard.check( latest, repository.getPureText( name, PageProvider.LATEST_VERSION ) );
            final String oldText = repository.getPureText( name, page.getVersion() );
            final boolean identical = oldText != null && oldText.equals( proposedText );
            if ( identical || !allowEmpty() && !repository.wikiPageExists( name, page.getVersion() ) && rawText.isBlank() ) {
                return new SaveOutcome( latest, latest == null ? 0 : latest.getVersion(), true );
            }
            return null;
        } finally {
            release( name, pl );
        }
    }

    private boolean allowEmpty() {
        return TextUtil.getBooleanProperty( CoreSubsystemBridge.fromLegacyEngine( engine ).properties().asProperties(),
                Engine.PROP_ALLOW_CREATION_OF_EMPTY_PAGES, false );
    }
}
