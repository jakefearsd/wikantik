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

import org.apache.wiki.api.core.Context;
import org.apache.wiki.api.engine.Initializable;

import java.util.List;

/**
 * Manager for retrieving recent article summaries. This service provides a unified way to
 * query for recently modified wiki articles with support for filtering, caching, and
 * excerpt generation.
 *
 * <p>The RecentArticlesManager is designed to support multiple consumers:
 * <ul>
 *   <li>REST API endpoints for modern SPA frontends</li>
 *   <li>Wiki plugins for embedding in wiki pages</li>
 *   <li>JSP tags for traditional template rendering</li>
 * </ul>
 *
 * <p>Results are cached with a configurable TTL to improve performance on high-traffic wikis.
 *
 * @since 3.0.7
 */
public interface RecentArticlesManager extends Initializable {

    /** Property name for cache TTL in seconds. Default is 60. */
    String PROP_CACHE_TTL = "wikantik.recentArticles.cacheTTL";

    /** Property name for default article count. Default is 10. */
    String PROP_DEFAULT_COUNT = "wikantik.recentArticles.defaultCount";

    /** Property name for default excerpt length. Default is 200. */
    String PROP_DEFAULT_EXCERPT_LENGTH = "wikantik.recentArticles.defaultExcerptLength";

    /** Property name for excluded page patterns (comma-separated regex). */
    String PROP_EXCLUDE_PATTERNS = "wikantik.recentArticles.excludePatterns";

    /** The wiki page name for the HTML template used by the plugin. */
    String TEMPLATE_PAGE_NAME = "RecentArticlesTemplate";

    /**
     * Retrieves a list of recent articles matching the specified query criteria.
     *
     * @param context The wiki context for permission checking and URL generation.
     * @param query The query parameters specifying count, date range, filters, etc.
     * @return A list of ArticleSummary objects, ordered by last modification date (most recent first).
     */
    List<ArticleSummary> getRecentArticles( Context context, RecentArticlesQuery query );

    /**
     * Retrieves a list of recent articles using default query parameters.
     *
     * @param context The wiki context for permission checking and URL generation.
     * @return A list of ArticleSummary objects with default count and settings.
     */
    default List<ArticleSummary> getRecentArticles( Context context ) {
        return getRecentArticles( context, new RecentArticlesQuery() );
    }

    /**
     * Clears the internal cache, forcing fresh data retrieval on the next request.
     * Useful when pages have been modified and immediate cache refresh is needed.
     */
    void clearCache();

    /**
     * Checks if a template page exists for rendering article summaries.
     *
     * @return true if the RecentArticlesTemplate page exists and can be used for rendering.
     */
    boolean hasTemplatePage();

    /**
     * Renders article summaries using the wiki template page.
     * Falls back to default rendering if no template page exists.
     *
     * @param context The wiki context for rendering.
     * @param articles The articles to render.
     * @return HTML string with rendered article summaries.
     */
    String renderWithTemplate( Context context, List<ArticleSummary> articles );
}
