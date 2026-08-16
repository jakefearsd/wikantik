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

import java.util.List;

/**
 * Service for upserting search visibility snapshots.
 */
public interface InsightsStore {

    /**
     * Upserts visibility rows, keyed by (snapshotDate, engine, siteHost, pagePath, queryText).
     * Re-sending a window converges instead of duplicating — backfill and the nightly run
     * are the same code path, so duplicates would double-count every history load.
     *
     * @param rows the visibility rows to upsert
     * @return the number of rows written; 0 if the list is empty or an error occurred
     */
    int upsert( List<VisibilityRow> rows );
}
