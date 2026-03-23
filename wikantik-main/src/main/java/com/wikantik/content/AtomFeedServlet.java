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
package com.wikantik.content;

import jakarta.servlet.ServletConfig;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import com.wikantik.WikiContext;
import com.wikantik.api.core.Context;
import com.wikantik.api.core.Engine;
import com.wikantik.api.spi.Wiki;
import com.wikantik.api.frontmatter.FrontmatterParser;
import com.wikantik.api.frontmatter.ParsedPage;
import com.wikantik.pages.PageManager;
import com.wikantik.util.BaseUrlResolver;
import com.wikantik.util.TextUtil;

import java.io.IOException;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TimeZone;

/**
 * Servlet that generates an Atom 1.0 feed of recent wiki articles.
 *
 * <p>Endpoint: {@code GET /feed.xml}
 *
 * <p>Query parameters:
 * <ul>
 *   <li>{@code count} - Maximum number of entries (default: 20, max: 100)</li>
 *   <li>{@code cluster} - Filter to articles in a specific frontmatter cluster</li>
 * </ul>
 *
 * <p>Each entry includes frontmatter-derived {@code <summary>} and {@code <category>} tags
 * when available.
 */
public class AtomFeedServlet extends HttpServlet {

    private static final long serialVersionUID = 1L;
    private static final Logger LOG = LogManager.getLogger( AtomFeedServlet.class );

    private static final int DEFAULT_COUNT = 20;
    private static final int MAX_COUNT = 100;
    private static final int FEED_SINCE_DAYS = 90;

    /** Property for explicit feed base URL (mirrors sitemap property pattern). */
    public static final String PROP_FEED_BASE_URL = "wikantik.feed.baseURL";

    private Engine engine;
    private String configuredBaseUrl;

    @Override
    public void init( final ServletConfig config ) throws ServletException {
        super.init( config );
        engine = Wiki.engine().find( config );

        final Properties props = engine.getWikiProperties();
        configuredBaseUrl = TextUtil.getStringProperty( props, PROP_FEED_BASE_URL, null );

        LOG.info( "AtomFeedServlet initialized" );
    }

