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
import com.wikantik.api.core.Engine;
import com.wikantik.cache.CachingManager;
import com.wikantik.preferences.Preferences;
import com.wikantik.util.TextUtil;
import com.wikantik.variables.VariableManager;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * The page-view side of {@link CachingManager#CACHE_HTML} for {@link DefaultRenderingManager#textToHTML}: what a view
 * may be served from it and what it may store. An entry is valid only for the raw page data and the viewer locale it
 * was rendered for; a view whose locale cannot be determined neither reads nor writes it.
 */
final class HtmlViewCache {

    private static final Logger LOG = LogManager.getLogger( HtmlViewCache.class );

    private HtmlViewCache() {
    }

    /** The cached final HTML for this view, or null on a miss (other data, other locale, unknown locale). */
    static String lookup( final CachingManager caches, final String cacheId, final String rawHash, final String locale ) {
        if( locale == null ) {
            return null;
        }
        final DefaultRenderingManager.HtmlCacheEntry cached = caches.get( CachingManager.CACHE_HTML, cacheId, () -> null );
        if( cached != null && cached.matches( rawHash, locale ) ) {
            LOG.debug( "HTML cache hit for {}", cacheId );
            return cached.html();
        }
        return null;
    }

    /** Stores a page view's final HTML; an entry without a locale is never stored (it would match every viewer). */
    static void store( final CachingManager caches, final String cacheId, final DefaultRenderingManager.HtmlCacheEntry entry ) {
        if( entry.locale() != null ) {
            caches.put( CachingManager.CACHE_HTML, cacheId, entry );
        }
    }

    /** The viewer's locale tag (create-page tooltips and other bundle strings are localised), or null if unknown. */
    static String viewerLocale( final Context context ) {
        try {
            return Preferences.getLocale( context ).toLanguageTag();
        } catch( final RuntimeException e ) {
            LOG.warn( "Could not determine the viewer locale; bypassing the HTML cache: {}", e.getMessage() );
            return null;
        }
    }

    /**
     * Whether the page filters run for a render: the {@link VariableManager#VAR_RUNFILTERS} context variable (set by
     * code), else the wiki property. Never a request parameter or session attribute, which the caller controls. A
     * render that skips the filters is flagged uncacheable, so it never reaches the shared document or HTML cache.
     */
    static boolean runFilters( final Context context, final Engine engine ) {
        final Object variable = context.getVariable( VariableManager.VAR_RUNFILTERS );
        final boolean run = variable != null ? "true".equals( variable.toString() ) : propertyAllows( engine );
        if( !run ) {
            context.setVariable( Context.VAR_RENDER_UNCACHEABLE, Boolean.TRUE );
        }
        return run;
    }

    private static boolean propertyAllows( final Engine engine ) {
        return engine == null || engine.getWikiProperties() == null
               || TextUtil.getBooleanProperty( engine.getWikiProperties(), VariableManager.VAR_RUNFILTERS, true );
    }
}
