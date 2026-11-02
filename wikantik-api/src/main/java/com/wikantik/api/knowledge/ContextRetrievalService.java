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
 * The single entry point for agent-facing retrieval. Owns the composition of
 * BM25 + dense retrieval + graph rerank + chunk/relatedPages shaping so every
 * caller — REST {@code /api/search}, OpenAPI tool-server {@code search_wiki},
 * and the forthcoming MCP {@code retrieve_context} — produces consistent
 * results from the same pipeline.
 *
 * <p>Implementations are thread-safe and stateless; obtain one from the
 * engine's manager registry.</p>
 */
public interface ContextRetrievalService {

    /** Run the full retrieval pipeline for a natural-language query. */
    RetrievalResult retrieve( ContextQuery query );

    /**
     * Fetch a single page by name. Returns {@code null} if the page does
     * not exist. Does not consult the search stack — this is a direct
     * page lookup for pinned-context flows.
     */
    RetrievedPage getPage( String pageName );

    /**
     * Browse pages by metadata filters. No ranking, no chunks, no related. Lists every page: a surface that answers a
     * caller uses {@link #listPages(PageListFilter, java.util.function.Predicate)}.
     */
    default PageList listPages( final PageListFilter filter ) {
        return listPages( filter, name -> true );
    }

    /**
     * Browse the pages {@code viewable} accepts by metadata filters. A page the caller may not view is dropped before it
     * is matched, so it is never read for the filter (its inline tags included) and never counted in
     * {@link PageList#totalMatched()}.
     *
     * @param filter   the filters, combined with AND
     * @param viewable whether the caller may view the page of that name
     * @return the window of matching pages, and how many match in all
     */
    PageList listPages( PageListFilter filter, java.util.function.Predicate< String > viewable );

    /**
     * Distinct values of a frontmatter field across all pages, with page
     * counts. {@code field} is the frontmatter key (e.g. {@code "cluster"}).
     * Counts every page: a surface that answers a caller uses
     * {@link #listMetadataValues(String, java.util.function.Predicate)}.
     */
    default List< MetadataValue > listMetadataValues( final String field ) {
        return listMetadataValues( field, name -> true );
    }

    /**
     * Distinct values of a frontmatter field across the pages {@code viewable} accepts, with page counts: a restricted
     * page's values (its tags, summary, title, cluster) are never listed to a caller who may not view it.
     *
     * @param field    the frontmatter key (e.g. {@code "cluster"})
     * @param viewable whether the caller may view the page of that name
     * @return the values, most pages first
     */
    List< MetadataValue > listMetadataValues( String field, java.util.function.Predicate< String > viewable );
}
