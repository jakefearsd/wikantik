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
package com.wikantik.tools;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Monotonic counters for the OpenAPI tool server. Incremented inline by the servlet
 * and exposed to Micrometer by {@link ToolsMetricsBridge} via {@code FunctionCounter},
 * avoiding any double-bookkeeping between the servlet and the registry.
 *
 * <p>Counter matrix — request outcomes are tagged by endpoint + status so operators
 * can slice success rates per tool without extra meter classes.</p>
 */
public final class ToolsMetrics {

    private final AtomicLong searchSuccess = new AtomicLong();
    private final AtomicLong searchError = new AtomicLong();
    private final AtomicLong getPageSuccess = new AtomicLong();
    private final AtomicLong getPageNotFound = new AtomicLong();
    private final AtomicLong getPageForbidden = new AtomicLong();
    private final AtomicLong getPageError = new AtomicLong();
    private final AtomicLong openapiServed = new AtomicLong();
    private final AtomicLong resultsReturned = new AtomicLong();
    private final AtomicLong getPageTruncated = new AtomicLong();

    public void recordSearchSuccess( final int resultCount ) {
        searchSuccess.incrementAndGet();
        if ( resultCount > 0 ) {
            resultsReturned.addAndGet( resultCount );
        }
    }

    public void recordSearchError() { searchError.incrementAndGet(); }

    public void recordGetPageSuccess( final boolean truncated ) {
        getPageSuccess.incrementAndGet();
        if ( truncated ) {
            getPageTruncated.incrementAndGet();
        }
    }

    public void recordGetPageNotFound() { getPageNotFound.incrementAndGet(); }

    public void recordGetPageForbidden() { getPageForbidden.incrementAndGet(); }

    public void recordGetPageError() { getPageError.incrementAndGet(); }

    public void recordOpenapiServed() { openapiServed.incrementAndGet(); }

    public long searchSuccess() { return searchSuccess.get(); }
    public long searchError() { return searchError.get(); }
    public long getPageSuccess() { return getPageSuccess.get(); }
    public long getPageNotFound() { return getPageNotFound.get(); }
    public long getPageForbidden() { return getPageForbidden.get(); }
    public long getPageError() { return getPageError.get(); }
    public long openapiServed() { return openapiServed.get(); }
    public long resultsReturned() { return resultsReturned.get(); }
    public long getPageTruncated() { return getPageTruncated.get(); }
}
