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
package com.wikantik.knowledge;

import com.wikantik.api.core.Context;
import com.wikantik.api.filters.PageFilter;
import com.wikantik.api.frontmatter.FrontmatterParser;
import com.wikantik.api.frontmatter.FrontmatterWriter;
import com.wikantik.api.frontmatter.ParsedPage;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * A postSave {@link PageFilter} that keeps Hub membership in sync bidirectionally.
 *
 * <ul>
 *   <li>When a member page's {@code hubs} list changes, the referenced Hub pages'
 *       {@code related} lists are updated to add or remove the member.</li>
 *   <li>When a Hub page's {@code related} list changes, the referenced member pages'
 *       {@code hubs} lists are updated to add or remove the hub.</li>
 * </ul>
 *
 * <p>A thread-local {@code SUPPRESS_SYNC} flag prevents recursive saves from triggering
 * another round of sync.</p>
 */
public class HubSyncFilter implements PageFilter {

    private static final Logger LOG = LogManager.getLogger( HubSyncFilter.class );

    /** Thread-local flag that prevents re-entrant sync during secondary saves. */
    private static final ThreadLocal< Boolean > SUPPRESS_SYNC = ThreadLocal.withInitial( () -> Boolean.FALSE );

    private final Function< String, String > readPage;
    private final BiConsumer< String, String > savePage;

    /**
     * Creates a new HubSyncFilter.
     *
     * @param readPage  a function that returns the raw content of a page by name, or {@code null} if not found
     * @param savePage  a consumer that persists (name, content) for a page
     */
    public HubSyncFilter( final Function< String, String > readPage,
                          final BiConsumer< String, String > savePage ) {
        this.readPage = readPage;
        this.savePage = savePage;
    }

    /**
     * Called after a page is successfully saved. Delegates to
     * {@link #syncAfterSave(String, String, String)} with the page name from context
     * and null for previous content.
     */
    @Override
    public void postSave( final Context context, final String content ) {
        final String pageName = context.getPage().getName();
        syncAfterSave( pageName, content, null );
    }

    /**
     * Synchronises bidirectional Hub membership after a page save.
     *
     * @param pageName    the name of the page that was saved
     * @param newContent  the new raw page content (may be null)
     * @param oldContent  the previous raw page content (may be null)
     */
    public void syncAfterSave( final String pageName,
                                final String newContent,
                                final String oldContent ) {
        if ( SUPPRESS_SYNC.get() ) {
            return;
        }

        try {
            final ParsedPage newPage = FrontmatterParser.parse( newContent );
            final ParsedPage oldPage = FrontmatterParser.parse( oldContent );

            syncMemberHubsField( pageName, newPage, oldPage );
            syncHubRelatedField( pageName, newPage, oldPage );
        } catch ( final Exception e ) {
            LOG.warn( "HubSyncFilter: unexpected error while syncing page '{}': {}", pageName, e.getMessage(), e );
        }
    }

    // -------------------------------------------------------------------------
    // Direction 1 — member page's `hubs` field changed
    // -------------------------------------------------------------------------

    private void syncMemberHubsField( final String memberName,
                                       final ParsedPage newPage,
                                       final ParsedPage oldPage ) {
        final List< String > newHubs = toStringList( newPage.metadata().get( "hubs" ) );
        final List< String > oldHubs = toStringList( oldPage.metadata().get( "hubs" ) );

        for ( final String hubName : newHubs ) {
            if ( !oldHubs.contains( hubName ) ) {
                addToTargetList( hubName, "related", memberName );
            }
        }
        for ( final String hubName : oldHubs ) {
            if ( !newHubs.contains( hubName ) ) {
                removeFromTargetList( hubName, "related", memberName );
            }
        }
    }

    // -------------------------------------------------------------------------
    // Direction 2 — hub page's `related` field changed (only if type == hub)
    // -------------------------------------------------------------------------

