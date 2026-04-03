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
import com.wikantik.api.exceptions.PluginException;
import com.wikantik.api.plugin.Plugin;
import com.wikantik.blog.BlogInfo;
import com.wikantik.blog.BlogManager;
import com.wikantik.util.TextUtil;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Discovers and lists all blogs in the wiki.
 *
 * <p>Usage: {@code [{BlogListing}]} on any page.
 *
 * <p><b>Parameters:</b>
 * <ul>
 *   <li><b>include</b> - regex filter on username (only matching usernames shown)</li>
 *   <li><b>exclude</b> - regex filter on username (matching usernames hidden)</li>
 *   <li><b>count</b> - maximum number of blogs to show</li>
 * </ul>
 *
 * @since 3.0.8
 */
public class BlogListing implements Plugin {

    private static final Logger LOG = LogManager.getLogger( BlogListing.class );

    /** Parameter name for include regex. */
    public static final String PARAM_INCLUDE = "include";

    /** Parameter name for exclude regex. */
    public static final String PARAM_EXCLUDE = "exclude";

    /** Parameter name for maximum count. */
    public static final String PARAM_COUNT = "count";

    /** Default maximum count (effectively unlimited). */
    private static final int DEFAULT_COUNT = Integer.MAX_VALUE;

    /** {@inheritDoc} */
    @Override
    public String execute( final Context context, final Map< String, String > params ) throws PluginException {
        final Engine engine = context.getEngine();
        final BlogManager blogManager = engine.getManager( BlogManager.class );

        if ( blogManager == null ) {
            LOG.warn( "BlogManager not available" );
            return "<p class=\"error\">BlogListing plugin: BlogManager not available.</p>";
        }

        try {
            List< BlogInfo > blogs = blogManager.listBlogs();

            // Apply include filter
            final String includeParam = params.get( PARAM_INCLUDE );
            if ( includeParam != null && !includeParam.isEmpty() ) {
                final Pattern includePattern = Pattern.compile( includeParam );
                blogs = blogs.stream()
                    .filter( b -> includePattern.matcher( b.username() ).matches() )
                    .collect( Collectors.toList() );
            }

            // Apply exclude filter
            final String excludeParam = params.get( PARAM_EXCLUDE );
            if ( excludeParam != null && !excludeParam.isEmpty() ) {
                final Pattern excludePattern = Pattern.compile( excludeParam );
                blogs = blogs.stream()
                    .filter( b -> !excludePattern.matcher( b.username() ).matches() )
                    .collect( Collectors.toList() );
            }

            // Apply count limit
            final int count = TextUtil.parseIntParameter( params.get( PARAM_COUNT ), DEFAULT_COUNT );
            if ( blogs.size() > count ) {
                blogs = blogs.subList( 0, count );
            }

            if ( blogs.isEmpty() ) {
                return "<p>No blogs found.</p>";
            }

            return renderHtml( blogs, engine.getBaseURL() );

        } catch ( final Exception e ) {
            LOG.warn( "Error executing BlogListing plugin", e );
            throw new PluginException( "Error listing blogs: " + e.getMessage() );
        }
    }

    /**
     * Renders the list of blogs as an HTML unordered list.
     */
    private String renderHtml( final List< BlogInfo > blogs, final String baseURL ) {
        final StringBuilder sb = new StringBuilder();
        sb.append( "<div class=\"blog-listing\">\n" );
        sb.append( "<ul>\n" );

        for ( final BlogInfo blog : blogs ) {
            final String href = baseURL + "/blog/" + escapeHtml( blog.username() ) + "/Blog";
            sb.append( "  <li class=\"blog-item\">" );
            sb.append( "<a href=\"" ).append( href ).append( "\">" );
            sb.append( escapeHtml( blog.title() ) );
            sb.append( "</a>" );

            if ( blog.description() != null && !blog.description().isEmpty() ) {
                sb.append( " &mdash; " ).append( escapeHtml( blog.description() ) );
            }

            sb.append( " <span class=\"entry-count\">(" ).append( blog.entryCount() );
            sb.append( blog.entryCount() == 1 ? " entry" : " entries" );
            sb.append( ")</span>" );

            sb.append( "</li>\n" );
        }

        sb.append( "</ul>\n" );
        sb.append( "</div>\n" );
        return sb.toString();
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
