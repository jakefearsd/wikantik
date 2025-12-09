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
package org.apache.wiki.content;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.wiki.api.core.Context;
import org.apache.wiki.api.core.ContextEnum;
import org.apache.wiki.api.core.Engine;
import org.apache.wiki.api.core.Page;
import org.apache.wiki.attachment.Attachment;
import org.apache.wiki.pages.PageManager;
import org.apache.wiki.render.RenderingManager;
import org.apache.wiki.util.TextUtil;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Default implementation of {@link RecentArticlesManager}.
 *
 * <p>This implementation provides:
 * <ul>
 *   <li>Caching of results with configurable TTL</li>
 *   <li>Title extraction from H1 headings or beautified page names</li>
 *   <li>Excerpt generation from rendered HTML content</li>
 *   <li>Configurable exclusion patterns for system pages</li>
 *   <li>Template-based rendering support</li>
 * </ul>
 *
 * @since 3.0.7
 */
public class DefaultRecentArticlesManager implements RecentArticlesManager {

    private static final Logger LOG = LogManager.getLogger( DefaultRecentArticlesManager.class );

    /** Default cache TTL in seconds. */
    private static final int DEFAULT_CACHE_TTL = 60;

    /** Default system pages to exclude. */
    private static final Set<String> DEFAULT_EXCLUDED_PAGES = new HashSet<>( Arrays.asList(
        "Main", "LeftMenu", "LeftMenuFooter", "MoreMenu", "PageHeader", "PageFooter",
        "LoginHelp", "EditPageHelp", "TextFormattingRules", "FindPageHelp",
        "RecentArticlesTemplate"
    ) );

    /** Regex to match H1 headings in wiki markup (both !!! and # styles). */
    private static final Pattern H1_WIKI_PATTERN = Pattern.compile( "^\\s*(?:!!!|#)\\s*(.+?)\\s*$", Pattern.MULTILINE );

    /** Regex to match H1 in rendered HTML. */
    private static final Pattern H1_HTML_PATTERN = Pattern.compile( "<h1[^>]*>([^<]+)</h1>", Pattern.CASE_INSENSITIVE );

    /** Regex for stripping HTML tags. */
    private static final Pattern HTML_TAG_PATTERN = Pattern.compile( "<[^>]+>" );

    /** Regex for collapsing whitespace. */
    private static final Pattern WHITESPACE_PATTERN = Pattern.compile( "\\s+" );

    /** Thread-safe date formatter for ISO format (yyyy-MM-dd). */
    private static final DateTimeFormatter ISO_DATE_FORMATTER = DateTimeFormatter.ofPattern( "yyyy-MM-dd" );

    /** Thread-safe date formatter for full format (MMMM d, yyyy). */
    private static final DateTimeFormatter FULL_DATE_FORMATTER = DateTimeFormatter.ofPattern( "MMMM d, yyyy", Locale.ENGLISH );

    /** Regex pattern for numeric HTML entities (&#123; or &#x1F4A1;). */
    private static final Pattern NUMERIC_ENTITY_PATTERN = Pattern.compile( "&#(x?)([0-9a-fA-F]+);" );

    private Engine m_engine;
    private int m_cacheTTL;
    private int m_defaultCount;
    private int m_defaultExcerptLength;
    private Set<String> m_excludedPages;
    private List<Pattern> m_excludePatterns;

    // Simple cache: query hash -> (timestamp, results)
    private final ConcurrentHashMap<Integer, CacheEntry> m_cache = new ConcurrentHashMap<>();

