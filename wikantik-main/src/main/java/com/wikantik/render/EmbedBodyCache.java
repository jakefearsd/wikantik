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
package com.wikantik.render;

import com.wikantik.api.core.Context;
import com.wikantik.cache.CachingManager;
import com.wikantik.parser.WikiDocument;

import java.io.IOException;
import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;

/**
 * The embedded-page side of {@link CachingManager#CACHE_HTML} for {@link DefaultRenderingManager#renderEmbedBody}. A
 * rendered (and sanitized) body that did not consult the viewer is kept in a slot of the embedded page's version, so
 * it is evicted with that page's other render caches; a slot holds up to {@value #BODIES_PER_PAGE} bodies (the whole
 * page, sections, truncations), each valid only for the body source and the viewer locale it was rendered for.
 */
final class EmbedBodyCache {

    /** Suffix that turns a page's {@link CachingManager#CACHE_HTML} id into the id of its embed-body slot. */
    static final String SLOT_SUFFIX = "::embed";
    private static final int BODIES_PER_PAGE = 8;

    /** Renders an embed body. */
    @FunctionalInterface
    interface BodyRender {
        String render() throws IOException;
    }

    /** One embedded page's bodies, keyed by body-source hash and viewer locale. */
    record Bodies( Map< String, String > byKey ) implements Serializable {
    }

    private EmbedBodyCache() {
    }

    /**
     * The body from the slot when it holds one for this source and locale; otherwise rendered, and kept when caching
     * is on and the body itself did not consult the viewer.
     *
     * @param caches the caching manager
     * @param enabled whether this render may use the HTML cache at all
     * @param pageCacheId the embedded page's {@link CachingManager#CACHE_HTML} id (name, version, plugin execution)
     * @param context the body's render context, whose variable map the host render shares
     * @param markdown the body source
     * @param render renders the body
     * @return the body HTML
     * @throws IOException if the body cannot be rendered
     */
    static String render( final CachingManager caches, final boolean enabled, final String pageCacheId,
                          final Context context, final String markdown, final BodyRender render ) throws IOException {
        final String locale = enabled ? HtmlViewCache.viewerLocale( context ) : null;
        if( locale == null ) {
            return render.render();
        }
        final String slot = pageCacheId + SLOT_SUFFIX;
        final String key = WikiDocument.hashPageData( markdown ) + '\u0000' + locale;
        final Bodies bodies = caches.get( CachingManager.CACHE_HTML, slot, () -> null );
        final String cached = bodies == null ? null : bodies.byKey().get( key );
        if( cached != null ) {
            return cached;
        }
        // The host has already flagged the shared variable map viewer-sensitive: clear the flags so that only what the
        // body itself consults counts, then put them back, raised if the body raised them and never lowered.
        final Object sensitive = context.getVariable( Context.VAR_VIEWER_SENSITIVE );
        final Object uncacheable = context.getVariable( Context.VAR_RENDER_UNCACHEABLE );
        context.setVariable( Context.VAR_VIEWER_SENSITIVE, null );
        context.setVariable( Context.VAR_RENDER_UNCACHEABLE, null );
        final String html;
        final boolean dependsOnViewer;
        try {
            html = render.render();
        } finally {
            final boolean bodySensitive = restore( context, Context.VAR_VIEWER_SENSITIVE, sensitive );
            final boolean bodyUncacheable = restore( context, Context.VAR_RENDER_UNCACHEABLE, uncacheable );
            dependsOnViewer = bodySensitive || bodyUncacheable;
        }
        if( !dependsOnViewer ) {
            // Read the slot again: an eviction that landed while the body rendered must not be undone by this store.
            final Bodies current = caches.get( CachingManager.CACHE_HTML, slot, () -> null );
            caches.put( CachingManager.CACHE_HTML, slot, adding( current, key, html ) );
        }
        return html;
    }

    /** Puts a render flag back as it was before the body, unless the body raised it; true when it did. */
    private static boolean restore( final Context context, final String flag, final Object before ) {
        if( Boolean.TRUE.equals( context.getVariable( flag ) ) ) {
            return true;
        }
        context.setVariable( flag, before );
        return false;
    }

    /** The slot with {@code key} added, making room by dropping one other body when it is full. */
    private static Bodies adding( final Bodies previous, final String key, final String html ) {
        final Map< String, String > next = previous == null ? new HashMap<>() : new HashMap<>( previous.byKey() );
        if( next.size() >= BODIES_PER_PAGE && !next.containsKey( key ) ) {
            next.remove( next.keySet().iterator().next() );
        }
        next.put( key, html );
        return new Bodies( Map.copyOf( next ) );
    }
}
