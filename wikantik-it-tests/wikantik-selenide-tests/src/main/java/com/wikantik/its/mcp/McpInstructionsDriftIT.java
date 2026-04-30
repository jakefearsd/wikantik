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
package com.wikantik.its.mcp;

import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * End-to-end check that the {@code instructions} text the server emits in its MCP
 * {@code initialize} response is in sync with the tool registry it advertises through
 * {@code tools/list}. Wire-level counterpart to the surefire {@code
 * InstructionsRegistryDriftTest} — that one catches drift before packaging; this one
 * catches drift after the WAR is deployed and a real client has handshaken with it.
 *
 * <p>Why both? An operator can override the bundled instructions via {@code
 * mcp.instructions.file} or an inline {@code mcp.instructions} property. The unit test
 * only sees the file shipped in {@code wikantik-admin-mcp/src/main/resources}; this IT
 * sees whatever the deployed server actually returns, so an operator override that
 * drifts from the registered tools fails the build before it can mislead an agent in
 * production.</p>
 */
public class McpInstructionsDriftIT extends WithMcpTestSetup {

    /**
     * Verb prefixes that mark a snake_case token in the instructions as a candidate
     * tool name. Mirrors {@code McpServerInitializer.TOOL_NAME_PREFIXES}; the IT module
     * does not depend on {@code wikantik-admin-mcp}, so the heuristic is duplicated
     * here. Add a new prefix in both places if a new tool family lands.
     */
    private static final Set< String > TOOL_NAME_PREFIXES = Set.of(
            "get_", "list_", "search_", "find_", "write_", "delete_", "rename_", "diff_",
            "verify_", "ping_", "preview_", "propose_", "mark_", "update_", "read_",
            "retrieve_", "traverse_", "discover_", "query_" );

    private static final Pattern SNAKE_TOKEN = Pattern.compile( "\\b[a-z][a-z0-9_]{2,}\\b" );

    @Test
    public void serverInstructionsMentionEveryRegisteredTool() {
        final String instructions = mcp.serverInstructions();
        Assertions.assertNotNull( instructions,
                "MCP initialize response must carry an instructions text. The server is "
                + "configured without one — agents lose the README they were promised." );
        Assertions.assertFalse( instructions.isBlank(),
                "MCP initialize instructions must not be blank." );

        final Set< String > registered = mcp.listTools().tools().stream()
                .map( McpSchema.Tool::name )
                .collect( Collectors.toCollection( LinkedHashSet::new ) );
        Assertions.assertFalse( registered.isEmpty(),
                "Server reported zero tools — the registry never wired up." );

        final Set< String > missing = new LinkedHashSet<>();
        for ( final String name : registered ) {
            if ( !mentionsWholeWord( instructions, name ) ) {
                missing.add( name );
            }
        }

        Assertions.assertTrue( missing.isEmpty(),
                "The deployed MCP server's instructions text does not mention these "
                + "registered tools: " + missing + ". Agents that read the initialize "
                + "instructions will never learn these tools exist. Either describe them "
                + "in wikantik-mcp-instructions.txt (or the override pointed at by "
                + "mcp.instructions.file), or remove them from the registry." );
    }

    @Test
    public void serverInstructionsDoNotReferenceRetiredTools() {
        final String instructions = mcp.serverInstructions();
        Assertions.assertNotNull( instructions,
                "MCP initialize response must carry an instructions text." );

        final Set< String > registered = mcp.listTools().tools().stream()
                .map( McpSchema.Tool::name )
                .collect( Collectors.toCollection( LinkedHashSet::new ) );

        final Set< String > stale = extractToolLikeNames( instructions );
        stale.removeAll( registered );

        Assertions.assertTrue( stale.isEmpty(),
                "The deployed MCP server's instructions text references tool name(s) "
                + "that the live server does not expose: " + stale + ". This is the "
                + "exact failure mode that bit production around 2026-04-29 — agents "
                + "called write_page / lock_page / delete_attachment because the "
                + "instructions told them to, even though the server had not registered "
                + "those tools for months. Refresh the instructions to mirror tools/list, "
                + "or implement the missing tools." );
    }

    private static boolean mentionsWholeWord( final String haystack, final String needle ) {
        return Pattern.compile( "\\b" + Pattern.quote( needle ) + "\\b" )
                .matcher( haystack ).find();
    }

    private static Set< String > extractToolLikeNames( final String instructions ) {
        final Matcher m = SNAKE_TOKEN.matcher( instructions );
        final Set< String > result = new LinkedHashSet<>();
        while ( m.find() ) {
            final String tok = m.group();
            if ( tok.indexOf( '_' ) < 0 ) {
                continue;
            }
            for ( final String prefix : TOOL_NAME_PREFIXES ) {
                if ( tok.startsWith( prefix ) ) {
                    result.add( tok );
                    break;
                }
            }
        }
        return result;
    }
}
