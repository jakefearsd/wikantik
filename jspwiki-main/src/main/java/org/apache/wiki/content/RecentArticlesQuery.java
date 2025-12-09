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

import java.io.Serializable;
import java.util.Objects;

/**
 * Query parameters for retrieving recent articles.
 *
 * <p>This class uses the builder pattern for flexible query construction:
 * <pre>{@code
 * RecentArticlesQuery query = new RecentArticlesQuery()
 *     .count(20)
 *     .sinceDays(7)
 *     .includeExcerpt(true)
 *     .excerptLength(150);
 * }</pre>
 *
 * @since 3.0.7
 */
public final class RecentArticlesQuery implements Serializable {

    private static final long serialVersionUID = 1L;

    /** Default number of articles to return. */
    public static final int DEFAULT_COUNT = 10;

    /** Default number of days to look back. */
    public static final int DEFAULT_SINCE_DAYS = 30;

    /** Default excerpt length in characters. */
    public static final int DEFAULT_EXCERPT_LENGTH = 200;

    private int count = DEFAULT_COUNT;
    private int sinceDays = DEFAULT_SINCE_DAYS;
    private boolean includeExcerpt = true;
    private int excerptLength = DEFAULT_EXCERPT_LENGTH;
    private String excludePattern;
    private String includePattern;

    /**
     * Creates a new query with default parameters.
     */
    public RecentArticlesQuery() {
    }

    /**
     * Sets the maximum number of articles to return.
     *
     * @param count the maximum count (must be positive).
     * @return this query for chaining.
     * @throws IllegalArgumentException if count is not positive.
     */
    public RecentArticlesQuery count( final int count ) {
        if ( count <= 0 ) {
            throw new IllegalArgumentException( "Count must be positive: " + count );
        }
        this.count = count;
        return this;
    }

    /**
     * Sets the number of days to look back for changes.
     *
     * @param sinceDays the number of days (must be positive).
     * @return this query for chaining.
     * @throws IllegalArgumentException if sinceDays is not positive.
     */
    public RecentArticlesQuery sinceDays( final int sinceDays ) {
        if ( sinceDays <= 0 ) {
            throw new IllegalArgumentException( "SinceDays must be positive: " + sinceDays );
        }
        this.sinceDays = sinceDays;
        return this;
    }

    /**
     * Sets whether to include excerpts in the results.
     *
     * @param includeExcerpt true to include excerpts.
     * @return this query for chaining.
     */
    public RecentArticlesQuery includeExcerpt( final boolean includeExcerpt ) {
        this.includeExcerpt = includeExcerpt;
        return this;
    }

    /**
     * Sets the maximum length of excerpts in characters.
     *
     * @param excerptLength the excerpt length (must be positive).
     * @return this query for chaining.
     * @throws IllegalArgumentException if excerptLength is not positive.
     */
    public RecentArticlesQuery excerptLength( final int excerptLength ) {
        if ( excerptLength <= 0 ) {
            throw new IllegalArgumentException( "ExcerptLength must be positive: " + excerptLength );
        }
        this.excerptLength = excerptLength;
        return this;
    }

    /**
     * Sets a regex pattern for pages to exclude.
     * Pages matching this pattern will not be included in results.
     *
     * @param excludePattern a regex pattern, or null to disable.
     * @return this query for chaining.
     */
    public RecentArticlesQuery excludePattern( final String excludePattern ) {
        this.excludePattern = excludePattern;
        return this;
    }

    /**
     * Sets a regex pattern for pages to include.
     * Only pages matching this pattern will be included in results.
     *
     * @param includePattern a regex pattern, or null to include all.
     * @return this query for chaining.
     */
    public RecentArticlesQuery includePattern( final String includePattern ) {
        this.includePattern = includePattern;
        return this;
    }

    /**
     * Returns the maximum number of articles to return.
     *
     * @return the count.
     */
    public int getCount() {
        return count;
    }

    /**
     * Returns the number of days to look back.
     *
     * @return the number of days.
     */
    public int getSinceDays() {
        return sinceDays;
    }

    /**
     * Returns whether excerpts should be included.
     *
     * @return true if excerpts should be included.
     */
    public boolean isIncludeExcerpt() {
        return includeExcerpt;
    }

    /**
     * Returns the maximum excerpt length.
     *
     * @return the excerpt length in characters.
     */
    public int getExcerptLength() {
        return excerptLength;
    }

    /**
     * Returns the exclude pattern.
     *
     * @return the regex pattern for exclusion, or null.
     */
    public String getExcludePattern() {
        return excludePattern;
    }

    /**
     * Returns the include pattern.
     *
     * @return the regex pattern for inclusion, or null.
     */
    public String getIncludePattern() {
        return includePattern;
    }

    @Override
    public boolean equals( final Object o ) {
        if ( this == o ) return true;
        if ( o == null || getClass() != o.getClass() ) return false;
        final RecentArticlesQuery that = ( RecentArticlesQuery ) o;
        return count == that.count &&
               sinceDays == that.sinceDays &&
               includeExcerpt == that.includeExcerpt &&
               excerptLength == that.excerptLength &&
               Objects.equals( excludePattern, that.excludePattern ) &&
               Objects.equals( includePattern, that.includePattern );
    }

    @Override
    public int hashCode() {
        return Objects.hash( count, sinceDays, includeExcerpt, excerptLength, excludePattern, includePattern );
    }

    @Override
    public String toString() {
        return "RecentArticlesQuery{" +
               "count=" + count +
               ", sinceDays=" + sinceDays +
               ", includeExcerpt=" + includeExcerpt +
               ", excerptLength=" + excerptLength +
               ", excludePattern='" + excludePattern + '\'' +
               ", includePattern='" + includePattern + '\'' +
               '}';
    }
}
