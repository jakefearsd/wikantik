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
package com.wikantik.frontmatter;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import com.wikantik.WikiContext;
import com.wikantik.api.core.Page;
import com.wikantik.api.frontmatter.FrontmatterParser;
import com.wikantik.api.frontmatter.ParsedPage;
import com.wikantik.pages.PageManager;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Preloads YAML frontmatter metadata into page attributes so that it is available
 * in JSP templates before the rendering pipeline runs.
 *
 * <p>This solves the timing problem where {@code commonheader.jsp} emits {@code <head>}
 * (meta tags, JSON-LD) before {@code <wiki:Content/>} triggers the parser, which is
 * where frontmatter is normally extracted.
 *
 * <p>Usage from JSP:
 * <pre>{@code
 * <% com.wikantik.frontmatter.FrontmatterPreloader.preloadMetadata(
 *        (com.wikantik.WikiContext) pageContext.getAttribute("jspwiki.context",
 *        PageContext.REQUEST_SCOPE)); %>
 * }</pre>
 */
public final class FrontmatterPreloader {

    private static final Logger LOG = LogManager.getLogger( FrontmatterPreloader.class );

    /** Guard attribute to prevent double-parsing. */
    static final String ATTR_PRELOADED = "_frontmatterPreloaded";

    private FrontmatterPreloader() {
    }

    /**
     * Parses YAML frontmatter from the current page's raw text and sets each
     * metadata entry as a page attribute. List-valued fields are converted to
     * comma-separated strings for use with {@code <wiki:Variable>}.
     *
     * <p>This method is idempotent — calling it multiple times for the same page
     * is a no-op after the first successful call.
     *
     * @param context the current wiki context (may be null)
     */
    public static void preloadMetadata( final WikiContext context ) {
        if ( context == null ) {
            return;
        }

        final Page page = context.getPage();
        if ( page == null ) {
            return;
        }

        // Guard: don't re-parse if already preloaded
        if ( Boolean.TRUE.equals( page.getAttribute( ATTR_PRELOADED ) ) ) {
            return;
        }

        final PageManager pageManager = context.getEngine().getManager( PageManager.class );
        if ( pageManager == null ) {
            return;
        }

        final String rawText = pageManager.getPureText( page );
        if ( rawText == null || rawText.isEmpty() ) {
            page.setAttribute( ATTR_PRELOADED, Boolean.TRUE );
            return;
        }

        final ParsedPage parsed = FrontmatterParser.parse( rawText );
        final Map< String, Object > metadata = parsed.metadata();

        if ( metadata.isEmpty() ) {
            page.setAttribute( ATTR_PRELOADED, Boolean.TRUE );
            return;
        }

        for ( final Map.Entry< String, Object > entry : metadata.entrySet() ) {
            final String key = entry.getKey();
            final Object value = entry.getValue();

            if ( value instanceof List< ? > list ) {
                // Convert list to comma-separated string for <wiki:Variable> compatibility
                final String csv = list.stream()
                    .map( Object::toString )
                    .collect( Collectors.joining( ", " ) );
                page.setAttribute( key, csv );
            } else if ( value instanceof Date date ) {
                // SnakeYAML parses dates like 2026-03-20 into java.util.Date;
                // convert back to ISO format for template use
                page.setAttribute( key, new SimpleDateFormat( "yyyy-MM-dd" ).format( date ) );
            } else if ( value != null ) {
                page.setAttribute( key, value.toString() );
            }
        }

        page.setAttribute( ATTR_PRELOADED, Boolean.TRUE );
        LOG.debug( "Preloaded {} frontmatter attributes for page '{}'", metadata.size(), page.getName() );
    }
}