    private void syncHubRelatedField( final String hubName,
                                       final ParsedPage newPage,
                                       final ParsedPage oldPage ) {
        final Object newType = newPage.metadata().get( "type" );
        if ( !"hub".equals( newType ) ) {
            return;
        }

        final List< String > newRelated = toStringList( newPage.metadata().get( "related" ) );
        final List< String > oldRelated = toStringList( oldPage.metadata().get( "related" ) );

        for ( final String memberName : newRelated ) {
            if ( !oldRelated.contains( memberName ) ) {
                addToTargetList( memberName, "hubs", hubName );
            }
        }
        for ( final String memberName : oldRelated ) {
            if ( !newRelated.contains( memberName ) ) {
                removeFromTargetList( memberName, "hubs", hubName );
            }
        }
    }

    // -------------------------------------------------------------------------
    // Helpers — mutate a target page's list field and save
    // -------------------------------------------------------------------------

    private void addToTargetList( final String targetPageName,
                                   final String listField,
                                   final String valueToAdd ) {
        final String raw = readPage.apply( targetPageName );
        if ( raw == null ) {
            LOG.debug( "HubSyncFilter: target page '{}' not found; skipping add of '{}' to '{}'",
                targetPageName, valueToAdd, listField );
            return;
        }
        try {
            final ParsedPage parsed = FrontmatterParser.parse( raw );
            final List< String > current = new ArrayList<>( toStringList( parsed.metadata().get( listField ) ) );
            if ( current.contains( valueToAdd ) ) {
                return; // already present — no-op
            }
            current.add( valueToAdd );
            final Map< String, Object > updatedMeta = new HashMap<>( parsed.metadata() );
            updatedMeta.put( listField, current );
            final String updatedContent = FrontmatterWriter.write( updatedMeta, parsed.body() );
            suppressedSave( targetPageName, updatedContent );
        } catch ( final Exception e ) {
            LOG.warn( "HubSyncFilter: failed to add '{}' to '{}' in '{}': {}",
                valueToAdd, listField, targetPageName, e.getMessage(), e );
        }
    }

    private void removeFromTargetList( final String targetPageName,
                                        final String listField,
                                        final String valueToRemove ) {
        final String raw = readPage.apply( targetPageName );
        if ( raw == null ) {
            LOG.debug( "HubSyncFilter: target page '{}' not found; skipping removal of '{}' from '{}'",
                targetPageName, valueToRemove, listField );
            return;
        }
        try {
            final ParsedPage parsed = FrontmatterParser.parse( raw );
            final List< String > current = new ArrayList<>( toStringList( parsed.metadata().get( listField ) ) );
            if ( !current.remove( valueToRemove ) ) {
                return; // not present — no-op
            }
            final Map< String, Object > updatedMeta = new HashMap<>( parsed.metadata() );
            if ( current.isEmpty() ) {
                updatedMeta.remove( listField );
            } else {
                updatedMeta.put( listField, current );
            }
            final String updatedContent = FrontmatterWriter.write( updatedMeta, parsed.body() );
            suppressedSave( targetPageName, updatedContent );
        } catch ( final Exception e ) {
            LOG.warn( "HubSyncFilter: failed to remove '{}' from '{}' in '{}': {}",
                valueToRemove, listField, targetPageName, e.getMessage(), e );
        }
    }

    /** Performs a save with the SUPPRESS_SYNC flag set to prevent recursion. */
    private void suppressedSave( final String pageName, final String content ) {
        SUPPRESS_SYNC.set( Boolean.TRUE );
        try {
            savePage.accept( pageName, content );
        } finally {
            SUPPRESS_SYNC.set( Boolean.FALSE );
        }
    }

    // -------------------------------------------------------------------------
    // Utility
    // -------------------------------------------------------------------------

    /**
     * Converts a frontmatter field value to a {@code List<String>}.
     *
     * @param value the raw value from the metadata map (may be null)
     * @return the string elements of the list, or an empty list
     */
    static List< String > toStringList( final Object value ) {
        if ( value instanceof List< ? > list ) {
            final List< String > result = new ArrayList<>();
            for ( final Object item : list ) {
                if ( item instanceof String s ) {
                    result.add( s );
                }
            }
            return result;
        }
        return List.of();
    }
}
