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
package com.wikantik.api.briefing;

import java.util.List;

/** Request for a session-start context briefing: explicit pins/clusters, an optional
 *  free-text prompt for retrieval-driven widening, a token budget, and a scope mode. */
public record BriefingRequest( List< String > pins, List< String > clusters, String prompt,
                               Integer budgetTokens, ScopeMode scopeMode ) {
    public BriefingRequest {
        pins = pins == null ? List.of() : List.copyOf( pins );
        clusters = clusters == null ? List.of() : List.copyOf( clusters );
        scopeMode = scopeMode == null ? ScopeMode.PREFER : scopeMode;
    }

    /** True when the request names any pin, cluster, or non-blank prompt to assemble from. */
    public boolean hasAnySource() {
        return !pins.isEmpty() || !clusters.isEmpty() || ( prompt != null && !prompt.isBlank() );
    }
}