    @Override
    protected void doGet( final HttpServletRequest req, final HttpServletResponse resp )
            throws ServletException, IOException {

        LOG.debug( "Generating Atom feed" );

        // Parse parameters
        int count = DEFAULT_COUNT;
        final String countParam = req.getParameter( "count" );
        if ( countParam != null ) {
            try {
                count = Math.min( Math.max( 1, Integer.parseInt( countParam ) ), MAX_COUNT );
            } catch ( final NumberFormatException e ) {
                // ignore, use default
            }
        }
        final String cluster = req.getParameter( "cluster" );

        // Build query
        final RecentArticlesQuery query = new RecentArticlesQuery()
            .count( count )
            .sinceDays( FEED_SINCE_DAYS )
            .includeExcerpt( false );

        final Context context = Wiki.context().create( engine, req, WikiContext.VIEW );
        final RecentArticlesManager manager = engine.getManager( RecentArticlesManager.class );
        if ( manager == null ) {
            resp.sendError( HttpServletResponse.SC_SERVICE_UNAVAILABLE, "RecentArticlesManager not available" );
            return;
        }

        final List< ArticleSummary > articles = manager.getRecentArticles( context, query );
        final PageManager pageManager = engine.getManager( PageManager.class );

        // Resolve base URL using shared 3-tier resolution
        final String baseUrl = BaseUrlResolver.resolve( engine, req, configuredBaseUrl );
        final String appName = engine.getApplicationName();

        // Determine feed-level updated time
        final SimpleDateFormat atomDate = new SimpleDateFormat( "yyyy-MM-dd'T'HH:mm:ss'Z'" );
        atomDate.setTimeZone( TimeZone.getTimeZone( "UTC" ) );

        String feedUpdated = atomDate.format( new java.util.Date() );
        if ( !articles.isEmpty() && articles.get( 0 ).getLastModified() != null ) {
            feedUpdated = atomDate.format( articles.get( 0 ).getLastModified() );
        }

        resp.setContentType( "application/atom+xml" );
        resp.setCharacterEncoding( "UTF-8" );
        resp.setHeader( "Cache-Control", "public, max-age=300" );

        final PrintWriter out = resp.getWriter();
        out.println( "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" );
        out.println( "<feed xmlns=\"http://www.w3.org/2005/Atom\">" );
        out.println( "  <title>" + escapeXml( appName ) + ( cluster != null ? " - " + escapeXml( cluster ) : "" ) + "</title>" );
        out.println( "  <link href=\"" + escapeXml( baseUrl ) + "\" rel=\"alternate\" />" );

        final String feedSelf = baseUrl + "/feed.xml" + ( cluster != null ? "?cluster=" + escapeXml( cluster ) : "" );
        out.println( "  <link href=\"" + escapeXml( feedSelf ) + "\" rel=\"self\" type=\"application/atom+xml\" />" );
        out.println( "  <id>" + escapeXml( baseUrl ) + "/feed.xml</id>" );
        out.println( "  <updated>" + feedUpdated + "</updated>" );
        out.println( "  <generator>Wikantik</generator>" );

        int emitted = 0;
        for ( final ArticleSummary article : articles ) {
            // Parse frontmatter for tags, summary, and cluster
            Map< String, Object > metadata = Map.of();
            if ( pageManager != null ) {
                final String rawText = pageManager.getPureText( article.getName(), -1 );
                if ( rawText != null && !rawText.isEmpty() ) {
                    final ParsedPage parsed = FrontmatterParser.parse( rawText );
                    metadata = parsed.metadata();
                }
            }

            // Filter by cluster if requested
            if ( cluster != null && !cluster.isEmpty() ) {
                final Object articleCluster = metadata.get( "cluster" );
                if ( articleCluster == null || !cluster.equals( articleCluster.toString() ) ) {
                    continue;
                }
            }

            emitted++;
            if ( emitted > count ) {
                break;
            }

            final String articleUrl = baseUrl + "/wiki/" + article.getName();
            final String updated = article.getLastModified() != null
                ? atomDate.format( article.getLastModified() )
                : feedUpdated;

            out.println( "  <entry>" );
            out.println( "    <title>" + escapeXml( article.getTitle() ) + "</title>" );
            out.println( "    <link href=\"" + escapeXml( articleUrl ) + "\" rel=\"alternate\" />" );
            out.println( "    <id>" + escapeXml( articleUrl ) + "</id>" );
            out.println( "    <updated>" + updated + "</updated>" );

            if ( article.getAuthor() != null && !article.getAuthor().isEmpty() ) {
                out.println( "    <author><name>" + escapeXml( article.getAuthor() ) + "</name></author>" );
            }

            // Summary from frontmatter
            final Object summary = metadata.get( "summary" );
            if ( summary != null && !summary.toString().isEmpty() ) {
                out.println( "    <summary>" + escapeXml( summary.toString() ) + "</summary>" );
            } else if ( article.getExcerpt() != null && !article.getExcerpt().isEmpty() ) {
                out.println( "    <summary>" + escapeXml( article.getExcerpt() ) + "</summary>" );
            }

            // Category tags from frontmatter
            final Object tags = metadata.get( "tags" );
            if ( tags instanceof List< ? > tagList ) {
                for ( final Object tag : tagList ) {
                    out.println( "    <category term=\"" + escapeXml( tag.toString() ) + "\" />" );
                }
            }

            out.println( "  </entry>" );
        }

        out.println( "</feed>" );

        LOG.debug( "Atom feed generated with {} entries", emitted );
    }

    private static String escapeXml( final String input ) {
        if ( input == null ) {
            return "";
        }
        return input
            .replace( "&", "&amp;" )
            .replace( "<", "&lt;" )
            .replace( ">", "&gt;" )
            .replace( "\"", "&quot;" )
            .replace( "'", "&apos;" );
    }
}
