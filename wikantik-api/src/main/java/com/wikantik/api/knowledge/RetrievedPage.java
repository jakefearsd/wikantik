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
package com.wikantik.api.knowledge;

import java.util.List;

/**
 * A wiki page surfaced as a retrieval hit. Carries its own top contributing
 * chunks (from the chunk-level score pass) and a small list of
 * mention-co-occurrence neighbors as {@link RelatedPage} hints. All list
 * fields default to empty when {@code null} is passed; name must be
 * non-blank.
 */
public record RetrievedPage(
    String name,
    String url,
    double score,
    String summary,
    String cluster,
    List< String > tags,
    List< RetrievedChunk > contributingChunks,
    List< RelatedPage > relatedPages,
    String author,
    java.util.Date lastModified,
    boolean derived
) {
    public RetrievedPage {
        if ( name == null || name.isBlank() ) {
            throw new IllegalArgumentException( "name must not be blank" );
        }
        summary = summary == null ? "" : summary;
        tags = tags == null ? List.of() : List.copyOf( tags );
        contributingChunks = contributingChunks == null ? List.of() : List.copyOf( contributingChunks );
        relatedPages = relatedPages == null ? List.of() : List.copyOf( relatedPages );
        // Defensive copy of mutable Date so internal state stays immutable.
        lastModified = lastModified == null ? null : new java.util.Date( lastModified.getTime() );
    }

    /** Defensive copy on read — Date is mutable. */
    public java.util.Date lastModified() {
        return lastModified == null ? null : new java.util.Date( lastModified.getTime() );
    }

    /**
     * GoF Builder companion for the 11-component canonical constructor. The
     * record stays the single source of truth (the compact constructor does all
     * validation/defaulting; {@link Builder#build()} just delegates) — the
     * builder exists so call sites that only care about a couple of components
     * don't have to thread six positional {@code null}s to reach the last one.
     */
    public static Builder builder( final String name, final double score ) {
        return new Builder( name, score );
    }

    public static final class Builder {
        private final String name;
        private final double score;
        private String url;
        private String summary;
        private String cluster;
        private List< String > tags;
        private List< RetrievedChunk > contributingChunks;
        private List< RelatedPage > relatedPages;
        private String author;
        private java.util.Date lastModified;
        private boolean derived;

        private Builder( final String name, final double score ) {
            this.name = name;
            this.score = score;
        }

        public Builder url( final String v )                                  { this.url = v; return this; }
        public Builder summary( final String v )                              { this.summary = v; return this; }
        public Builder cluster( final String v )                              { this.cluster = v; return this; }
        public Builder tags( final List< String > v )                         { this.tags = v; return this; }
        public Builder contributingChunks( final List< RetrievedChunk > v )   { this.contributingChunks = v; return this; }
        public Builder relatedPages( final List< RelatedPage > v )            { this.relatedPages = v; return this; }
        public Builder author( final String v )                               { this.author = v; return this; }
        public Builder lastModified( final java.util.Date v )                 { this.lastModified = v; return this; }
        public Builder derived( final boolean v )                             { this.derived = v; return this; }

        public RetrievedPage build() {
            return new RetrievedPage( name, url, score, summary, cluster, tags,
                contributingChunks, relatedPages, author, lastModified, derived );
        }
    }
}
