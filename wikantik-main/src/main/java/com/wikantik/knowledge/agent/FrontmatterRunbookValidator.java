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

import com.wikantik.api.agent.RunbookBlock;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * Validates the {@code runbook:} frontmatter block per the Phase 3 schema in
 * {@code docs/wikantik-pages/AgentGradeContentDesign.md}. Pure function — no
 * I/O. Two predicates are injected so the validator stays unit-testable
 * without dragging in {@code StructuralIndexService} or {@code PageManager}.
 *
 * <p>Returns an empty result (both {@code valid} and {@code issues} empty)
 * when the page is not a runbook — callers that want to enforce
 * {@code type: runbook} should check {@code "runbook".equals(metadata.get("type"))}
 * themselves before invoking the validator. This keeps the validator's
 * contract tight: "given a runbook frontmatter, parse it or list the
 * problems".</p>
 */
public final class FrontmatterRunbookValidator {

    private static final Logger LOG = LogManager.getLogger( FrontmatterRunbookValidator.class );

    public enum IssueKind {
        MISSING_BLOCK,
        MALFORMED_BLOCK,
        WHEN_TO_USE_EMPTY,
        STEPS_TOO_FEW,
        PITFALLS_EMPTY,
        RELATED_TOOL_INVALID,
        REFERENCE_UNRESOLVABLE
    }

    public record Issue( IssueKind kind, String detail ) {}

    public record Result( Optional< RunbookBlock > valid, List< Issue > issues ) {
        public Result {
            valid  = valid  == null ? Optional.empty() : valid;
            issues = issues == null ? List.of()        : List.copyOf( issues );
        }
        public boolean hasIssues() {
            return !issues.isEmpty();
        }
    }

    private static final Pattern PREFIXED_TOOL = Pattern.compile(
            "^/(api|knowledge-mcp|wikantik-admin-mcp|tools)/.+$" );
    private static final Pattern BARE_TOOL_NAME = Pattern.compile( "^[a-z][a-z0-9_]*$" );

    private FrontmatterRunbookValidator() {}

    public static Result validate(
            final Map< String, Object > metadata,
            final Predicate< String > canonicalIdResolves,
            final Predicate< String > pageTitleResolves ) {

        if ( metadata == null ) {
            return new Result( Optional.empty(), List.of() );
        }
        final Object typeRaw = metadata.get( "type" );
        if ( typeRaw == null || !"runbook".equalsIgnoreCase( typeRaw.toString().trim() ) ) {
            return new Result( Optional.empty(), List.of() );
        }

        final Object blockRaw = metadata.get( "runbook" );
        if ( blockRaw == null ) {
            return new Result( Optional.empty(), List.of(
                    new Issue( IssueKind.MISSING_BLOCK,
                            "type: runbook requires a runbook: block" ) ) );
        }
        if ( !( blockRaw instanceof Map< ?, ? > rawBlock ) ) {
            return new Result( Optional.empty(), List.of(
                    new Issue( IssueKind.MALFORMED_BLOCK,
                            "runbook: must be a map, not " + blockRaw.getClass().getSimpleName() ) ) );
        }

        final List< Issue > issues = new ArrayList<>();

        final List< String > whenToUse = nonBlankStrings( rawBlock.get( "when_to_use" ) );
        if ( whenToUse.isEmpty() ) {
            issues.add( new Issue( IssueKind.WHEN_TO_USE_EMPTY,
                    "runbook.when_to_use must have at least one entry" ) );
        }

        final List< String > inputs = nonBlankStrings( rawBlock.get( "inputs" ) );

        final List< String > steps = nonBlankStrings( rawBlock.get( "steps" ) );
        if ( steps.size() < 2 ) {
            issues.add( new Issue( IssueKind.STEPS_TOO_FEW,
                    "runbook.steps must have at least 2 entries (got " + steps.size() + ")" ) );
        }

        final List< String > pitfalls = nonBlankStrings( rawBlock.get( "pitfalls" ) );
        if ( pitfalls.isEmpty() ) {
            issues.add( new Issue( IssueKind.PITFALLS_EMPTY,
                    "runbook.pitfalls must have at least one entry — use \"(none known)\" if there really are none" ) );
        }

        final List< String > relatedTools = nonBlankStrings( rawBlock.get( "related_tools" ) );
        for ( final String t : relatedTools ) {
            if ( !PREFIXED_TOOL.matcher( t ).matches()
              && !BARE_TOOL_NAME.matcher( t ).matches() ) {
                issues.add( new Issue( IssueKind.RELATED_TOOL_INVALID,
                        "related_tools entry doesn't match /api|knowledge-mcp|wikantik-admin-mcp|tools/* "
                          + "or a bare snake_case tool name: " + t ) );
            }
        }

        final List< String > references = nonBlankStrings( rawBlock.get( "references" ) );
        for ( final String ref : references ) {
            if ( !canonicalIdResolves.test( ref ) && !pageTitleResolves.test( ref ) ) {
                issues.add( new Issue( IssueKind.REFERENCE_UNRESOLVABLE,
                        "references entry resolves to neither a canonical_id nor a page title: " + ref ) );
            }
        }

        if ( !issues.isEmpty() ) {
            return new Result( Optional.empty(), issues );
        }
        return new Result( Optional.of( new RunbookBlock(
                whenToUse, inputs, steps, pitfalls, relatedTools, references ) ),
                List.of() );
    }

    private static List< String > nonBlankStrings( final Object raw ) {
        if ( !( raw instanceof List< ? > list ) ) {
            return List.of();
        }
        final List< String > out = new ArrayList<>( list.size() );
        for ( final Object o : list ) {
            if ( o == null ) continue;
            final String s = o.toString().trim();
            if ( s.isEmpty() ) continue;
            out.add( s );
        }
        return out;
    }
}
