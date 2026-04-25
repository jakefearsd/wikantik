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
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.*;

class FrontmatterRunbookValidatorTest {

    private static final Predicate< String > NO_CANONICAL_IDS = id -> false;
    private static final Predicate< String > NO_TITLES        = name -> false;

    @Test
    void minimal_valid_runbook_round_trips() {
        final Map< String, Object > fm = Map.of(
                "type", "runbook",
                "runbook", Map.of(
                        "when_to_use", List.of( "Agent needs X" ),
                        "steps",       List.of( "Call A", "Then call B" ),
                        "pitfalls",    List.of( "(none known)" )
                ) );
        final FrontmatterRunbookValidator.Result r =
                FrontmatterRunbookValidator.validate( fm, NO_CANONICAL_IDS, NO_TITLES );
        assertTrue( r.valid().isPresent(), () -> "expected valid; issues: " + r.issues() );
        assertTrue( r.issues().isEmpty() );
        final RunbookBlock rb = r.valid().get();
        assertEquals( 1, rb.when_to_use().size() );
        assertEquals( 2, rb.steps().size() );
        assertEquals( "(none known)", rb.pitfalls().get( 0 ) );
    }

    @Test
    void missing_runbook_block_is_an_issue() {
        final Map< String, Object > fm = Map.of( "type", "runbook" );
        final FrontmatterRunbookValidator.Result r =
                FrontmatterRunbookValidator.validate( fm, NO_CANONICAL_IDS, NO_TITLES );
        assertTrue( r.valid().isEmpty() );
        assertTrue( r.issues().stream().anyMatch( i ->
                i.kind() == FrontmatterRunbookValidator.IssueKind.MISSING_BLOCK ) );
    }

    @Test
    void runbook_block_must_be_a_map() {
        final Map< String, Object > fm = Map.of(
                "type", "runbook",
                "runbook", "not a map" );
        final FrontmatterRunbookValidator.Result r =
                FrontmatterRunbookValidator.validate( fm, NO_CANONICAL_IDS, NO_TITLES );
        assertTrue( r.issues().stream().anyMatch( i ->
                i.kind() == FrontmatterRunbookValidator.IssueKind.MALFORMED_BLOCK ) );
    }

    @Test
    void when_to_use_must_have_at_least_one_entry() {
        final Map< String, Object > fm = Map.of(
                "type", "runbook",
                "runbook", Map.of(
                        "when_to_use", List.of(),
                        "steps",       List.of( "a", "b" ),
                        "pitfalls",    List.of( "(none known)" )
                ) );
        final var r = FrontmatterRunbookValidator.validate( fm, NO_CANONICAL_IDS, NO_TITLES );
        assertTrue( r.valid().isEmpty() );
        assertTrue( r.issues().stream().anyMatch( i ->
                i.kind() == FrontmatterRunbookValidator.IssueKind.WHEN_TO_USE_EMPTY ) );
    }

    @Test
    void steps_must_have_at_least_two_entries() {
        final Map< String, Object > fm = Map.of(
                "type", "runbook",
                "runbook", Map.of(
                        "when_to_use", List.of( "x" ),
                        "steps",       List.of( "only one" ),
                        "pitfalls",    List.of( "(none known)" )
                ) );
        final var r = FrontmatterRunbookValidator.validate( fm, NO_CANONICAL_IDS, NO_TITLES );
        assertTrue( r.valid().isEmpty() );
        assertTrue( r.issues().stream().anyMatch( i ->
                i.kind() == FrontmatterRunbookValidator.IssueKind.STEPS_TOO_FEW ) );
    }

    @Test
    void pitfalls_must_have_at_least_one_entry() {
        final Map< String, Object > fm = Map.of(
                "type", "runbook",
                "runbook", Map.of(
                        "when_to_use", List.of( "x" ),
                        "steps",       List.of( "a", "b" ),
                        "pitfalls",    List.of()
                ) );
        final var r = FrontmatterRunbookValidator.validate( fm, NO_CANONICAL_IDS, NO_TITLES );
        assertTrue( r.valid().isEmpty() );
        assertTrue( r.issues().stream().anyMatch( i ->
                i.kind() == FrontmatterRunbookValidator.IssueKind.PITFALLS_EMPTY ) );
    }

