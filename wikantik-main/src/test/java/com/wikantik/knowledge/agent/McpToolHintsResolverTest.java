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

import com.wikantik.api.agent.McpToolHint;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class McpToolHintsResolverTest {

    private final McpToolHintsResolver resolver = new McpToolHintsResolver();

    @Test
    void frontmatter_hints_are_passed_through_when_well_formed() {
        final Map< String, Object > fm = Map.of(
                "mcp_tool_hints", List.of(
                        Map.of( "tool", "/knowledge-mcp/search_knowledge",
                                "when", "Find pages about hybrid retrieval" )
                ) );
        final List< McpToolHint > out = resolver.resolve( fm, List.of( "retrieval" ), "wikantik-development" );
        assertEquals( 1, out.size() );
        assertEquals( "/knowledge-mcp/search_knowledge", out.get( 0 ).tool() );
    }

    @Test
    void synthesises_hints_from_tags_and_cluster_when_frontmatter_empty() {
        final List< McpToolHint > out = resolver.resolve(
                Map.of(),
                List.of( "retrieval", "agent-context" ),
                "wikantik-development" );
        assertFalse( out.isEmpty(), "must synthesise at least one hint" );
        assertTrue( out.stream().anyMatch( h -> h.tool().contains( "/knowledge-mcp/" ) ),
                "synthesised hints should point at /knowledge-mcp/* tools" );
    }

    @Test
    void no_tags_no_cluster_no_frontmatter_yields_empty_list() {
        assertTrue( resolver.resolve( Map.of(), List.of(), null ).isEmpty() );
        assertTrue( resolver.resolve( null,     null,        null ).isEmpty() );
    }

    @Test
    void caps_at_max_hints() {
        final List< Map< String, String > > many = java.util.stream.IntStream.range( 0, 30 )
                .mapToObj( i -> Map.of( "tool", "/x/" + i, "when", "case " + i ) )
                .toList();
        final List< McpToolHint > out = resolver.resolve(
                Map.of( "mcp_tool_hints", many ),
                List.of(), null );
        assertTrue( out.size() <= McpToolHintsResolver.MAX_HINTS,
                "must cap at " + McpToolHintsResolver.MAX_HINTS );
    }

    @Test
    void malformed_frontmatter_entries_are_skipped() {
        final List< Object > entries = Arrays.asList(
                Map.of( "tool", "ok", "when", "ok" ),
                "not a map",
                Map.of( "tool", "missing-when" ),
                Map.of( "tool", "ok2", "when", "ok2" ) );
        final Map< String, Object > fm = new HashMap<>();
        fm.put( "mcp_tool_hints", entries );
        final List< McpToolHint > out = resolver.resolve( fm, List.of(), null );
        assertEquals( 2, out.size() );
        assertEquals( "ok",  out.get( 0 ).tool() );
        assertEquals( "ok2", out.get( 1 ).tool() );
    }
}
