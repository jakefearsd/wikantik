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
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Builds {@link McpToolHint} entries for the {@code /for-agent} projection. If
 * the page authors {@code mcp_tool_hints:} in frontmatter, those entries are
 * surfaced verbatim (after a per-entry well-formedness check). Otherwise a
 * small set of synthesised hints is generated from the page's tags and
 * cluster, pointing agents at the most likely useful tools on
 * {@code /knowledge-mcp}.
 */
public final class McpToolHintsResolver {

    private static final Logger LOG = LogManager.getLogger( McpToolHintsResolver.class );

    public static final int MAX_HINTS = 5;

    public List< McpToolHint > resolve(
            final Map< String, Object > frontmatter,
            final List< String > tags,
            final String cluster ) {

        // 1. Frontmatter authored.
        if ( frontmatter != null ) {
            final Object raw = frontmatter.get( "mcp_tool_hints" );
            if ( raw instanceof List< ? > list ) {
                final List< McpToolHint > out = new ArrayList<>();
                for ( final Object o : list ) {
                    if ( !( o instanceof Map< ?, ? > m ) ) continue;
                    final Object tool = m.get( "tool" );
                    final Object when = m.get( "when" );
                    if ( tool == null || when == null ) continue;
                    final String t = tool.toString().trim();
                    final String w = when.toString().trim();
                    if ( t.isEmpty() || w.isEmpty() ) continue;
                    out.add( new McpToolHint( t, w ) );
                    if ( out.size() >= MAX_HINTS ) break;
                }
                if ( !out.isEmpty() ) {
                    return List.copyOf( out );
                }
            }
        }

        // 2. Synthesise from tags + cluster.
        final List< McpToolHint > out = new ArrayList<>();
        if ( tags != null && !tags.isEmpty() ) {
            out.add( new McpToolHint(
                    "/knowledge-mcp/search_knowledge",
                    "Search across this page's topic — tags: " + String.join( ", ", tags ) ) );
        }
        if ( cluster != null && !cluster.isBlank() ) {
            out.add( new McpToolHint(
                    "/knowledge-mcp/list_pages_by_filter",
                    "Enumerate other pages in cluster '" + cluster + "'" ) );
        }
        return List.copyOf( out );
    }
}