    @Test
    void related_tools_accepts_known_prefixes_and_bare_names() {
        final Map< String, Object > fm = Map.of(
                "type", "runbook",
                "runbook", Map.of(
                        "when_to_use", List.of( "x" ),
                        "steps",       List.of( "a", "b" ),
                        "pitfalls",    List.of( "(none known)" ),
                        "related_tools", List.of(
                                "/api/search",
                                "/knowledge-mcp/search_knowledge",
                                "/wikantik-admin-mcp/mark_page_verified",
                                "/tools/search_wiki",
                                "search_knowledge",
                                "find_similar"
                        ) ) );
        final var r = FrontmatterRunbookValidator.validate( fm, NO_CANONICAL_IDS, NO_TITLES );
        assertTrue( r.valid().isPresent(), () -> "issues: " + r.issues() );
        assertEquals( 6, r.valid().get().related_tools().size() );
    }

    @Test
    void related_tools_rejects_unknown_prefixes_and_garbage() {
        final Map< String, Object > fm = Map.of(
                "type", "runbook",
                "runbook", Map.of(
                        "when_to_use", List.of( "x" ),
                        "steps",       List.of( "a", "b" ),
                        "pitfalls",    List.of( "(none known)" ),
                        "related_tools", List.of( "/wiki/Foo", "Bad-Tool-Name" )
                ) );
        final var r = FrontmatterRunbookValidator.validate( fm, NO_CANONICAL_IDS, NO_TITLES );
        assertTrue( r.valid().isEmpty() );
        final long badCount = r.issues().stream()
                .filter( i -> i.kind() == FrontmatterRunbookValidator.IssueKind.RELATED_TOOL_INVALID )
                .count();
        assertEquals( 2, badCount );
    }

    @Test
    void references_accept_canonical_id_or_page_title() {
        final Set< String > knownCanonicals = Set.of( "01ABC" );
        final Set< String > knownTitles     = Set.of( "HybridRetrieval" );
        final Map< String, Object > fm = Map.of(
                "type", "runbook",
                "runbook", Map.of(
                        "when_to_use", List.of( "x" ),
                        "steps",       List.of( "a", "b" ),
                        "pitfalls",    List.of( "(none known)" ),
                        "references",  List.of( "01ABC", "HybridRetrieval" )
                ) );
        final var r = FrontmatterRunbookValidator.validate( fm,
                knownCanonicals::contains, knownTitles::contains );
        assertTrue( r.valid().isPresent(), () -> "issues: " + r.issues() );
        assertEquals( 2, r.valid().get().references().size() );
    }

    @Test
    void references_unresolvable_entries_are_issues() {
        final Map< String, Object > fm = Map.of(
                "type", "runbook",
                "runbook", Map.of(
                        "when_to_use", List.of( "x" ),
                        "steps",       List.of( "a", "b" ),
                        "pitfalls",    List.of( "(none known)" ),
                        "references",  List.of( "GhostPage", "01NOTREAL" )
                ) );
        final var r = FrontmatterRunbookValidator.validate( fm, NO_CANONICAL_IDS, NO_TITLES );
        assertTrue( r.valid().isEmpty() );
        final long missing = r.issues().stream()
                .filter( i -> i.kind() == FrontmatterRunbookValidator.IssueKind.REFERENCE_UNRESOLVABLE )
                .count();
        assertEquals( 2, missing );
    }

    @Test
    void non_runbook_type_returns_empty_result_with_no_issues() {
        final Map< String, Object > fm = Map.of( "type", "article" );
        final var r = FrontmatterRunbookValidator.validate( fm, NO_CANONICAL_IDS, NO_TITLES );
        assertTrue( r.valid().isEmpty() );
        assertTrue( r.issues().isEmpty() );
    }

    @Test
    void blank_string_entries_in_lists_are_dropped_not_failures() {
        final Map< String, Object > fm = Map.of(
                "type", "runbook",
                "runbook", Map.of(
                        "when_to_use", java.util.Arrays.asList( "real entry", "  ", "" ),
                        "steps",       List.of( "step a", "step b" ),
                        "pitfalls",    List.of( "(none known)" )
                ) );
        final var r = FrontmatterRunbookValidator.validate( fm, NO_CANONICAL_IDS, NO_TITLES );
        assertTrue( r.valid().isPresent(), () -> "issues: " + r.issues() );
        assertEquals( 1, r.valid().get().when_to_use().size() );
    }
}
