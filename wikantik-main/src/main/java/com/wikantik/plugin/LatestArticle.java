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

import java.util.List;
import java.util.Map;

/**
 * Shows the most recent blog entry for a user. Typically used on {@code Blog.md}.
 *
 * <p>Usage: {@code [{LatestArticle}]} on Blog.md.
 *
 * <p><b>Parameters:</b>
 * <ul>
 *   <li><b>user</b> - blog owner username (defaults to inferring from current page name)</li>
 *   <li><b>excerpt</b> - true/false, whether to show an excerpt or full content (default: true)</li>
 *   <li><b>excerptLength</b> - character limit for excerpt (default: 200)</li>
 * </ul>
 *
 * @since 3.0.8
 */
public class LatestArticle implements Plugin {

    private static final Logger LOG = LogManager.getLogger( LatestArticle.class );

    /** Parameter name for user. */
    public static final String PARAM_USER = "user";

    /** Parameter name for excerpt flag. */
    public static final String PARAM_EXCERPT = "excerpt";

    /** Parameter name for excerpt length. */
    public static final String PARAM_EXCERPT_LENGTH = "excerptLength";

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
            return "<p class=\"error\">LatestArticle plugin: BlogManager not available.</p>";
        }

        try {
            final String username = resolveUsername( context, params );
            if ( username == null || username.isEmpty() ) {
                return "<p class=\"error\">LatestArticle plugin: cannot determine blog owner.</p>";
            }

            final List< Page > entries = blogManager.listEntries( username );
            if ( entries.isEmpty() ) {
                return "<p>No entries yet.</p>";
            }

            // First entry is the newest (listEntries returns descending order)
            final Page latestEntry = entries.get( 0 );
            final String content = pageManager.getPureText( latestEntry.getName(), PageProvider.LATEST_VERSION );
            final ParsedPage parsed = FrontmatterParser.parse( content );
            final Map< String, Object > metadata = parsed.metadata();

            final String title = metadata.getOrDefault( "title", latestEntry.getName() ).toString();
            final String date = metadata.containsKey( "date" ) ? metadata.get( "date" ).toString() : "";
            final String body = parsed.body().trim();

            // Determine excerpt vs. full content
            final boolean showExcerpt = !"false".equalsIgnoreCase( params.get( PARAM_EXCERPT ) );
            final int excerptLength = parsePositiveInt( params.get( PARAM_EXCERPT_LENGTH ), DEFAULT_EXCERPT_LENGTH );

            // Build the link to the entry
            final String entrySlug = latestEntry.getName().substring( latestEntry.getName().lastIndexOf( '/' ) + 1 );
            final String href = engine.getBaseURL() + "/blog/" + escapeHtml( username ) + "/" + escapeHtml( entrySlug );

            return renderHtml( title, date, body, href, showExcerpt, excerptLength );

        } catch ( final Exception e ) {
            LOG.warn( "Error executing LatestArticle plugin", e );
            throw new PluginException( "Error retrieving latest article: " + e.getMessage() );
        }
    }

    /**
     * Resolves the blog owner username. Checks the {@code user} parameter first, then
     * infers from the current page name (expects {@code blog/<username>/...}).
     *
     * <p>Package-private so that {@link ArticleListing} can reuse it.
     *
     * @param context the wiki context
     * @param params  the plugin parameters
     * @return the resolved username, or {@code null} if it cannot be determined
     */
    static String resolveUsername( final Context context, final Map< String, String > params ) {
        // Check explicit user parameter first
        final String userParam = params.get( PARAM_USER );
        if ( userParam != null && !userParam.isEmpty() ) {
            return userParam.toLowerCase();
        }

        // Infer from current page name: blog/<username>/...
        final Page page = context.getPage();
        if ( page != null ) {
            final String pageName = page.getName();
            if ( pageName != null && pageName.startsWith( "blog/" ) ) {
                final String remainder = pageName.substring( "blog/".length() );
                final int slashIndex = remainder.indexOf( '/' );
                if ( slashIndex > 0 ) {
                    return remainder.substring( 0, slashIndex ).toLowerCase();
                }
            }
        }

        return null;
    }

    /**
     * Renders the latest article as HTML.
     */
    private String renderHtml( final String title, final String date, final String body,
                               final String href, final boolean showExcerpt, final int excerptLength ) {
        final StringBuilder sb = new StringBuilder();
        sb.append( "<div class=\"latest-article\">\n" );

        // Title as link
        sb.append( "  <h3 class=\"entry-title\"><a href=\"" ).append( href ).append( "\">" );
        sb.append( escapeHtml( title ) );
        sb.append( "</a></h3>\n" );

        // Date
        if ( !date.isEmpty() ) {
            sb.append( "  <p class=\"entry-date\">" ).append( escapeHtml( date ) ).append( "</p>\n" );
        }

        // Excerpt or full content
        if ( !body.isEmpty() ) {
            if ( showExcerpt ) {
                final String excerpt = truncate( body, excerptLength );
                sb.append( "  <p class=\"entry-excerpt\">" ).append( escapeHtml( excerpt ) ).append( "</p>\n" );
            } else {
                sb.append( "  <div class=\"entry-content\">" ).append( escapeHtml( body ) ).append( "</div>\n" );
            }
        }

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
