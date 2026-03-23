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
package com.wikantik.test;

import com.wikantik.api.core.Context;
import com.wikantik.api.core.Engine;
import com.wikantik.api.core.Page;
import com.wikantik.api.exceptions.FilterException;
import com.wikantik.api.exceptions.ProviderException;
import com.wikantik.event.WikiEvent;
import com.wikantik.api.parser.MarkdownLinkScanner;
import com.wikantik.references.ReferenceManager;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory stub implementation of {@link ReferenceManager} for unit testing.
 *
 * <p>Stores reference data in two maps — no filesystem, no providers,
 * no engine required. This allows testing components that depend on
 * ReferenceManager without the overhead of a full WikiEngine initialization.
 *
 * <p>Usage:
 * <pre>{@code
 * StubReferenceManager refMgr = new StubReferenceManager();
 * refMgr.addReferences("SourcePage", Set.of("TargetA", "TargetB"));
 * GetOutboundLinksTool tool = new GetOutboundLinksTool(refMgr);
 * }</pre>
 *
 * @since 3.0.7
 */
public class StubReferenceManager implements ReferenceManager {

    /** What each page links to (outbound). */
    private final Map< String, Collection< String > > refersTo = new ConcurrentHashMap<>();

    /** What links to each page (inbound). */
    private final Map< String, Set< String > > referredBy = new ConcurrentHashMap<>();

    /**
     * Test convenience method: populates both refersTo and referredBy maps.
     * This is the primary way tests set up reference data.
     *
     * @param pageName the source page
     * @param targets  the pages that {@code pageName} links to
     */
    public void addReferences( final String pageName, final Collection< String > targets ) {
        refersTo.put( pageName, new LinkedHashSet<>( targets ) );
        for ( final String target : targets ) {
            referredBy.computeIfAbsent( target, k -> ConcurrentHashMap.newKeySet() )
                    .add( pageName );
        }
        // Ensure the source page is in referredBy keys (even if empty) so findCreated works
        referredBy.computeIfAbsent( pageName, k -> ConcurrentHashMap.newKeySet() );
    }

    // --- ReferenceManager implementation ---

    @Override
    public void initialize( final Collection< Page > pages ) throws ProviderException {
        // no-op
    }

    @Override
    public boolean isInitialized() {
        return true;
    }

    @Override
    public Collection< String > scanWikiLinks( final Page page, final String pagedata ) {
        return new ArrayList<>( MarkdownLinkScanner.findLocalLinks( pagedata ) );
    }

    @Override
    public void pageRemoved( final Page page ) {
        clearPageEntries( page.getName() );
    }

    @Override
    public void updateReferences( final Page page ) {
        // no-op — single-arg overload
    }

    @Override
    public void updateReferences( final String page, final Collection< String > references ) {
        addReferences( page, references );
    }

    @Override
    public void clearPageEntries( final String pagename ) {
        final Collection< String > targets = refersTo.remove( pagename );
        if ( targets != null ) {
            for ( final String target : targets ) {
                final Set< String > refs = referredBy.get( target );
                if ( refs != null ) {
                    refs.remove( pagename );
                }
            }
        }
        // Also remove as a referrer from the referredBy map
        referredBy.remove( pagename );
    }

    @Override
    public Collection< String > findUnreferenced() {
        // Pages in refersTo keys that have empty (or absent) referredBy entries
        final List< String > unreferenced = new ArrayList<>();
        for ( final String page : refersTo.keySet() ) {
            final Set< String > refs = referredBy.get( page );
            if ( refs == null || refs.isEmpty() ) {
                unreferenced.add( page );
            }
        }
        return unreferenced;
    }

    @Override
    public Collection< String > findUncreated() {
        // Pages in referredBy keys that aren't in refersTo keys
        final Set< String > uncreated = new LinkedHashSet<>();
        for ( final String page : referredBy.keySet() ) {
            if ( !refersTo.containsKey( page ) ) {
                uncreated.add( page );
            }
        }
        return uncreated;
    }

    @Override
    public Set< String > findReferrers( final String pagename ) {
        final Set< String > refs = referredBy.get( pagename );
        if ( refs == null ) {
            return Set.of();
        }
        return Collections.unmodifiableSet( new LinkedHashSet<>( refs ) );
    }

    @Override
    public Set< String > findReferredBy( final String pageName ) {
        return findReferrers( pageName );
    }

    @Override
    public Collection< String > findRefersTo( final String pageName ) {
        final Collection< String > targets = refersTo.get( pageName );
        if ( targets == null ) {
            return List.of();
        }
        return Collections.unmodifiableCollection( new ArrayList<>( targets ) );
    }

    @Override
    public Set< String > findCreated() {
        return Collections.unmodifiableSet( new LinkedHashSet<>( refersTo.keySet() ) );
    }

    // --- PageFilter methods (no-op) ---

    @Override
    public void initialize( final Engine engine, final Properties properties ) throws FilterException {
        // no-op
    }

    @Override
    public String preTranslate( final Context context, final String content ) {
        return content;
    }

    @Override
    public String postTranslate( final Context context, final String htmlContent ) {
        return htmlContent;
    }

    @Override
    public String preSave( final Context context, final String content ) {
        return content;
    }

    @Override
    public void postSave( final Context context, final String content ) {
        // no-op
    }

    @Override
    public void destroy( final Engine engine ) {
        // no-op
    }

    // --- WikiEventListener ---

    @Override
    public void actionPerformed( final WikiEvent event ) {
        // no-op
    }
}
