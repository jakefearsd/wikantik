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
package com.wikantik.plugin;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import com.wikantik.api.core.Context;
import com.wikantik.api.core.Engine;
import com.wikantik.api.core.Page;
import com.wikantik.api.exceptions.PluginException;
import com.wikantik.api.frontmatter.FrontmatterParser;
import com.wikantik.api.frontmatter.ParsedPage;
import com.wikantik.api.plugin.Plugin;
import com.wikantik.api.providers.PageProvider;
import com.wikantik.blog.BlogManager;
import com.wikantik.pages.PageManager;
import com.wikantik.util.TextUtil;

import java.util.List;
import java.util.Map;

/**
 * Lists blog entries with dates, titles, and optional excerpts. Typically used on {@code Blog.md}.
 *
 * <p>Usage: {@code [{ArticleListing}]} on Blog.md.
 *
 * <p><b>Parameters:</b>
 * <ul>
 *   <li><b>user</b> - blog owner username (defaults to current page context, reuses
 *       {@link LatestArticle#resolveUsername(Context, Map)})</li>
 *   <li><b>count</b> - maximum number of entries to show (default: 10)</li>
 *   <li><b>excerpt</b> - true/false, whether to show excerpts (default: true)</li>
 *   <li><b>excerptLength</b> - character limit for excerpts (default: 200)</li>
 *   <li><b>skipLatest</b> - true/false, skip the most recent entry (default: false).
 *       Useful when paired with {@link LatestArticle} on the same page to avoid duplication.</li>
 * </ul>
 *
 * @since 3.0.8
 */
public class ArticleListing implements Plugin {

    private static final Logger LOG = LogManager.getLogger( ArticleListing.class );

    /** Parameter name for user. */
    public static final String PARAM_USER = "user";

    /** Parameter name for maximum count. */
    public static final String PARAM_COUNT = "count";

    /** Parameter name for excerpt flag. */
    public static final String PARAM_EXCERPT = "excerpt";

    /** Parameter name for excerpt length. */
    public static final String PARAM_EXCERPT_LENGTH = "excerptLength";

    /** Parameter name for skipping the latest entry. */
    public static final String PARAM_SKIP_LATEST = "skipLatest";

    /** Default number of entries to show. */
    static final int DEFAULT_COUNT = 10;

    /** Default excerpt length in characters. */
    static final int DEFAULT_EXCERPT_LENGTH = 200;

    /** {@inheritDoc} */
    @Override
    public String execute( final Context context, final Map< String, String > params ) throws PluginException {
        final Engine engine = context.getEngine();
        final BlogManager blogManager = engine.getManager( BlogManager.class );
        final PageManager pageManager = engine.getManager( PageManager.class );

        if ( blogManager == null ) {
            LOG.warn( "BlogManager not available" );
            return "<p class=\"error\">ArticleListing plugin: BlogManager not available.</p>";
        }

        try {
            final String username = LatestArticle.resolveUsername( context, params );
            if ( username == null || username.isEmpty() ) {
                return "<p class=\"error\">ArticleListing plugin: cannot determine blog owner.</p>";
            }

            List< Page > entries = blogManager.listEntries( username );

            // Skip the most recent entry when used alongside LatestArticle
            final boolean skipLatest = "true".equalsIgnoreCase( params.get( PARAM_SKIP_LATEST ) );
            if ( skipLatest && !entries.isEmpty() ) {
                entries = entries.subList( 1, entries.size() );
            }

            if ( entries.isEmpty() ) {
                return "<p>No entries yet.</p>";
            }

            // Apply count limit
            final int count = TextUtil.parseIntParameter( params.get( PARAM_COUNT ), DEFAULT_COUNT );
            if ( entries.size() > count ) {
                entries = entries.subList( 0, count );
            }

            // Determine excerpt settings
            final boolean showExcerpt = !"false".equalsIgnoreCase( params.get( PARAM_EXCERPT ) );
            final int excerptLength = parsePositiveInt( params.get( PARAM_EXCERPT_LENGTH ), DEFAULT_EXCERPT_LENGTH );

            return renderHtml( entries, username, pageManager, engine.getBaseURL(), showExcerpt, excerptLength );

        } catch ( final Exception e ) {
            LOG.warn( "Error executing ArticleListing plugin", e );
            throw new PluginException( "Error listing blog entries: " + e.getMessage() );
        }
    }

