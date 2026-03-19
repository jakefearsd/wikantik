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
import com.wikantik.content.ArticleSummary;
import com.wikantik.content.RecentArticlesManager;
import com.wikantik.content.RecentArticlesQuery;
import com.wikantik.util.TextUtil;

import java.util.List;
import java.util.Map;

/**
 * Plugin for displaying recent articles with excerpts in wiki pages.
 *
 * <p>This plugin provides a modern article listing with titles, excerpts, and metadata,
 * suitable for creating landing pages and content discovery experiences.
 *
 * <p><b>Parameters:</b>
 * <ul>
 *   <li><b>count</b> - Maximum number of articles to display (default: 10, max: 100)</li>
 *   <li><b>since</b> - Number of days to look back (default: 30)</li>
 *   <li><b>excerpt</b> - Whether to include excerpts: true/false (default: true)</li>
 *   <li><b>excerptLength</b> - Maximum excerpt length in characters (default: 200)</li>
 *   <li><b>exclude</b> - Regex pattern for pages to exclude</li>
 *   <li><b>include</b> - Regex pattern for pages to include (only matching pages returned)</li>
 *   <li><b>cssClass</b> - Custom CSS class for the container (default: "recent-articles")</li>
 * </ul>
 *
 * <p><b>Example usage:</b>
 * <pre>
 * [{RecentArticles count=5 since=7}]
 * [{RecentArticles count=10 excerpt=true excerptLength=150}]
 * [{RecentArticles include='Blog.*' count=5}]
 * </pre>
 *
 * @since 3.0.7
 */
public class RecentArticles implements Plugin {

    private static final Logger LOG = LogManager.getLogger( RecentArticles.class );

    /** Parameter name for count. */
    public static final String PARAM_COUNT = "count";

    /** Parameter name for since days. */
    public static final String PARAM_SINCE = "since";

    /** Parameter name for excerpt flag. */
    public static final String PARAM_EXCERPT = "excerpt";

    /** Parameter name for excerpt length. */
    public static final String PARAM_EXCERPT_LENGTH = "excerptLength";

    /** Parameter name for exclude pattern. */
    public static final String PARAM_EXCLUDE = "exclude";

    /** Parameter name for include pattern. */
    public static final String PARAM_INCLUDE = "include";

    /** Parameter name for CSS class. */
    public static final String PARAM_CSS_CLASS = "cssClass";

    /** Maximum allowed count. */
    private static final int MAX_COUNT = 100;

    /** Default CSS class. */
    private static final String DEFAULT_CSS_CLASS = "recent-articles";

    /**
     * {@inheritDoc}
     */
    @Override
    public String execute( final Context context, final Map<String, String> params ) throws PluginException {
        final Engine engine = context.getEngine();

        final RecentArticlesManager manager = engine.getManager( RecentArticlesManager.class );
        if ( manager == null ) {
            LOG.error( "RecentArticlesManager not available" );
            return "<p class=\"error\">RecentArticles plugin: Service not available.</p>";
        }

        try {
            final RecentArticlesQuery query = buildQuery( params );
            final List<ArticleSummary> articles = manager.getRecentArticles( context, query );

            // Check if template-based rendering is available
            if ( manager.hasTemplatePage() ) {
                return manager.renderWithTemplate( context, articles );
            }

            // Fall back to default rendering
            final String cssClass = params.getOrDefault( PARAM_CSS_CLASS, DEFAULT_CSS_CLASS );
            return renderHtml( articles, cssClass );

        } catch ( final IllegalArgumentException e ) {
            LOG.warn( "Invalid plugin parameters: {}", e.getMessage() );
            throw new PluginException( "Invalid parameters: " + e.getMessage() );
        } catch ( final Exception e ) {
            LOG.error( "Error executing RecentArticles plugin", e );
            throw new PluginException( "Error retrieving recent articles: " + e.getMessage() );
        }
    }