    /**
     * {@inheritDoc}
     */
    @Override
    public void initialize( final Engine engine, final Properties props ) {
        m_engine = engine;

        m_cacheTTL = TextUtil.getIntegerProperty( props, PROP_CACHE_TTL, DEFAULT_CACHE_TTL );
        m_defaultCount = TextUtil.getIntegerProperty( props, PROP_DEFAULT_COUNT, RecentArticlesQuery.DEFAULT_COUNT );
        m_defaultExcerptLength = TextUtil.getIntegerProperty( props, PROP_DEFAULT_EXCERPT_LENGTH, RecentArticlesQuery.DEFAULT_EXCERPT_LENGTH );

        // Initialize excluded pages set
        m_excludedPages = new HashSet<>( DEFAULT_EXCLUDED_PAGES );

        // Parse additional exclude patterns from properties
        m_excludePatterns = new ArrayList<>();
        final String excludePatternsStr = props.getProperty( PROP_EXCLUDE_PATTERNS );
        if ( excludePatternsStr != null && !excludePatternsStr.isEmpty() ) {
            for ( final String pattern : excludePatternsStr.split( "," ) ) {
                try {
                    m_excludePatterns.add( Pattern.compile( pattern.trim() ) );
                } catch ( final PatternSyntaxException e ) {
                    LOG.warn( "Invalid exclude pattern '{}': {}", pattern, e.getMessage() );
                }
            }
        }

        LOG.info( "RecentArticlesManager initialized: cacheTTL={}s, defaultCount={}, defaultExcerptLength={}",
                  m_cacheTTL, m_defaultCount, m_defaultExcerptLength );
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public List<ArticleSummary> getRecentArticles( final Context context, final RecentArticlesQuery query ) {
        if ( context == null || query == null ) {
            return Collections.emptyList();
        }

        // Check cache first
        final int cacheKey = query.hashCode();
        final CacheEntry cached = m_cache.get( cacheKey );
        if ( cached != null && !cached.isExpired( m_cacheTTL ) ) {
            LOG.debug( "Cache hit for query: {}", query );
            return cached.getResults();
        }

        // Build fresh results
        LOG.debug( "Building fresh results for query: {}", query );
        final List<ArticleSummary> results = buildRecentArticles( context, query );

        // Update cache
        m_cache.put( cacheKey, new CacheEntry( results ) );

        return results;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void clearCache() {
        m_cache.clear();
        LOG.debug( "RecentArticles cache cleared" );
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean hasTemplatePage() {
        final PageManager pageManager = m_engine.getManager( PageManager.class );
        return pageManager != null && pageManager.wikiPageExists( TEMPLATE_PAGE_NAME );
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String renderWithTemplate( final Context context, final List<ArticleSummary> articles ) {
        if ( articles == null || articles.isEmpty() ) {
            return renderDefaultHtml( context, articles );
        }

        if ( !hasTemplatePage() ) {
            return renderDefaultHtml( context, articles );
        }

        try {
            return renderWithWikiTemplate( context, articles );
        } catch ( final Exception e ) {
            LOG.warn( "Error rendering with template, falling back to default: {}", e.getMessage() );
            return renderDefaultHtml( context, articles );
        }
    }

    /**
     * Renders articles using a wiki template page.
     * The template page can use special placeholders that get replaced with article data.
     */
    private String renderWithWikiTemplate( final Context context, final List<ArticleSummary> articles ) {
        final PageManager pageManager = m_engine.getManager( PageManager.class );
        final String templateText = pageManager.getPureText( TEMPLATE_PAGE_NAME, -1 );

        if ( templateText == null || templateText.isEmpty() ) {
            return renderDefaultHtml( context, articles );
        }

        // Check if template uses iteration markers
        if ( templateText.contains( "%%ARTICLE_START%%" ) && templateText.contains( "%%ARTICLE_END%%" ) ) {
            return renderIterativeTemplate( context, articles, templateText );
        }

        // Simple template - just render the whole list
        return renderSimpleTemplate( context, articles, templateText );
    }

    /**
     * Renders using iterative template with %%ARTICLE_START%% and %%ARTICLE_END%% markers.
     */
    private String renderIterativeTemplate( final Context context, final List<ArticleSummary> articles, final String templateText ) {
        // Extract the parts of the template
        final int startMarker = templateText.indexOf( "%%ARTICLE_START%%" );
        final int endMarker = templateText.indexOf( "%%ARTICLE_END%%" );

        if ( startMarker < 0 || endMarker < 0 || endMarker <= startMarker ) {
            return renderDefaultHtml( context, articles );
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
        final RenderingManager renderingManager = m_engine.getManager( RenderingManager.class );
        if ( renderingManager != null ) {
            try {
                return renderingManager.textToHTML( context, result.toString() );
            } catch ( final Exception e ) {
                LOG.warn( "Error rendering template to HTML: {}", e.getMessage() );
            }
        }

        return result.toString();
    }

    /**
     * Renders a simple template (no iteration markers) with global placeholders.
     */
    private String renderSimpleTemplate( final Context context, final List<ArticleSummary> articles, final String templateText ) {
        String result = templateText;
        result = result.replace( "%%ARTICLE_COUNT%%", String.valueOf( articles.size() ) );

        // Build a simple list for the template
        final StringBuilder articleList = new StringBuilder();
        for ( final ArticleSummary article : articles ) {
            articleList.append( "* [" ).append( article.getTitle() ).append( "|" ).append( article.getName() ).append( "]\n" );
        }
        result = result.replace( "%%ARTICLE_LIST%%", articleList.toString() );

        // Render to HTML
        final RenderingManager renderingManager = m_engine.getManager( RenderingManager.class );
        if ( renderingManager != null ) {
            try {
                return renderingManager.textToHTML( context, result );
            } catch ( final Exception e ) {
                LOG.warn( "Error rendering simple template to HTML: {}", e.getMessage() );
            }
        }

        return result;
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
     * Builds the list of recent articles matching the query criteria.
     */
    private List<ArticleSummary> buildRecentArticles( final Context context, final RecentArticlesQuery query ) {
        final List<ArticleSummary> results = new ArrayList<>();
        final PageManager pageManager = m_engine.getManager( PageManager.class );
        final RenderingManager renderingManager = m_engine.getManager( RenderingManager.class );

        if ( pageManager == null ) {
            LOG.warn( "PageManager not available" );
            return results;
        }

        // Calculate the cutoff date
        final Calendar sinceDate = new GregorianCalendar();
        sinceDate.add( Calendar.DAY_OF_MONTH, -query.getSinceDays() );
        final Date cutoffDate = sinceDate.getTime();

        // Compile optional include/exclude patterns
        final Pattern includePattern = compilePattern( query.getIncludePattern() );
        final Pattern excludePattern = compilePattern( query.getExcludePattern() );

        // Get recent changes (already sorted by date, most recent first)
        final Set<Page> recentChanges = pageManager.getRecentChanges();

        for ( final Page page : recentChanges ) {
            // Stop if we've collected enough articles
            if ( results.size() >= query.getCount() ) {
                break;
            }

            // Skip if older than cutoff date
            if ( page.getLastModified() != null && page.getLastModified().before( cutoffDate ) ) {
                break; // Since pages are sorted by date, no need to continue
            }

            // Skip attachments
            if ( page instanceof Attachment ) {
                continue;
            }

            final String pageName = page.getName();

            // Skip excluded pages
            if ( isExcluded( pageName, includePattern, excludePattern ) ) {
                continue;
            }

            // Build article summary
            try {
                final ArticleSummary summary = buildArticleSummary( context, page, pageManager, renderingManager, query );
                if ( summary != null ) {
                    results.add( summary );
                }
            } catch ( final Exception e ) {
                LOG.warn( "Error building summary for page '{}': {}", pageName, e.getMessage() );
            }
        }

        return Collections.unmodifiableList( results );
    }

    /**
     * Builds an ArticleSummary for a single page.
     */
    private ArticleSummary buildArticleSummary( final Context context,
                                                 final Page page,
                                                 final PageManager pageManager,
                                                 final RenderingManager renderingManager,
                                                 final RecentArticlesQuery query ) {
        final String pageName = page.getName();

        // Get page content for title and excerpt extraction
        final String pageText = pageManager.getPureText( page );
        String renderedHtml = null;
        if ( query.isIncludeExcerpt() && renderingManager != null ) {
            try {
                renderedHtml = renderingManager.getHTML( pageName, page.getVersion() );
            } catch ( final Exception e ) {
                LOG.debug( "Could not render page '{}': {}", pageName, e.getMessage() );
            }
        }

        // Extract title
        final String title = extractTitle( pageName, pageText, renderedHtml, renderingManager );

        // Extract excerpt
        String excerpt = null;
        if ( query.isIncludeExcerpt() && renderedHtml != null ) {
            excerpt = extractExcerpt( renderedHtml, query.getExcerptLength() );
        }

        // Build the URL
        final String url = context.getURL( ContextEnum.PAGE_VIEW.getRequestContext(), pageName );

        // Get change note
        final String changeNote = page.getAttribute( Page.CHANGENOTE );

        return new ArticleSummary.Builder()
            .name( pageName )
            .title( title )
            .author( page.getAuthor() )
            .lastModified( page.getLastModified() )
            .excerpt( excerpt )
            .changeNote( changeNote )
            .version( page.getVersion() )
            .url( url )
            .size( page.getSize() )
            .build();
    }

    /**
     * Extracts a display title from the page.
     * Priority: H1 heading > beautified page name
     */
    private String extractTitle( final String pageName,
                                  final String pageText,
                                  final String renderedHtml,
                                  final RenderingManager renderingManager ) {
        // Try to find H1 in wiki markup first
        if ( pageText != null ) {
            final var matcher = H1_WIKI_PATTERN.matcher( pageText );
            if ( matcher.find() ) {
                final String h1 = matcher.group( 1 ).trim();
                if ( !h1.isEmpty() ) {
                    return h1;
                }
            }
        }

        // Try to find H1 in rendered HTML
        if ( renderedHtml != null ) {
            final var matcher = H1_HTML_PATTERN.matcher( renderedHtml );
            if ( matcher.find() ) {
                final String h1 = stripHtml( matcher.group( 1 ) ).trim();
                if ( !h1.isEmpty() ) {
                    return h1;
                }
            }
        }

        // Fall back to beautified page name
        if ( renderingManager != null ) {
            return renderingManager.beautifyTitle( pageName );
        }

        // Last resort: just return the page name
        return pageName;
    }

    /**
     * Extracts an excerpt from rendered HTML content.
     */
    private String extractExcerpt( final String html, final int maxLength ) {
        if ( html == null || html.isEmpty() ) {
            return null;
        }

        // Strip HTML tags
        String text = stripHtml( html );

        // Collapse whitespace
        text = WHITESPACE_PATTERN.matcher( text ).replaceAll( " " ).trim();

        // Skip if too short
        if ( text.length() < 20 ) {
            return null;
        }

        // Truncate to max length
        if ( text.length() > maxLength ) {
            // Find a good break point (word boundary)
            int breakPoint = text.lastIndexOf( ' ', maxLength );
            if ( breakPoint < maxLength / 2 ) {
                breakPoint = maxLength;
            }
            text = text.substring( 0, breakPoint ).trim() + "...";
        }

        return text;
    }

    /**
     * Strips HTML tags from a string and decodes HTML entities.
     */
    private String stripHtml( final String html ) {
        if ( html == null ) {
            return "";
        }

        // Remove HTML tags first
        String text = HTML_TAG_PATTERN.matcher( html ).replaceAll( "" );

        // Decode numeric HTML entities (&#123; or &#x1F4A1;)
        text = decodeNumericEntities( text );

        // Decode named HTML entities (most common ones)
        text = text
            // Whitespace and special characters
            .replace( "&nbsp;", " " )
            .replace( "&ensp;", " " )
            .replace( "&emsp;", " " )
            .replace( "&thinsp;", " " )
            // Basic entities (process &amp; last to avoid double-decoding)
            .replace( "&lt;", "<" )
            .replace( "&gt;", ">" )
            .replace( "&quot;", "\"" )
            .replace( "&apos;", "'" )
            // Punctuation and symbols
            .replace( "&ndash;", "–" )
            .replace( "&mdash;", "—" )
            .replace( "&lsquo;", "'" )
            .replace( "&rsquo;", "'" )
            .replace( "&ldquo;", "\u201C" )  // left double quotation mark
            .replace( "&rdquo;", "\u201D" )  // right double quotation mark
            .replace( "&hellip;", "…" )
            .replace( "&bull;", "•" )
            .replace( "&middot;", "·" )
            // Copyright and trademark
            .replace( "&copy;", "©" )
            .replace( "&reg;", "®" )
            .replace( "&trade;", "™" )
            // Currency
            .replace( "&cent;", "¢" )
            .replace( "&pound;", "£" )
            .replace( "&euro;", "€" )
            .replace( "&yen;", "¥" )
            // Math symbols
            .replace( "&times;", "×" )
            .replace( "&divide;", "÷" )
            .replace( "&plusmn;", "±" )
            .replace( "&deg;", "°" )
            // Process &amp; last to avoid double-decoding issues
            .replace( "&amp;", "&" );

        return text;
    }

    /**
     * Decodes numeric HTML entities (both decimal and hexadecimal).
     * Examples: &#65; -> A, &#x41; -> A
     */
    private String decodeNumericEntities( final String text ) {
        if ( text == null || !text.contains( "&#" ) ) {
            return text;
        }

        final var matcher = NUMERIC_ENTITY_PATTERN.matcher( text );
        final var result = new StringBuilder();

        while ( matcher.find() ) {
            final boolean isHex = !matcher.group( 1 ).isEmpty();
            final String numStr = matcher.group( 2 );
            try {
                final int codePoint = Integer.parseInt( numStr, isHex ? 16 : 10 );
                if ( Character.isValidCodePoint( codePoint ) ) {
                    matcher.appendReplacement( result, new String( Character.toChars( codePoint ) ) );
                } else {
                    // Invalid code point, keep original
                    matcher.appendReplacement( result, matcher.group( 0 ) );
                }
            } catch ( final NumberFormatException e ) {
                // Keep original if parsing fails
                matcher.appendReplacement( result, matcher.group( 0 ) );
            }
        }
        matcher.appendTail( result );

        return result.toString();
    }

    /**
     * Checks if a page should be excluded from results.
     */
    private boolean isExcluded( final String pageName, final Pattern includePattern, final Pattern excludePattern ) {
        // Check if page is in the static exclusion list
        if ( m_excludedPages.contains( pageName ) ) {
            return true;
        }

        // Check configured exclude patterns
        for ( final Pattern pattern : m_excludePatterns ) {
            if ( pattern.matcher( pageName ).matches() ) {
                return true;
            }
        }

        // Check query-specific exclude pattern
        if ( excludePattern != null && excludePattern.matcher( pageName ).matches() ) {
            return true;
        }

        // Check query-specific include pattern (if set, page must match)
        if ( includePattern != null && !includePattern.matcher( pageName ).matches() ) {
            return true;
        }

        return false;
    }

    /**
     * Compiles a regex pattern, returning null if the pattern is null or invalid.
     */
    private Pattern compilePattern( final String patternStr ) {
        if ( patternStr == null || patternStr.isEmpty() ) {
            return null;
        }
        try {
            return Pattern.compile( patternStr );
        } catch ( final PatternSyntaxException e ) {
            LOG.warn( "Invalid regex pattern '{}': {}", patternStr, e.getMessage() );
            return null;
        }
    }

    /**
     * Renders articles using default HTML formatting.
     */
    private String renderDefaultHtml( final Context context, final List<ArticleSummary> articles ) {
        if ( articles == null || articles.isEmpty() ) {
            return "<p>No recent articles found.</p>";
        }

        final StringBuilder sb = new StringBuilder();
        sb.append( "<div class=\"recent-articles\">\n" );

        for ( final ArticleSummary article : articles ) {
            sb.append( "  <div class=\"article-card\">\n" );
            sb.append( "    <h3><a href=\"" ).append( escapeHtml( article.getUrl() ) ).append( "\">" );
            sb.append( escapeHtml( article.getTitle() ) ).append( "</a></h3>\n" );

            sb.append( "    <p class=\"article-meta\">" );
            if ( article.getAuthor() != null ) {
                sb.append( escapeHtml( article.getAuthor() ) );
            }
            if ( article.getLastModified() != null ) {
                sb.append( " &middot; " ).append( article.getLastModified() );
            }
            sb.append( "</p>\n" );

            if ( article.getExcerpt() != null ) {
                sb.append( "    <p class=\"article-excerpt\">" ).append( escapeHtml( article.getExcerpt() ) ).append( "</p>\n" );
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

    /**
     * Simple cache entry with timestamp.
     */
    private static class CacheEntry {
        private final long timestamp;
        private final List<ArticleSummary> results;

        CacheEntry( final List<ArticleSummary> results ) {
            this.timestamp = System.currentTimeMillis();
            this.results = results;
        }

        boolean isExpired( final int ttlSeconds ) {
            return System.currentTimeMillis() - timestamp > ttlSeconds * 1000L;
        }

        List<ArticleSummary> getResults() {
            return results;
        }
    }
}
