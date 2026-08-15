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
package com.wikantik.pagegraph.spine;

import java.util.List;
import java.util.Map;

/**
 *  The outcome of applying a {@link ClusterRenamePlan}.
 *
 *  <p>The operation is not atomic — pages are saved one at a time through the normal filter
 *  chain, so a failure part-way leaves the corpus split across two cluster names. Rather than
 *  hide that, every page is reported: {@link #renamed()} is what moved, {@link #failures()} is
 *  what did not and why, so the caller can retry precisely the remainder.</p>
 *
 *  @param from     the cluster that was renamed
 *  @param to       its new path
 *  @param renamed  slugs successfully rewritten
 *  @param failures slug to failure message, for pages that did not move
 */
public record ClusterRenameResult(
        String from,
        String to,
        List< String > renamed,
        Map< String, String > failures ) {

    public ClusterRenameResult {
        renamed  = renamed  == null ? List.of() : List.copyOf( renamed );
        failures = failures == null ? Map.of()  : Map.copyOf( failures );
    }

    /** Whether every page moved. */
    public boolean complete() {
        return failures.isEmpty();
    }
}
