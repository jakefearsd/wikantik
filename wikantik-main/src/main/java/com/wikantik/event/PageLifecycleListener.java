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
package com.wikantik.event;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Template Method base for listeners that react to page lifecycle events: a save, a delete or a rename.
 *
 * <p>{@link #actionPerformed} fixes the dispatch order. {@link WikiPageRenameEvent} is a subclass of
 * {@link WikiPageEvent}, so it is tested first or a rename would be read as a plain page event. Of the
 * page events only {@link WikiPageEvent#POST_SAVE_END} (to {@link #onSaved}) and
 * {@link WikiPageEvent#PAGE_DELETED} (to {@link #onDeleted}) are routed. The bare
 * {@link WikiPageEvent#POST_SAVE} is deliberately ignored: a {@code PageEventFilter} fires it as well as
 * the filter manager firing {@code POST_SAVE_END}, so handling both would run a save's work twice.
 * Anything a hook throws is logged once at WARN and never propagates to the code that fired the event.</p>
 *
 * <p>Registration sources matter, because events are keyed on their source: save events come from the
 * {@code FilterManager} ({@code doPostSaveFiltering}), {@code PAGE_DELETED} from the {@code PageManager},
 * and the rename event from {@code DefaultPageRenamer} keyed on itself, never on the {@code PageManager}.
 * A listener that wants renames must therefore be registered on the renamer too. Each subclass keeps its own
 * {@code register} method and passes exactly the sources it needs to {@link #listenTo}.
 * {@code WikiEventManager} holds listeners weakly, so whoever wires a listener must keep a strong reference.</p>
 */
public abstract class PageLifecycleListener implements WikiEventListener {

    /** Logs under the concrete subclass so operators' per-listener logger configuration is unchanged. */
    private final Logger log = LogManager.getLogger( getClass().getName() );

    @Override
    public final void actionPerformed( final WikiEvent event ) {
        try {
            if ( event instanceof WikiPageRenameEvent rename ) {
                onRenamed( rename.getOldPageName(), rename.getNewPageName() );
            } else if ( event instanceof WikiPageEvent pageEvent ) {
                switch ( pageEvent.getType() ) {
                    case WikiPageEvent.POST_SAVE_END -> onSaved( pageEvent.getPageName() );
                    case WikiPageEvent.PAGE_DELETED  -> onDeleted( pageEvent.getPageName() );
                    default                          -> { /* ignore other event types */ }
                }
            }
        } catch ( final RuntimeException e ) {
            log.warn( "{} event handling failed for {}: {}", label(), event, e.getMessage(), e );
        }
    }

    /** A save finished ({@code POST_SAVE_END}). Default: nothing. */
    protected void onSaved( final String page ) {
    }

    /** A page was deleted ({@code PAGE_DELETED}). Default: nothing. */
    protected void onDeleted( final String page ) {
    }

    /** A page was renamed. Default: nothing. */
    protected void onRenamed( final String from, final String to ) {
    }

    /** Short lower-case name used in the failure log line, e.g. {@code "citation"}. */
    protected abstract String label();

    /** Registers this listener on every non-null source. */
    protected final void listenTo( final Object... sources ) {
        for ( final Object source : sources ) {
            if ( source != null ) {
                WikiEventManager.addWikiEventListener( source, this );
            }
        }
    }
}
