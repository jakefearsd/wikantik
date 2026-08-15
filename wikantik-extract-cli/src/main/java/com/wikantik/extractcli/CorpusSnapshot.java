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
package com.wikantik.extractcli;

import java.util.List;
import java.util.Map;

/**
 *  One side's view of the corpus, plus an explicit record of anything that could not be read.
 *
 *  <p>The {@code errors} list is the whole point. `bin/remote.sh pages-pull` fails
 *  `Permission denied` on container-owned pages and returns the rest, so a caller that only
 *  looks at the returned pages sees an authoritative-looking corpus that is silently missing
 *  precisely the pages the application has rewritten. A snapshot therefore carries its own
 *  failures, and {@link CorpusDiff} refuses to compare one that has any.</p>
 *
 *  @param name   human label for this side ("repo", "prod") used in messages
 *  @param pages  successfully read pages, keyed by slug
 *  @param errors one entry per page that could not be read; empty means the snapshot is total
 */
public record CorpusSnapshot( String name, Map< String, PageFacts > pages, List< String > errors ) {

    public CorpusSnapshot {
        pages  = pages  == null ? Map.of()  : Map.copyOf( pages );
        errors = errors == null ? List.of() : List.copyOf( errors );
    }

    /** Whether every page was read. A snapshot with any error is not safe to compare. */
    public boolean complete() {
        return errors.isEmpty();
    }
}