    /**
     * Builds a query from plugin parameters.
     */
    private RecentArticlesQuery buildQuery( final Map<String, String> params ) {
        final RecentArticlesQuery query = new RecentArticlesQuery();

        // Parse count
        final String countParam = params.get( PARAM_COUNT );
        if ( countParam != null ) {
            int count = TextUtil.parseIntParameter( countParam, RecentArticlesQuery.DEFAULT_COUNT );
            if ( count <= 0 ) {
                count = RecentArticlesQuery.DEFAULT_COUNT;
            }
            if ( count > MAX_COUNT ) {
                count = MAX_COUNT;
            }
            query.count( count );
        }

        // Parse since days
        final String sinceParam = params.get( PARAM_SINCE );
        if ( sinceParam != null ) {
            int since = TextUtil.parseIntParameter( sinceParam, RecentArticlesQuery.DEFAULT_SINCE_DAYS );
            if ( since <= 0 ) {
                since = RecentArticlesQuery.DEFAULT_SINCE_DAYS;
            }
            query.sinceDays( since );
        }

        // Parse excerpt flag
        final String excerptParam = params.get( PARAM_EXCERPT );
        if ( excerptParam != null ) {
            query.includeExcerpt( TextUtil.isPositive( excerptParam ) );
        }

        // Parse excerpt length
        final String excerptLengthParam = params.get( PARAM_EXCERPT_LENGTH );
        if ( excerptLengthParam != null ) {
            int length = TextUtil.parseIntParameter( excerptLengthParam, RecentArticlesQuery.DEFAULT_EXCERPT_LENGTH );
            if ( length <= 0 ) {
                length = RecentArticlesQuery.DEFAULT_EXCERPT_LENGTH;
            }
            query.excerptLength( length );
        }

        // Parse exclude pattern
        final String excludeParam = params.get( PARAM_EXCLUDE );
        if ( excludeParam != null && !excludeParam.isEmpty() ) {
            query.excludePattern( excludeParam );
        }

        // Parse include pattern
        final String includeParam = params.get( PARAM_INCLUDE );
        if ( includeParam != null && !includeParam.isEmpty() ) {
            query.includePattern( includeParam );
        }

        return query;
    }

    /**
     * Renders articles as HTML.
     */
    private String renderHtml( final List<ArticleSummary> articles, final String cssClass ) {
        if ( articles == null || articles.isEmpty() ) {
            return "<p class=\"no-articles\">No recent articles found.</p>";
        }

        final StringBuilder sb = new StringBuilder();
        sb.append( "<div class=\"" ).append( escapeHtml( cssClass ) ).append( "\">\n" );

        for ( final ArticleSummary article : articles ) {
            sb.append( "  <div class=\"article-card\">\n" );
            sb.append( "    <h3 class=\"article-title\"><a href=\"" )
              .append( escapeHtml( article.getUrl() ) )
              .append( "\">" )
              .append( escapeHtml( article.getTitle() ) )
              .append( "</a></h3>\n" );

            sb.append( "    <p class=\"article-meta\">" );
            if ( article.getAuthor() != null ) {
                sb.append( "<span class=\"author\">" ).append( escapeHtml( article.getAuthor() ) ).append( "</span>" );
            }
            if ( article.getLastModified() != null ) {
                if ( article.getAuthor() != null ) {
                    sb.append( " &middot; " );
                }
                sb.append( "<span class=\"date\">" ).append( article.getLastModified() ).append( "</span>" );
            }
            sb.append( "</p>\n" );

            if ( article.getExcerpt() != null && !article.getExcerpt().isEmpty() ) {
                sb.append( "    <p class=\"article-excerpt\">" )
                  .append( escapeHtml( article.getExcerpt() ) )
                  .append( "</p>\n" );
            }

            if ( article.getChangeNote() != null && !article.getChangeNote().isEmpty() ) {
                sb.append( "    <p class=\"article-changenote\"><em>" )
                  .append( escapeHtml( article.getChangeNote() ) )
                  .append( "</em></p>\n" );
            }

            sb.append( "  </div>\n" );
        }

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
