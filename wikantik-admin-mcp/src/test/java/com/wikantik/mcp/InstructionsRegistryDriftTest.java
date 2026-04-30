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
package com.wikantik.mcp;

import com.wikantik.TestEngine;
import com.wikantik.mcp.tools.ListProposalsTool;
import com.wikantik.mcp.tools.McpTool;
import com.wikantik.mcp.tools.ProposeKnowledgeTool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Regression guard against the "instructions vs. registry" drift that bit production
 * around 2026-04-29: the static {@code wikantik-mcp-instructions.txt} text shipped with
 * the WAR was teaching agents to call {@code write_page}, {@code delete_page},
 * {@code lock_page}, etc., none of which the live {@link McpToolRegistry} actually
 * registers. The MCP {@code initialize} response carries that text verbatim, so any
 * agent reading it would form a wrong mental model of the tool surface.
 *
 * <p>Two assertions, both must pass on every build:</p>
 * <ol>
 *   <li>Every tool the registry exposes is mentioned by name (whole-word) in the
 *       instructions — otherwise an agent reading the static text never learns the tool
 *       exists.</li>
 *   <li>Every tool-shaped snake_case token in the instructions corresponds to a
 *       registered tool — otherwise the text advertises a tool that the server cannot
 *       actually invoke.</li>
 * </ol>
 *
 * <p>The KG-conditional tools ({@code list_proposals}, {@code propose_knowledge}) are
 * registered in production but not in {@link TestEngine}, so we add their {@code
 * TOOL_NAME} constants explicitly to the expected set — production ships with them, and
 * the instructions document the production surface.</p>
 */
class InstructionsRegistryDriftTest {

    private static final String INSTRUCTIONS_RESOURCE = "/wikantik-mcp-instructions.txt";

    private TestEngine engine;
    private McpToolRegistry registry;

    @BeforeEach
    void setUp() {
        engine = TestEngine.build();
        registry = new McpToolRegistry( engine );
    }

    @AfterEach
    void tearDown() {
        engine.stop();
    }

    @Test
    void instructionsMentionEveryRegisteredTool() {
        final String instructions = loadInstructions();
        final Set< String > expected = expectedToolNames();

        final Set< String > missing = new LinkedHashSet<>();
        for ( final String name : expected ) {
            if ( !mentionsWholeWord( instructions, name ) ) {
                missing.add( name );
            }
        }

        assertTrue( missing.isEmpty(),
                "wikantik-mcp-instructions.txt is out of sync with McpToolRegistry. "
                + "These registered tools are not mentioned in the instructions: " + missing
                + ". Either describe each missing tool in the instructions file, or remove "
                + "it from the registry. Agents that only read the initialize-response "
                + "instructions will never learn these tools exist." );
    }

    @Test
    void instructionsDoNotReferenceRetiredTools() {
        final String instructions = loadInstructions();
        final Set< String > registered = expectedToolNames();
        final Set< String > mentioned = McpServerInitializer.extractToolLikeNames( instructions );

        final Set< String > stale = new LinkedHashSet<>( mentioned );
        stale.removeAll( registered );

        assertTrue( stale.isEmpty(),
                "wikantik-mcp-instructions.txt mentions tool name(s) that the live registry "
                + "does not expose: " + stale + ". Either implement these tools or remove "
                + "the references. Stale references will mislead agents — the failure that "
                + "prompted this guard had agents calling write_page / lock_page / "
                + "delete_attachment, none of which had been registered for months." );
    }

    @Test
    void driftCheckCatchesAFabricatedRetiredTool() {
        // Sanity-check the detector itself: if someone wrote `delete_page` (singular) into
        // the instructions, the drift check must spot it. This is the failure mode that
        // bit production — confirm the guard fires on it.
        final String fabricated = """
                Tools:
                  delete_page  permanently delete a page.
                  write_pages  create new pages.
                """;
        final Set< String > registered = Set.of( "write_pages" );
        final McpServerInitializer.ToolNameDrift drift =
                McpServerInitializer.computeToolNameDrift( fabricated, registered );

        assertTrue( drift.mentionedButNotRegistered().contains( "delete_page" ),
                "extractToolLikeNames must flag delete_page as a stale mention" );
        assertTrue( drift.registeredButNotMentioned().isEmpty(),
                "write_pages is mentioned, so the registered set must come back clean" );
    }

    private Set< String > expectedToolNames() {
        final Set< String > names = new LinkedHashSet<>();
        for ( final McpTool tool : registry.readOnlyTools() ) {
            names.add( tool.name() );
        }
        for ( final McpTool tool : registry.authorConfigurableTools() ) {
            names.add( tool.name() );
        }
        // KG-conditional tools — registered when KnowledgeGraphService is wired (DefaultEngine
        // in production). TestEngine doesn't wire it, so add them explicitly: the shipped
        // instructions describe production, where these tools are present.
        names.add( ListProposalsTool.TOOL_NAME );
        names.add( ProposeKnowledgeTool.TOOL_NAME );
        return names;
    }

    private static boolean mentionsWholeWord( final String haystack, final String needle ) {
        final Pattern p = Pattern.compile( "\\b" + Pattern.quote( needle ) + "\\b" );
        return p.matcher( haystack ).find();
    }

    private String loadInstructions() {
        try ( InputStream in = getClass().getResourceAsStream( INSTRUCTIONS_RESOURCE ) ) {
            assertNotNull( in,
                    "Classpath resource " + INSTRUCTIONS_RESOURCE + " is missing — the WAR "
                    + "would ship without instructions and the MCP initialize response "
                    + "would carry a null body." );
            return new String( in.readAllBytes(), StandardCharsets.UTF_8 );
        } catch ( final IOException e ) {
            fail( "Failed to read " + INSTRUCTIONS_RESOURCE + ": " + e.getMessage() );
            return null;
        }
    }
}
