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
package com.wikantik.knowledge.briefing;

import com.wikantik.api.briefing.BriefingItem;
import com.wikantik.api.briefing.BriefingLogEntry;
import com.wikantik.api.briefing.BriefingRequest;
import com.wikantik.api.briefing.ContextBriefing;

import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;

/**
 * Builds the {@link BriefingLogEntry} for a served briefing. Shared by the REST and MCP briefing
 * surfaces so the count semantics live in one place: {@code pinCount} = included {@code pin} items,
 * {@code pointerCount} = budget-trimmed {@code pin}/{@code cluster} items only (so {@code cited} and
 * {@code elsewhere} pointers cannot inflate it), {@code citedCount} / {@code elsewhereCount} = items of
 * that origin whether or not they were trimmed, and {@code scopeMode} = the request's effective mode in
 * wire form.
 */
public final class BriefingLogEntries {

    private BriefingLogEntries() {}

    /**
     * @param request  the validated request (its scope mode is already defaulted to {@code prefer})
     * @param gated    the briefing as served, i.e. after the ACL gate
     * @param surface  the {@code SourceSurface} wire name
     */
    public static BriefingLogEntry of( final BriefingRequest request, final ContextBriefing gated,
                                       final String surface ) {
        final List< BriefingItem > items = gated.items();
        final boolean promptPresent = request.prompt() != null && !request.prompt().isBlank();
        return new BriefingLogEntry( String.join( ",", request.pins() ), String.join( ",", request.clusters() ),
                promptPresent, gated.budgetTokens(), gated.usedTokens(), gated.sections().size(),
                count( items, i -> i.included() && ItemAssembler.ORIGIN_PIN.equals( i.origin() ) ),
                count( items, i -> !i.included() && ( ItemAssembler.ORIGIN_PIN.equals( i.origin() )
                                                      || ItemAssembler.ORIGIN_CLUSTER.equals( i.origin() ) ) ),
                surface,
                count( items, i -> ItemAssembler.ORIGIN_CITED.equals( i.origin() ) ),
                count( items, i -> ItemAssembler.ORIGIN_ELSEWHERE.equals( i.origin() ) ),
                request.scopeMode().name().toLowerCase( Locale.ROOT ) );
    }

    /**
     * The result count the <i>query</i> log records for a served briefing: the sections delivered plus the
     * {@code elsewhere} pointers, i.e. the retrieval hits the agent was told about. Counting delivered
     * sections alone would log {@code 0} for a prompt whose hits all lie outside the requested cluster,
     * a false content-gap signal. Paired with {@link ContextBriefing#retrievalCoverage()}.
     */
    public static int retrievalHitCount( final ContextBriefing gated ) {
        return gated.sections().size() + count( gated.items(), i -> ItemAssembler.ORIGIN_ELSEWHERE.equals( i.origin() ) );
    }

    private static int count( final List< BriefingItem > items, final Predicate< BriefingItem > test ) {
        return (int) items.stream().filter( test ).count();
    }
}
