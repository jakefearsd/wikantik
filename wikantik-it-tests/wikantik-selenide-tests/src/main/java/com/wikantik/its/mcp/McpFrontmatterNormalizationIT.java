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

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

/**
 * Wire-level guard for the frontmatter normalization layer that lets MCP callers
 * pass a markdown file with embedded YAML frontmatter without having to know YAML
 * quoting rules. The recurring failure mode this protects against:
 *
 * <pre>
 *   ---
 *   title: Woodworking Joinery: Structural Mechanics
 *   ---
 *   body
 * </pre>
 *
 * The unquoted colon makes SnakeYAML parse the value as a nested mapping start.
 * Without normalization the page used to save with empty metadata + a {@code WARN}
 * log; now the server either fixes it transparently (when the YAML is recoverable)
 * or rejects with a structured error containing line/column and a quoting hint.
 *
 * <p>Two layers are exercised end-to-end:</p>
 * <ol>
 *   <li>The MCP write tools' auto-normalization (well-quoted frontmatter round-trips
 *       through {@code FrontmatterNormalizer} → {@code FrontmatterWriter} → save).</li>
 *   <li>The {@code FrontmatterValidationPageFilter} save-time guard (malformed YAML
 *       that slips past the MCP normalizer — or comes from REST/JSP callers — is
 *       rejected with {@code FilterException}).</li>
 * </ol>
 */
public class McpFrontmatterNormalizationIT extends WithMcpTestSetup {

    @Test
    public void writePagesAcceptsContentWithProperlyQuotedFrontmatter() {
        // Happy path: agent passes the full markdown file (frontmatter + body) as the
        // content string with the value quoted. Server should normalize to a body-plus-
        // structured-metadata save and the saved page should round-trip correctly.
        final String pageName = uniquePageName( "FmQuoted" );
        final String content = "---\n"
                + "title: \"Woodworking Joinery: Structural Mechanics\"\n"
                + "tags:\n"
                + "  - woodworking\n"
                + "  - joinery\n"
                + "---\n"
                + "How to cut a mortise and tenon joint.\n";

        final Map< String, Object > response = mcp.callTool( "write_pages", Map.of(
                "pages", List.of( Map.of( "pageName", pageName, "content", content ) ) ) );

        @SuppressWarnings( "unchecked" )
        final List< Map< String, Object > > results =
                ( List< Map< String, Object > > ) response.get( "results" );
        Assertions.assertEquals( Boolean.TRUE, results.get( 0 ).get( "created" ),
                "well-quoted YAML must save successfully — got: " + results.get( 0 ) );

        // Read back and assert the title round-tripped (this is the invariant the
        // server-side normalization must preserve).
        final Map< String, Object > read = mcp.callTool( "read_page", Map.of( "pageName", pageName ) );
        Assertions.assertEquals( Boolean.TRUE, read.get( "exists" ) );
        final String body = ( String ) read.get( "content" );
        Assertions.assertNotNull( body );
        Assertions.assertTrue( body.contains( "Woodworking Joinery: Structural Mechanics" ),
                "title must survive normalization — got body:\n" + body );

        // Cleanup.
        mcp.callTool( "delete_pages", Map.of(
                "pageNames", List.of( pageName ),
                "confirm", true ) );
    }

    @Test
    public void writePagesRejectsContentWithUnquotedColonInTitle() {
        // The exact failure mode the user reported: agent or human writes
        // 'title: Foo: Bar' without quotes. The save-time validator (or the MCP
        // normalizer's strict parse, whichever fires first) must reject loudly
        // with a structured error rather than silently dropping metadata.
        final String pageName = uniquePageName( "FmBroken" );
        final String content = "---\n"
                + "title: Woodworking Joinery: Structural Mechanics\n"
                + "---\n"
                + "body\n";

        final Map< String, Object > response = mcp.callTool( "write_pages", Map.of(
                "pages", List.of( Map.of( "pageName", pageName, "content", content ) ) ) );

        @SuppressWarnings( "unchecked" )
        final List< Map< String, Object > > results =
                ( List< Map< String, Object > > ) response.get( "results" );
        Assertions.assertEquals( Boolean.FALSE, results.get( 0 ).get( "created" ),
                "malformed YAML must NOT save — got: " + results.get( 0 ) );
        final String error = String.valueOf( results.get( 0 ).get( "error" ) );
        Assertions.assertTrue(
                error.toLowerCase().contains( "frontmatter" )
                        || error.toLowerCase().contains( "yaml" )
                        || error.toLowerCase().contains( "mapping" ),
                "error must surface the YAML problem — got: " + error );

        // The hint should also explain the fix so an agent can self-correct.
        final String hint = String.valueOf( results.get( 0 ).get( "hint" ) );
        Assertions.assertTrue( hint.contains( "double quotes" ) || hint.contains( "metadata" ),
                "hint must mention quoting or the metadata field — got: " + hint );
    }

    @Test
    public void writePagesAcceptsStructuredMetadataWithColonValue() {
        // Alternative path: agent skips embedded frontmatter and uses the structured
        // metadata field directly. SnakeYAML's emitter quotes correctly, so the
        // saved page round-trips with no work on the agent's part.
        final String pageName = uniquePageName( "FmStruct" );
        final Map< String, Object > metadata = Map.of(
                "title", "Woodworking Joinery: Structural Mechanics",
                "tags", List.of( "woodworking", "joinery" ) );

        final Map< String, Object > response = mcp.callTool( "write_pages", Map.of(
                "pages", List.of( Map.of(
                        "pageName", pageName,
                        "content", "How to cut a mortise and tenon joint.\n",
                        "metadata", metadata ) ) ) );

        @SuppressWarnings( "unchecked" )
        final List< Map< String, Object > > results =
                ( List< Map< String, Object > > ) response.get( "results" );
        Assertions.assertEquals( Boolean.TRUE, results.get( 0 ).get( "created" ),
                "structured metadata path must always work — got: " + results.get( 0 ) );

        final Map< String, Object > read = mcp.callTool( "read_page", Map.of( "pageName", pageName ) );
        final String body = ( String ) read.get( "content" );
        Assertions.assertTrue( body.contains( "Woodworking Joinery: Structural Mechanics" ),
                "title must round-trip via structured metadata path — got body:\n" + body );

        mcp.callTool( "delete_pages", Map.of(
                "pageNames", List.of( pageName ),
                "confirm", true ) );
    }
}
