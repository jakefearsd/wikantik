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

import com.wikantik.api.structure.Audience;
import com.wikantik.api.structure.Confidence;
import com.wikantik.api.structure.RelationEdge;

import java.time.Instant;
import java.util.List;

/**
 * Token-budgeted projection of a wiki page for agent consumption. Returned by
 * {@link ForAgentProjectionService#project(String)}; rendered to JSON by the
 * REST resource and the MCP tool. Every list field is non-null (empty when the
 * page has nothing to contribute).
 *
 * <p>{@link #degraded} is {@code true} when one or more individual extractors
 * threw and the resulting field was filled with a fallback value (or omitted).
 * {@link #missingFields} names which fields are absent — agents should weight
 * the response accordingly.</p>
 *
 * <p>{@link #runbook} stays {@code null} until Phase 3 of the Agent-Grade
 * Content design introduces the runbook page type.</p>
 */
public record ForAgentProjection(
        String canonicalId,
        String slug,
        String title,
        String type,
        String cluster,
        Audience audience,
        Confidence confidence,
        Instant verifiedAt,
        String verifiedBy,
        Instant updated,
        String summary,
        List< KeyFact > keyFacts,
        List< HeadingOutline > headingsOutline,
        List< RelationEdge > outgoingRelations,
        List< RelationEdge > incomingRelations,
        List< RecentChange > recentChanges,
        List< McpToolHint > mcpToolHints,
        Object runbook,
        String fullBodyUrl,
        String rawMarkdownUrl,
        boolean degraded,
        List< String > missingFields
) {
    public ForAgentProjection {
        if ( canonicalId == null || canonicalId.isBlank() ) {
            throw new IllegalArgumentException( "canonicalId required" );
        }
        if ( slug == null || slug.isBlank() ) {
            throw new IllegalArgumentException( "slug required" );
        }
        keyFacts            = keyFacts            == null ? List.of() : List.copyOf( keyFacts );
        headingsOutline     = headingsOutline     == null ? List.of() : List.copyOf( headingsOutline );
        outgoingRelations   = outgoingRelations   == null ? List.of() : List.copyOf( outgoingRelations );
        incomingRelations   = incomingRelations   == null ? List.of() : List.copyOf( incomingRelations );
        recentChanges       = recentChanges       == null ? List.of() : List.copyOf( recentChanges );
        mcpToolHints        = mcpToolHints        == null ? List.of() : List.copyOf( mcpToolHints );
        missingFields       = missingFields       == null ? List.of() : List.copyOf( missingFields );
    }
}
