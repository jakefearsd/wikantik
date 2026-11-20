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

import com.wikantik.util.TextUtil;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

/**
 * Renders recent-article lists from a wiki template (iterative or simple) or the default HTML card layout.
 */
final class ArticleTemplateRenderer {

    private static final Logger LOG = LogManager.getLogger( ArticleTemplateRenderer.class );

    /** Thread-safe date formatter for ISO format (yyyy-MM-dd). */
    private static final DateTimeFormatter ISO_DATE_FORMATTER = DateTimeFormatter.ofPattern( "yyyy-MM-dd" );

    /** Thread-safe date formatter for full format (MMMM d, yyyy). */
    private static final DateTimeFormatter FULL_DATE_FORMATTER = DateTimeFormatter.ofPattern( "MMMM d, yyyy", Locale.ENGLISH );

    private final Function< String, String > textToHtml;

    /** @param textToHtml wiki-markup to HTML conversion, or {@code null} to leave rendered templates as markup */
    ArticleTemplateRenderer( final Function< String, String > textToHtml ) {
        this.textToHtml = textToHtml;
    }

    /**
     * Renders {@code articles} through the non-empty wiki {@code template}, falling back to the default HTML when
     * the iteration markers are malformed.
     */
    String render( final String template, final List< ArticleSummary > articles ) {
        // Check if template uses iteration markers
        if ( template.contains( "%%ARTICLE_START%%" ) && template.contains( "%%ARTICLE_END%%" ) ) {
            return renderIterativeTemplate( articles, template );
        }

        // Simple template - just render the whole list
        return renderSimpleTemplate( articles, template );
    }

    /** The warn-and-fall-back tail shared by every template mode: wiki markup to HTML, or the markup itself. */
    private String renderOrFallback( final String markup, final String what ) {
        if ( textToHtml != null ) {
            try {
                return textToHtml.apply( markup );
            } catch ( final Exception e ) {
                LOG.warn( "Error rendering {} to HTML: {}", what, e.getMessage() );
            }
        }
        return markup;
    }

    /**
     * Renders using iterative template with %%ARTICLE_START%% and %%ARTICLE_END%% markers.
     */
    private String renderIterativeTemplate( final List<ArticleSummary> articles, final String templateText ) {
        // Extract the parts of the template
        final int startMarker = templateText.indexOf( "%%ARTICLE_START%%" );
        final int endMarker = templateText.indexOf( "%%ARTICLE_END%%" );

        if ( startMarker < 0 || endMarker < 0 || endMarker <= startMarker ) {
            return renderDefaultHtml( articles );
        }

        final String header = templateText.substring( 0, startMarker );
        final String articleTemplate = templateText.substring( startMarker + "%%ARTICLE_START%%".length(), endMarker );
        final String footer = templateText.substring( endMarker + "%%ARTICLE_END%%".length() );

        final StringBuilder result = new StringBuilder();
        result.append( renderTemplatePart( header, null, 0, articles.size() ) );

        for ( int i = 0; i < articles.size(); i++ ) {
            result.append( renderTemplatePart( articleTemplate, articles.get( i ), i + 1, articles.size() ) );
        }

        result.append( renderTemplatePart( footer, null, 0, articles.size() ) );

        // Render the wiki markup to HTML
        return renderOrFallback( result.toString(), "template" );
    }

    /**
     * Renders a simple template (no iteration markers) with global placeholders.
     */
    private String renderSimpleTemplate( final List<ArticleSummary> articles, final String templateText ) {
        String result = templateText;
        result = result.replace( "%%ARTICLE_COUNT%%", String.valueOf( articles.size() ) );

        // Build a simple list for the template
        final StringBuilder articleList = new StringBuilder();
        for ( final ArticleSummary article : articles ) {
            articleList.append( "* [" ).append( article.getTitle() ).append('|').append( article.getName() ).append( "]\n" );
        }
        result = result.replace( "%%ARTICLE_LIST%%", articleList.toString() );

        // Render to HTML
        return renderOrFallback( result, "simple template" );
    }

    /**
     * Replaces placeholders in a template part with article data.
     */
    private String renderTemplatePart( final String template, final ArticleSummary article, final int index, final int total ) {
        String result = template;

        // Global placeholders
        result = result.replace( "%%ARTICLE_COUNT%%", String.valueOf( total ) );

        if ( article != null ) {
            // Article-specific placeholders
            result = result.replace( "%%NAME%%", article.getName() != null ? article.getName() : "" );
            result = result.replace( "%%TITLE%%", article.getTitle() != null ? article.getTitle() : "" );
            result = result.replace( "%%AUTHOR%%", article.getAuthor() != null ? article.getAuthor() : "Anonymous" );
            result = result.replace( "%%URL%%", article.getUrl() != null ? article.getUrl() : "" );
            result = result.replace( "%%EXCERPT%%", article.getExcerpt() != null ? article.getExcerpt() : "" );
            result = result.replace( "%%CHANGENOTE%%", article.getChangeNote() != null ? article.getChangeNote() : "" );
            result = result.replace( "%%INDEX%%", String.valueOf( index ) );

            // Date formatting using thread-safe DateTimeFormatter
            if ( article.getLastModified() != null ) {
                final var zonedDateTime = article.getLastModified().toInstant().atZone( ZoneId.systemDefault() );
                result = result.replace( "%%DATE%%", ISO_DATE_FORMATTER.format( zonedDateTime ) );
                result = result.replace( "%%DATE_FULL%%", FULL_DATE_FORMATTER.format( zonedDateTime ) );
            } else {
                result = result.replace( "%%DATE%%", "" );
                result = result.replace( "%%DATE_FULL%%", "" );
            }
        }

        return result;
    }

    /**
     * Renders articles using default HTML formatting.
     */
    String renderDefaultHtml( final List<ArticleSummary> articles ) {
        if ( articles == null || articles.isEmpty() ) {
            return "<p>No recent articles found.</p>";
        }

        final StringBuilder sb = new StringBuilder();
        sb.append( "<div class=\"recent-articles\">\n" );

        for ( final ArticleSummary article : articles ) {
            sb.append( "  <div class=\"article-card\">\n" );
            sb.append( "    <h3><a href=\"" ).append( TextUtil.escapeHtml( article.getUrl() ) ).append( "\">" );
            sb.append( TextUtil.escapeHtml( article.getTitle() ) ).append( "</a></h3>\n" );

            sb.append( "    <p class=\"article-meta\">" );
            if ( article.getAuthor() != null ) {
                sb.append( TextUtil.escapeHtml( article.getAuthor() ) );
            }
            if ( article.getLastModified() != null ) {
                sb.append( " &middot; " ).append( article.getLastModified() );
            }
            sb.append( "</p>\n" );

            if ( article.getExcerpt() != null ) {
                sb.append( "    <p class=\"article-excerpt\">" ).append( TextUtil.escapeHtml( article.getExcerpt() ) ).append( "</p>\n" );
            }

            sb.append( "  </div>\n" );
        }

        sb.append( "</div>\n" );
        return sb.toString();
    }
}
