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

import java.time.LocalDate;

/**
 * Represents a search visibility snapshot row from a search engine result.
 * Keyed by (snapshotDate, engine, siteHost, pagePath, queryText).
 */
public record VisibilityRow(
        LocalDate snapshotDate,
        int windowDays,
        String engine,
        String siteHost,
        String pagePath,
        String queryText,
        int impressions,
        int clicks,
        Double position ) {

    /**
     * Factory method that creates a new VisibilityRow, or returns null if engine or siteHost
     * is null or blank. Guards the allowlist contract at the type boundary.
     *
     * @param snapshotDate the snapshot date
     * @param windowDays   the reporting window in days
     * @param engine       the search engine name; must not be blank
     * @param siteHost     the site host; must not be blank
     * @param pagePath     the page path
     * @param queryText    the query text
     * @param impressions  the number of impressions
     * @param clicks       the number of clicks
     * @param position     the average position (may be null)
     * @return a new VisibilityRow, or null if engine or siteHost is blank
     */
    public static VisibilityRow of( final LocalDate snapshotDate, final int windowDays,
                                    final String engine, final String siteHost,
                                    final String pagePath, final String queryText,
                                    final int impressions, final int clicks,
                                    final Double position ) {
        if ( engine == null || engine.isBlank() || siteHost == null || siteHost.isBlank() ) {
            return null;
        }
        return new VisibilityRow( snapshotDate, windowDays, engine, siteHost, pagePath,
                queryText, impressions, clicks, position );
    }
}
