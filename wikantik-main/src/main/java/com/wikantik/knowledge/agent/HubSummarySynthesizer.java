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
package com.wikantik.knowledge.agent;

import com.wikantik.api.agent.AgentHintsBlock;
import com.wikantik.api.agent.PreferredPage;

import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Detects an authored hub summary that matches a generic "Index of pages on…"
 * pattern and synthesises an overlay that names the top-3 cluster pages
 * derived by {@link AgentHintsDeriver}. Read-only: never writes back to the
 * page body. Stateless.
 */
public final class HubSummarySynthesizer {

    private static final Pattern GENERIC =
            Pattern.compile( "^\\s*(an?\\s+)?index of (pages?|articles?|content)\\s+(on|about|covering|for)\\b",
                             Pattern.CASE_INSENSITIVE );

    public Optional< String > maybeOverlay( final String authoredSummary,
                                            final AgentHintsBlock derivedHints,
                                            final boolean isHub ) {
        if ( !isHub )                                            return Optional.empty();
        if ( authoredSummary == null )                           return Optional.empty();
        if ( !GENERIC.matcher( authoredSummary ).find() )        return Optional.empty();
        if ( derivedHints == null
                || derivedHints.prefer_pages() == null
                || derivedHints.prefer_pages().isEmpty() )       return Optional.empty();

        final List< String > topTitles = derivedHints.prefer_pages().stream()
                .limit( 3 )
                .map( PreferredPage::title )
                .toList();
        return Optional.of( "Cluster hub. Highest-signal pages: " + String.join( ", ", topTitles ) + "." );
    }
}
