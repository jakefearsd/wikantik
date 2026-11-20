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

import com.wikantik.parser.markdown.ReaderText;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import com.wikantik.api.core.Context;
import com.wikantik.api.core.ContextEnum;
import com.wikantik.api.core.Engine;
import com.wikantik.api.core.Page;
import com.wikantik.api.core.Session;
import com.wikantik.api.identity.IdentityDisplay;
import com.wikantik.api.identity.PublicIdentity;
import com.wikantik.WikiSession;
import com.wikantik.attachment.Attachment;
import com.wikantik.auth.permissions.PermissionFilter;
import com.wikantik.auth.identity.PublicIdentities;
import com.wikantik.api.managers.PageManager;
import com.wikantik.page.subsystem.PageSubsystemBridge;
import com.wikantik.api.managers.SystemPageRegistry;
import com.wikantik.core.subsystem.CoreSubsystemBridge;
import com.wikantik.parser.RenderDepth;
import com.wikantik.render.RenderingManager;
import com.wikantik.render.subsystem.RenderingSubsystemBridge;
import com.wikantik.util.TextUtil;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.GregorianCalendar;

import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import java.util.function.Supplier;
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


    private Engine engine;
    private PageManager pageManager;
    private RenderingManager renderingManager;
    private SystemPageRegistry systemPageRegistry;
    private int cacheTTL;
    private List<Pattern> excludePatterns;
    /** Test seam: replaces the engine-backed anonymous view check. */
    private Predicate< String > guestCanViewOverride;
    /** Test seam: replaces the engine-backed identity lookup. */
    private Supplier< PublicIdentity > publicIdentityOverride;

    // Simple cache: query hash -> (timestamp, results)
    private final ConcurrentHashMap<Integer, CacheEntry> cache = new ConcurrentHashMap<>();

    /**
     * Constructor with explicit dependency injection for testability.
     *
     * @param engine             the wiki engine
     * @param pageManager        the page manager for page retrieval
     * @param renderingManager   the rendering manager for HTML generation
     * @param systemPageRegistry the system page registry for exclusion checks
     */
    public DefaultRecentArticlesManager( final Engine engine,
                                         final PageManager pageManager,
                                         final RenderingManager renderingManager,
                                         final SystemPageRegistry systemPageRegistry ) {
        this.engine = engine;
        this.pageManager = pageManager;
        this.renderingManager = renderingManager;
        this.systemPageRegistry = systemPageRegistry;
    }

    /**
     * Test seam: supplies the anonymous view check and the identity lookup instead of deriving them from the
     * engine (a Mockito {@code WikiEngine} cannot build a guest session). Not used in production, where the
     * no-argument SPI constructor plus {@link #initialize} give the engine-backed defaults.
     */
    DefaultRecentArticlesManager( final Engine engine,
                                  final PageManager pageManager,
                                  final RenderingManager renderingManager,
                                  final SystemPageRegistry systemPageRegistry,
                                  final Predicate< String > guestCanView,
                                  final Supplier< PublicIdentity > publicIdentity ) {
        this( engine, pageManager, renderingManager, systemPageRegistry );
        this.guestCanViewOverride = guestCanView;
        this.publicIdentityOverride = publicIdentity;
    }

    /**
     * No-arg constructor for SPI/reflection-based instantiation.
     * Fields are populated in {@link #initialize(Engine, Properties)}.
     */
    public DefaultRecentArticlesManager() {
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void initialize( final Engine newEngine, final Properties props ) {
        this.engine = newEngine;
        this.pageManager = PageSubsystemBridge.fromLegacyEngine( newEngine ).pages();
        this.renderingManager = RenderingSubsystemBridge.fromLegacyEngine( newEngine ).renderingManager();
        this.systemPageRegistry = CoreSubsystemBridge.fromLegacyEngine( newEngine ).systemPageRegistry();
        cacheTTL = TextUtil.getIntegerProperty( props, PROP_CACHE_TTL, DEFAULT_CACHE_TTL );
        final int defaultCount = TextUtil.getIntegerProperty( props, PROP_DEFAULT_COUNT, RecentArticlesQuery.DEFAULT_COUNT );
        final int defaultExcerptLength = TextUtil.getIntegerProperty( props, PROP_DEFAULT_EXCERPT_LENGTH, RecentArticlesQuery.DEFAULT_EXCERPT_LENGTH );

        // Parse additional exclude patterns from properties
        excludePatterns = new ArrayList<>();
        final String excludePatternsStr = props.getProperty( PROP_EXCLUDE_PATTERNS );
        if ( excludePatternsStr != null && !excludePatternsStr.isEmpty() ) {
            for ( final String pattern : excludePatternsStr.split( "," ) ) {
                try {
                    excludePatterns.add( Pattern.compile( pattern.trim() ) );
                } catch ( final PatternSyntaxException e ) {
                    LOG.warn( "Invalid exclude pattern '{}': {}", pattern, e.getMessage() );
                }
            }
        }

        LOG.info( "RecentArticlesManager initialized: cacheTTL={}s, defaultCount={}, defaultExcerptLength={}",
                  cacheTTL, defaultCount, defaultExcerptLength );
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
        final CacheEntry cached = cache.get( cacheKey );
        if ( cached != null && !cached.isExpired( cacheTTL ) ) {
            LOG.debug( "Cache hit for query: {}", query );
            return cached.getResults();
        }

        // Build fresh results
        LOG.debug( "Building fresh results for query: {}", query );
        final List<ArticleSummary> results = buildRecentArticles( context, query );

        // Update cache
        cache.put( cacheKey, new CacheEntry( results ) );

        return results;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void clearCache() {
        cache.clear();
        LOG.debug( "RecentArticles cache cleared" );
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean hasTemplatePage() {
        return pageManager != null && pageManager.wikiPageExists( TEMPLATE_PAGE_NAME );
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String renderWithTemplate( final Context context, final List<ArticleSummary> articles ) {
        final ArticleTemplateRenderer renderer = templateRenderer( context );
        if ( articles == null || articles.isEmpty() ) {
            return renderer.renderDefaultHtml( articles );
        }

        if ( !hasTemplatePage() ) {
            return renderer.renderDefaultHtml( articles );
        }

        try {
            return renderWithWikiTemplate( renderer, articles );
        } catch ( final Exception e ) {
            LOG.warn( "Error rendering with template, falling back to default: {}", e.getMessage() );
            return renderer.renderDefaultHtml( articles );
        }
    }

    /** The renderer for one request; markup is left unrendered when there is no rendering manager. */
    private ArticleTemplateRenderer templateRenderer( final Context context ) {
        return new ArticleTemplateRenderer( renderingManager == null
                ? null
                : markup -> renderingManager.textToHTML( context, markup ) );
    }

    /**
     * Renders articles using a wiki template page.
     * The template page can use special placeholders that get replaced with article data.
     */
    private String renderWithWikiTemplate( final ArticleTemplateRenderer renderer, final List<ArticleSummary> articles ) {
        final String templateText = pageManager.getPureText( TEMPLATE_PAGE_NAME, -1 );

        if ( templateText == null || templateText.isEmpty() ) {
            return renderer.renderDefaultHtml( articles );
        }
        return renderer.render( templateText, articles );
    }

    /**
     * Builds the list of recent articles matching the query criteria.
     */
    private List<ArticleSummary> buildRecentArticles( final Context context, final RecentArticlesQuery query ) {
        final List<ArticleSummary> results = new ArrayList<>();

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
        final Set<Page> recentChanges = pageManager.getRecentChanges( cutoffDate );

        // Results are cached once and shared by every caller (Atom feed, /api/recent-articles, both plugins), so
        // they must be safe for the least-privileged reader: only pages an anonymous visitor may view.
        final Predicate< String > guestCanView = guestCanView();
        final PublicIdentity identity = publicIdentity();

        for ( final Page page : recentChanges ) {
            // Stop if we've collected enough articles
            if ( results.size() >= query.getCount() ) {
                break;
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

            // Skip pages an anonymous visitor cannot view (before any title, excerpt or author is built)
            if ( !guestMayView( guestCanView, pageName ) ) {
                continue;
            }

            // Build article summary
            try {
                final ArticleSummary summary = buildArticleSummary( context, page, pageManager, renderingManager, query, identity );
                if ( summary != null ) {
                    results.add( summary );
                }
            } catch ( final Exception e ) {
                LOG.warn( "Error building summary for page '{}': {}", pageName, e.getMessage() );
            }
        }

        return Collections.unmodifiableList( results );
    }

    /** The anonymous view check: a request-free guest session, as the public ontology and knowledge-MCP surfaces use. */
    private Predicate< String > guestCanView() {
        if ( guestCanViewOverride != null ) {
            return guestCanViewOverride;
        }
        final Session guest = WikiSession.guestSession( engine );
        final PermissionFilter filter = new PermissionFilter( engine );
        return pageName -> filter.canAccessQuietly( guest, pageName, "view" );
    }

    /** A check that fails hides the page (and is logged); it never fails the list and never leaks. */
    private static boolean guestMayView( final Predicate< String > guestCanView, final String pageName ) {
        try {
            return guestCanView.test( pageName );
        } catch ( final RuntimeException e ) {
            LOG.warn( "Anonymous view check failed for '{}'; leaving it out of recent articles: {}",
                      pageName, e.getMessage(), e );
            return false;
        }
    }

    /**
     * Resolved once per build (the Auth subsystem is built after this manager initialises). Never null in
     * production: before the service is wired {@link PublicIdentities} answers with a masking-only fallback, so a
     * stored login is never published as an author.
     */
    private PublicIdentity publicIdentity() {
        return publicIdentityOverride != null
                ? publicIdentityOverride.get()
                : PublicIdentities.forEngine( engine );
    }

    /**
     * Builds an ArticleSummary for a single page.
     */
    private ArticleSummary buildArticleSummary( final Context context,
                                                 final Page page,
                                                 final PageManager pageManager,
                                                 final RenderingManager renderingManager,
                                                 final RecentArticlesQuery query,
                                                 final PublicIdentity identity ) {
        final String pageName = page.getName();

        // Get page content for title and excerpt extraction; a commented-out H1 is not the page's title
        final String pageText = ReaderText.withoutComments( pageManager.getPureText( page ) );
        String renderedHtml = null;
        if ( query.isIncludeExcerpt() && renderingManager != null ) {
            try {
                // Rendered one level deep so server fences stay placeholders: the HTML only feeds the plain-text title
                // and excerpt, and drawing every listed page's diagrams would spend a PlantUML budget per article.
                renderedHtml = RenderDepth.nested( () -> renderingManager.getHTML( pageName, page.getVersion() ) );
            } catch ( final Exception e ) {
                LOG.debug( "Could not render page '{}': {}", pageName, e.getMessage() );
            }
        }

        // Extract title
        final String title = extractTitle( pageName, pageText, renderedHtml, renderingManager );

        // Extract excerpt
        String excerpt = null;
        if ( query.isIncludeExcerpt() && renderedHtml != null ) {
            excerpt = ArticleTextExtractor.excerpt( renderedHtml, query.getExcerptLength() );
        }

        // Build the URL
        final String url = context.getURL( ContextEnum.PAGE_VIEW.getRequestContext(), pageName );

        // Get change note
        final String changeNote = page.getAttribute( Page.CHANGENOTE );

        return new ArticleSummary.Builder()
            .name( pageName )
            .title( title )
            .author( IdentityDisplay.ofAuthor( identity, page.getAuthor() ) )
            .lastModified( page.getLastModified() )
            .excerpt( excerpt )
            .changeNote( IdentityDisplay.changeNote( identity, changeNote ) )
            .version( page.getVersion() )
            .url( url )
            .size( page.getSize() )
            .build();
    }

    /**
     * Display title. Priority: H1 heading > beautified page name > page name.
     */
    private static String extractTitle( final String pageName,
                                        final String pageText,
                                        final String renderedHtml,
                                        final RenderingManager renderingManager ) {
        final String h1 = ArticleTextExtractor.title( pageText, renderedHtml );
        if ( h1 != null ) {
            return h1;
        }
        // Fall back to beautified page name, then (last resort) the page name itself
        return renderingManager != null ? renderingManager.beautifyTitle( pageName ) : pageName;
    }

    /**
     * Checks if a page should be excluded from results.
     */
    private boolean isExcluded( final String pageName, final Pattern includePattern, final Pattern excludePattern ) {
        // Check centralized system page registry
        if ( systemPageRegistry != null && systemPageRegistry.isSystemPage( pageName ) ) {
            return true;
        }

        // Check configured exclude patterns
        for ( final Pattern pattern : excludePatterns ) {
            if ( pattern.matcher( pageName ).matches() ) {
                return true;
            }
        }

        // Excluded if either exclude pattern matches, or include pattern is set and doesn't match.
        return ( excludePattern != null && excludePattern.matcher( pageName ).matches() )
                || ( includePattern != null && !includePattern.matcher( pageName ).matches() );
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
