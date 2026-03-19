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
import java.util.Date;
import java.util.Objects;

/**
 * Data transfer object representing a summary of a wiki article.
 * Contains metadata and excerpt suitable for display in article lists,
 * search results, and recent changes feeds.
 *
 * <p>This class is immutable and thread-safe. Use the {@link Builder} to construct instances.
 *
 * @since 3.0.7
 */
public final class ArticleSummary implements Serializable {

    private static final long serialVersionUID = 1L;

    private final String name;
    private final String title;
    private final String author;
    private final Date lastModified;
    private final String excerpt;
    private final String changeNote;
    private final int version;
    private final String url;
    private final long size;

    private ArticleSummary( final Builder builder ) {
        this.name = builder.name;
        this.title = builder.title;
        this.author = builder.author;
        this.lastModified = builder.lastModified != null ? new Date( builder.lastModified.getTime() ) : null;
        this.excerpt = builder.excerpt;
        this.changeNote = builder.changeNote;
        this.version = builder.version;
        this.url = builder.url;
        this.size = builder.size;
    }

    /**
     * Returns the wiki page name (e.g., "GettingStarted").
     *
     * @return the page name, never null.
     */
    public String getName() {
        return name;
    }

    /**
     * Returns the display title, derived from the first H1 heading or beautified page name.
     *
     * @return the display title, never null.
     */
    public String getTitle() {
        return title;
    }

    /**
     * Returns the author of the last modification.
     *
     * @return the author name, may be null if unknown.
     */
    public String getAuthor() {
        return author;
    }

    /**
     * Returns the date of the last modification.
     *
     * @return a copy of the last modified date, may be null.
     */
    public Date getLastModified() {
        return lastModified != null ? new Date( lastModified.getTime() ) : null;
    }

    /**
     * Returns a text excerpt from the page content.
     * HTML tags are stripped and the text is truncated to the configured length.
     *
     * @return the excerpt text, may be null if not requested or unavailable.
     */
    public String getExcerpt() {
        return excerpt;
    }

    /**
     * Returns the change note from the last modification.
     *
     * @return the change note, may be null if not provided.
     */
    public String getChangeNote() {
        return changeNote;
    }

    /**
     * Returns the current version number of the page.
     *
     * @return the version number.
     */
    public int getVersion() {
        return version;
    }

    /**
     * Returns the URL to view this article.
     *
     * @return the view URL, never null.
     */
    public String getUrl() {
        return url;
    }

    /**
     * Returns the size of the page content in bytes.
     *
     * @return the content size.
     */
    public long getSize() {
        return size;
    }

    @Override
    public boolean equals( final Object o ) {
        if ( this == o ) return true;
        if ( o == null || getClass() != o.getClass() ) return false;
        final ArticleSummary that = ( ArticleSummary ) o;
        return version == that.version &&
               Objects.equals( name, that.name ) &&
               Objects.equals( lastModified, that.lastModified );
    }

    @Override
    public int hashCode() {
        return Objects.hash( name, lastModified, version );
    }

    @Override
    public String toString() {
        return "ArticleSummary{" +
               "name='" + name + '\'' +
               ", title='" + title + '\'' +
               ", author='" + author + '\'' +
               ", lastModified=" + lastModified +
               ", version=" + version +
               '}';
    }

    /**
     * Builder for creating ArticleSummary instances.
     */
    public static class Builder {
        private String name;
        private String title;
        private String author;
        private Date lastModified;
        private String excerpt;
        private String changeNote;
        private int version;
        private String url;
        private long size;

        /**
         * Creates a new Builder instance.
         */
        public Builder() {
        }

        /**
         * Sets the page name.
         *
         * @param name the wiki page name.
         * @return this builder for chaining.
         */
        public Builder name( final String name ) {
            this.name = name;
            return this;
        }

        /**
         * Sets the display title.
         *
         * @param title the display title.
         * @return this builder for chaining.
         */
        public Builder title( final String title ) {
            this.title = title;
            return this;
        }

        /**
         * Sets the author name.
         *
         * @param author the author of the last modification.
         * @return this builder for chaining.
         */
        public Builder author( final String author ) {
            this.author = author;
            return this;
        }

        /**
         * Sets the last modified date.
         *
         * @param lastModified the date of last modification.
         * @return this builder for chaining.
         */
        public Builder lastModified( final Date lastModified ) {
            this.lastModified = lastModified;
            return this;
        }

        /**
         * Sets the excerpt text.
         *
         * @param excerpt the excerpt from the page content.
         * @return this builder for chaining.
         */
        public Builder excerpt( final String excerpt ) {
            this.excerpt = excerpt;
            return this;
        }

        /**
         * Sets the change note.
         *
         * @param changeNote the change note from last modification.
         * @return this builder for chaining.
         */
        public Builder changeNote( final String changeNote ) {
            this.changeNote = changeNote;
            return this;
        }

        /**
         * Sets the version number.
         *
         * @param version the page version.
         * @return this builder for chaining.
         */
        public Builder version( final int version ) {
            this.version = version;
            return this;
        }

        /**
         * Sets the view URL.
         *
         * @param url the URL to view the article.
         * @return this builder for chaining.
         */
        public Builder url( final String url ) {
            this.url = url;
            return this;
        }

        /**
         * Sets the content size.
         *
         * @param size the size in bytes.
         * @return this builder for chaining.
         */
        public Builder size( final long size ) {
            this.size = size;
            return this;
        }

        /**
         * Builds and returns the ArticleSummary instance.
         *
         * @return a new ArticleSummary with the configured values.
         * @throws IllegalStateException if name is null or empty.
         */
        public ArticleSummary build() {
            if ( name == null || name.isEmpty() ) {
                throw new IllegalStateException( "ArticleSummary name cannot be null or empty" );
            }
            if ( title == null ) {
                title = name;
            }
            if ( url == null ) {
                url = "";
            }
            return new ArticleSummary( this );
        }
    }
}
