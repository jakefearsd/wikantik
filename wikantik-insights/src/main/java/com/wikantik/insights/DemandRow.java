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
package com.wikantik.insights;

/**
 * One {@code retrieval_query_log}-shaped demand signal, consumed by
 * {@link OpportunityEngine#evaluateAgentGap}.
 *
 * <p>{@link OpportunityEngine} does not query the database -- this record is the shape a future
 * {@code retrieval_query_log} adapter (owned elsewhere; see {@code V051}) is expected to hand it.
 * The common case is one {@code DemandRow} per distinct {@code queryText} in the 28-day window,
 * with {@code occurrences} and {@code distinctSessions} already summed by the caller. A caller
 * may also emit more than one row for the same {@code queryText} -- e.g. one row for a "zero
 * results" cluster of calls and another for a "returned results" cluster -- and the engine groups
 * by {@code queryText} before evaluating, so a query is only judged as a gap when <em>every</em>
 * row for it is weak (§7.3 rule 1's "every occurrence" wording).</p>
 *
 * @param queryText        the submitted query text
 * @param coverage         bundle coverage confidence for this row's calls
 *                         ({@code strong}/{@code partial}/{@code weak}/{@code unknown}), or
 *                         {@code null} for non-bundle surfaces that don't report it
 * @param resultCount      the result count observed for this row's calls; {@code 0} means the
 *                         call(s) this row represents returned nothing
 * @param occurrences      how many retrieval calls this row represents
 * @param distinctSessions how many distinct calls/sessions those occurrences came from
 */
public record DemandRow( String queryText, String coverage, int resultCount,
                         int occurrences, int distinctSessions ) {
}
