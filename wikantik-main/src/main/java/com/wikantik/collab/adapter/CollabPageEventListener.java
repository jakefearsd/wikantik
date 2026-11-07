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
package com.wikantik.collab.adapter;

import com.wikantik.api.managers.PageManager;
import com.wikantik.collab.CollabPageWriter;
import com.wikantik.collab.CollabSession;
import com.wikantik.collab.CollabSessionRegistry;
import com.wikantik.content.PageRenamer;
import com.wikantik.event.WikiEvent;
import com.wikantik.event.WikiEventListener;
import com.wikantik.event.WikiEventManager;
import com.wikantik.event.WikiPageEvent;
import com.wikantik.event.WikiPageRenameEvent;
import com.wikantik.event.WikiSecurityEvent;
import com.wikantik.filters.FilterManager;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Objects;

/**
 * Turns the engine's page and security events into {@link CollabSessionRegistry} calls (docs/MultiEdit.MD A1, A3, A5,
 * A13; ruling Q10). It lives in the adapter package because it touches engine event types; the registry stays
 * engine-free.
 *
 * <ul>
 *   <li>{@code POST_SAVE_END} (every save through the filters) and {@code POST_SAVE_BEGIN} (the only event the page
 *   directory watcher fires): the page's live session settles. Detection is by content hash, so the second event of a
 *   save merges nothing, an on-disk overwrite without a new version is still merged, and an event that arrives while a
 *   publish is in its save is queued and merged once it ends (A3). A session not in memory reconciles when it loads. A
 *   missing page freezes nothing: the watcher fires this event for a file delete too, and a {@code git mv} is not a
 *   delete.</li>
 *   <li>{@link WikiPageRenameEvent}: the renamed page's draft follows ({@link CollabSessionRegistry#rekey}); then every
 *   live session settles, because the renamer rewrites the referrers with {@code putPageText}, which fires no save
 *   event (A1).</li>
 *   <li>{@code PAGE_DELETED}: the draft is frozen, but only when the page really is gone. A rename fires no
 *   {@code PAGE_DELETED}; a delete event for a page that still exists (its delete failed) keeps the draft, with a
 *   WARN.</li>
 *   <li>{@code SESSION_EXPIRED} (its target is the wiki session): that session's participants are evicted with
 *   {@code signed-out}. {@code LOGOUT} (its target is null): every participant whose session no longer reads as signed
 *   in is. Under A13 the client keeps its edits and asks the user to log in.</li>
 *   <li>{@code GROUP_ADD} and {@code GROUP_REMOVE}: the next registry tick re-checks {@code edit} (E29).</li>
 * </ul>
 *
 * <p>Nothing escapes {@link #actionPerformed}: a {@link RuntimeException} is logged at WARN with the event and the page
 * and never reaches the thread that fired the event (a save, a rename, a logout).</p>
 */
public final class CollabPageEventListener implements WikiEventListener {

    private static final Logger LOG = LogManager.getLogger( CollabPageEventListener.class );

    private final CollabSessionRegistry registry;
    private final CollabPageWriter writer;

    public CollabPageEventListener( final CollabSessionRegistry registry, final CollabPageWriter writer ) {
        this.registry = Objects.requireNonNull( registry, "registry" );
        this.writer = Objects.requireNonNull( writer, "writer" );
    }

    /**
     * Listens on the page manager ({@code PAGE_DELETED}), the filter manager (the save events), the renamer (its rename
     * event is keyed on itself, never on the page manager) and each security source (the session monitor fires
     * {@code SESSION_EXPIRED}, the authentication manager {@code LOGOUT}, the group manager the group events), as
     * {@code OntologyEventListener.register} does. Null sources are skipped. {@link WikiEventManager} holds listeners
     * weakly, so the caller must keep a strong reference to this listener.
     */
    public void register( final PageManager pm, final FilterManager fm, final PageRenamer renamer,
                          final Object... securitySources ) {
        int sources = listenOn( pm ) + listenOn( fm ) + listenOn( renamer );
        if( securitySources != null ) {
            for( final Object source : securitySources ) {
                sources += listenOn( source );
            }
        }
        LOG.info( "Collab page event listener registered on {} event source(s)", sources );
    }

    private int listenOn( final Object source ) {
        if( source == null ) {
            return 0;
        }
        WikiEventManager.addWikiEventListener( source, this );
        return 1;
    }

    @Override
    public void actionPerformed( final WikiEvent event ) {
        try {
            if( event instanceof WikiPageRenameEvent rename ) {
                renamed( rename.getOldPageName(), rename.getNewPageName() );
            } else if( event instanceof WikiPageEvent page ) {
                pageEvent( page.getType(), page.getPageName() );
            } else if( event instanceof WikiSecurityEvent security ) {
                securityEvent( security.getType(), security.getTarget() );
            }
        } catch( final RuntimeException e ) {
            LOG.warn( "collab: handling {} for {} failed; the registry tick and the next load reconcile: {}",
                event.getTypeDescription(), pageOf( event ), e.toString(), e );
        }
    }

    private void pageEvent( final int type, final String pageName ) {
        if( pageName == null ) {
            return;
        }
        switch( type ) {
            case WikiPageEvent.POST_SAVE_BEGIN, WikiPageEvent.POST_SAVE_END ->
                registry.liveForPage( pageName ).ifPresent( CollabSession::settle );
            case WikiPageEvent.PAGE_DELETED -> deleted( pageName );
            default -> { /* not a collab concern */ }
        }
    }

    /** Rekey first, then every live session reconciles: the referrer rewrites fired no save event. */
    private void renamed( final String from, final String to ) {
        try {
            registry.rekey( from, to );
        } catch( final RuntimeException e ) {
            // remembered and retried by the registry (a refusal also froze the orphan); the settle still runs
            LOG.warn( "collab: the draft of {} did not follow its rename to {} yet: {}", from, to, e.toString() );
        }
        registry.settleAll();
    }

    /** Ruling Q10: only a true delete freezes. A delete that cannot be confirmed keeps the draft. */
    private void deleted( final String pageName ) {
        final boolean gone;
        try {
            gone = writer.latest( pageName ).isEmpty();
        } catch( final RuntimeException e ) {
            LOG.warn( "collab: PAGE_DELETED for {}, but the page store could not confirm it; keeping its draft: {}",
                pageName, e.toString() );
            return;
        }
        if( !gone ) {
            LOG.warn( "collab: PAGE_DELETED for {}, but the page still exists (its delete failed?); keeping its draft",
                pageName );
            return;
        }
        registry.freeze( pageName );
    }

    private void securityEvent( final int type, final Object target ) {
        switch( type ) {
            case WikiSecurityEvent.SESSION_EXPIRED -> registry.evictSignedOut( target );
            case WikiSecurityEvent.LOGOUT -> registry.evictSignedOutAll();
            case WikiSecurityEvent.GROUP_ADD, WikiSecurityEvent.GROUP_REMOVE -> registry.requestEditRecheck();
            default -> { /* not a collab concern */ }
        }
    }

    private static String pageOf( final WikiEvent event ) {
        if( event instanceof WikiPageRenameEvent rename ) {
            return rename.getOldPageName() + " -> " + rename.getNewPageName();
        }
        return event instanceof WikiPageEvent page ? page.getPageName() : "(no page)";
    }
}
