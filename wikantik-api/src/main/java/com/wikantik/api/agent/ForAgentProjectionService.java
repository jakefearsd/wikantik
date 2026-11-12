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
package com.wikantik.api.agent;

import java.util.Optional;
import java.util.function.Predicate;

/**
 * Returns a token-budgeted, agent-shaped view of a wiki page. The
 * implementation composes the structural index, page manager, and a small set
 * of extractors; callers receive a single self-describing payload that fits
 * inside an agent context window.
 *
 * <p>Returns {@link Optional#empty()} when the canonical_id is not known to
 * the structural index. Returns a populated {@link ForAgentProjection} (with
 * {@link ForAgentProjection#degraded()} set to {@code true} when one or more
 * extractors failed) for any page the index can resolve. Never throws on
 * extractor failure — the per-field try/catch surfaces the failure on the
 * {@link ForAgentProjection#missingFields()} list instead.</p>
 *
 * <p>Phase 2 of the Agent-Grade Content design — see
 * {@code docs/wikantik-pages/AgentGradeContentDesign.md}.</p>
 */
public interface ForAgentProjectionService {

    /**
     * The projection with nothing withheld: every field derived from other pages names them whether or not a caller
     * may view them. For internal use only; anything served to a caller goes through
     * {@link #project(String, Predicate)}.
     */
    default Optional< ForAgentProjection > project( final String canonicalId ) {
        return project( canonicalId, slug -> true );
    }

    /**
     * The projection as {@code viewable} may see it. The fields derived from OTHER pages (the preferred pages of
     * {@code agent_hints}, a hub summary synthesized from their titles, and the targets of {@code stale_citations})
     * hold only pages whose slug {@code viewable} accepts. The caller still checks view permission on the page
     * itself.
     *
     * @param canonicalId the page's canonical id
     * @param viewable    whether the caller may view the page with this slug
     * @return the projection, or empty when the canonical id is unknown
     */
    Optional< ForAgentProjection > project( String canonicalId, Predicate< String > viewable );
}
