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
package com.wikantik.kgpolicy;

/**
 * SQL fragments that filter out pages present in {@code kg_excluded_pages}.
 * Read-path queries that touch {@code kg_nodes}, {@code kg_edges}, or
 * {@code chunk_entity_mentions} use these fragments so the predicate stays
 * consistent across call sites.
 *
 * <p>Each pair of constants is meant to be spliced into a query: the {@code
 * _JOIN} fragment goes between the {@code FROM} table and the {@code WHERE}
 * clause; the {@code _WHERE} fragment is added to the {@code WHERE} clause
 * (combine with {@code AND} when other predicates exist).</p>
 *
 * <p>Aliases used:</p>
 * <ul>
 *   <li>{@code n} for {@code kg_nodes}</li>
 *   <li>{@code e} for {@code kg_edges}</li>
 *   <li>{@code m} for {@code chunk_entity_mentions}</li>
 *   <li>{@code c} for {@code kg_content_chunks} (joined-through for mention queries)</li>
 *   <li>{@code ns} for the source-side {@code kg_nodes} when filtering edges</li>
 *   <li>{@code kgxn}, {@code kgxe}, {@code kgxm} are the filter-side
 *       {@code kg_excluded_pages} aliases (one per filter scope so multiple
 *       filters can co-exist in a single query).</li>
 * </ul>
 */
public final class KgInclusionFilter {

    private KgInclusionFilter() {}

    /** Use after {@code FROM kg_nodes n}. Joins on {@code n.source_page}. */
    public static final String NODE_FILTER_JOIN =
            " LEFT JOIN kg_excluded_pages kgxn ON n.source_page = kgxn.page_name ";

    /** Use in {@code WHERE} when {@link #NODE_FILTER_JOIN} is present. */
    public static final String NODE_FILTER_WHERE = " kgxn.page_name IS NULL ";

    /**
     * Filter for {@code kg_edges e}. Excludes any edge whose source endpoint
     * lives on an excluded page. (Symmetric filtering on the target endpoint
     * is intentionally omitted: agents traversing the graph still see edges
     * pointing INTO an excluded page, but the page's content + outgoing
     * edges stay hidden.)
     */
    public static final String EDGE_FILTER_JOIN =
            " LEFT JOIN kg_nodes ns           ON ns.id = e.source_id " +
            " LEFT JOIN kg_excluded_pages kgxe ON ns.source_page = kgxe.page_name ";

    /** Use in {@code WHERE} when {@link #EDGE_FILTER_JOIN} is present. */
    public static final String EDGE_FILTER_WHERE = " kgxe.page_name IS NULL ";

    /**
     * Filter for {@code chunk_entity_mentions m}, joined through
     * {@code kg_content_chunks c} to recover the page name.
     */
    public static final String MENTION_FILTER_JOIN =
            " LEFT JOIN kg_content_chunks c     ON c.id = m.chunk_id " +
            " LEFT JOIN kg_excluded_pages kgxm  ON c.page_name = kgxm.page_name ";

    /** Use in {@code WHERE} when {@link #MENTION_FILTER_JOIN} is present. */
    public static final String MENTION_FILTER_WHERE = " kgxm.page_name IS NULL ";
}