    /**
     * Renders blog entries as an HTML list.
     */
    private String renderHtml( final List< Page > entries, final String username,
                               final PageManager pageManager, final String baseURL,
                               final boolean showExcerpt, final int excerptLength ) throws Exception {
        final StringBuilder sb = new StringBuilder();
        sb.append( "<div class=\"article-listing\">\n" );
        sb.append( "<ul>\n" );

        for ( final Page entry : entries ) {
            final String content = pageManager.getPureText( entry.getName(), PageProvider.LATEST_VERSION );
            final ParsedPage parsed = FrontmatterParser.parse( content );
            final Map< String, Object > metadata = parsed.metadata();

            final String title = metadata.containsKey( "title" )
                    ? metadata.get( "title" ).toString()
                    : LatestArticle.titleFromFilename( entry.getName() );
            final String formattedDate = LatestArticle.formatDate( metadata.get( "date" ) );
            final String body = parsed.body().trim();
            final String synopsis = metadata.containsKey( "synopsis" )
                    ? metadata.get( "synopsis" ).toString() : null;

            // Build the link to the entry
            final String entrySlug = entry.getName().substring( entry.getName().lastIndexOf( '/' ) + 1 );
            final String href = baseURL + "/blog/" + escapeHtml( username ) + "/" + escapeHtml( entrySlug );

            sb.append( "  <li class=\"entry-item\">" );

            // Title with date as link: "Title - Fri Apr 03"
            sb.append( "<a href=\"" ).append( href ).append( "\" class=\"entry-title\">" );
            sb.append( escapeHtml( title ) );
            if ( !formattedDate.isEmpty() ) {
                sb.append( " &mdash; " ).append( escapeHtml( formattedDate ) );
            }
            sb.append( "</a>" );

            // Excerpt: use synopsis from frontmatter if available, otherwise truncate body
            if ( showExcerpt && !body.isEmpty() ) {
                final String excerptText = synopsis != null
                        ? synopsis
                        : truncate( LatestArticle.stripMarkdown( body ), excerptLength );
                sb.append( "\n    <p class=\"entry-excerpt\">" ).append( escapeHtml( excerptText ) ).append( "</p>" );
            }

            sb.append( "</li>\n" );
        }

        sb.append( "</ul>\n" );
        sb.append( "</div>\n" );
        return sb.toString();
    }

    /**
     * Truncates text to the specified length, appending "..." if truncated.
     */
    private String truncate( final String text, final int maxLength ) {
        if ( text.length() <= maxLength ) {
            return text;
        }
        return text.substring( 0, maxLength ) + "...";
    }

    /**
     * Parses a string as a positive integer, returning the default if parsing fails or value is non-positive.
     */
    private int parsePositiveInt( final String value, final int defaultValue ) {
        if ( value == null || value.isEmpty() ) {
            return defaultValue;
        }
        try {
            final int parsed = Integer.parseInt( value );
            return parsed > 0 ? parsed : defaultValue;
        } catch ( final NumberFormatException e ) {
            return defaultValue;
        }
    }

    /**
     * Escapes HTML special characters.
     */
    private String escapeHtml( final String text ) {
        if ( text == null ) {
            return "";
        }
        return text
            .replace( "&", "&amp;" )
            .replace( "<", "&lt;" )
            .replace( ">", "&gt;" )
            .replace( "\"", "&quot;" );
    }
}
