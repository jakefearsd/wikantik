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
package com.wikantik.mcp.tools;

import com.wikantik.api.core.Page;
import com.wikantik.api.frontmatter.FrontmatterParser;
import com.wikantik.api.frontmatter.FrontmatterWriter;
import com.wikantik.api.frontmatter.ParsedPage;
import com.wikantik.api.managers.PageManager;
import com.wikantik.api.pages.PageSaveHelper;
import com.wikantik.api.pages.SaveOptions;
import com.wikantik.api.structure.Confidence;
import io.modelcontextprotocol.spec.McpSchema;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Phase 1 of the Agent-Grade Content design: mark a set of pages as verified.
 * Writes {@code verified_at = NOW()} and {@code verified_by = <exchange author>}
 * into each page's frontmatter via the standard save pipeline. The
 * {@link com.wikantik.knowledge.structure.DefaultStructuralIndexService}'s
 * post-save rebuild propagates the change into {@code page_verification},
 * where {@code ConfidenceComputer} promotes the confidence to
 * {@code authoritative} when the verifier is on the trusted-authors list.
 *
 * <p>Optional {@code confidence} argument lets the verifier pin an explicit
 * value (typically {@code stale} to flag a known-stale page).</p>
 */
public class MarkPageVerifiedTool extends DefaultAuthorTool implements McpTool {

    private static final Logger LOG = LogManager.getLogger( MarkPageVerifiedTool.class );
    public static final String TOOL_NAME = "mark_page_verified";

    private final PageSaveHelper saveHelper;
    private final PageManager pageManager;

    public MarkPageVerifiedTool( final PageSaveHelper saveHelper, final PageManager pageManager ) {
        this.saveHelper = saveHelper;
        this.pageManager = pageManager;
    }

    @Override
    public String name() {
        return TOOL_NAME;
    }

    @Override
    public McpSchema.Tool definition() {
        final Map< String, Object > properties = new LinkedHashMap<>();
        properties.put( "pageNames", Map.of(
            "type", "array",
            "items", Map.of( "type", "string" ),
            "description", "Slugs of the pages to mark verified.",
            "examples", List.of( List.of( "HybridRetrieval", "AgentMemory" ) )
        ) );
        properties.put( "verifier", Map.of(
            "type", "string",
            "description", "Login_name of the verifying author. Defaults to the MCP exchange author.",
            "examples", List.of( "jakefear" )
        ) );
        properties.put( "confidence", Map.of(
            "type", "string",
            "description", "Optional explicit confidence override (authoritative | provisional | stale). " +
                "Omit to let the rule engine compute it from verified_at + trusted-authors.",
            "examples", List.of( "authoritative" )
        ) );
        properties.put( "changeNote", Map.of(
            "type", "string",
            "description", "Optional change note recorded with the save.",
            "examples", List.of( "quarterly content review — pages reread end-to-end" )
        ) );

        final Map< String, Object > outputSchema = new LinkedHashMap<>();
        outputSchema.put( "type", "object" );
        outputSchema.put( "examples", List.of( Map.of(
                "results", List.of(
                        Map.of(
                                "pageName", "HybridRetrieval",
                                "marked", true,
                                "verified_at", "2026-04-25T14:30:00Z",
                                "verified_by", "jakefear",
                                "confidence", "authoritative"
                        ),
                        Map.of(
                                "pageName", "AgentMemory",
                                "marked", true,
                                "verified_at", "2026-04-25T14:30:00Z",
                                "verified_by", "jakefear",
                                "confidence", "authoritative"
                        )
                ),
                "summary", Map.of( "markedCount", 2, "failedCount", 0 )
        ) ) );

        return McpSchema.Tool.builder()
            .name( TOOL_NAME )
            .description( "Mark a list of pages as verified. Writes verified_at = NOW() and " +
                "verified_by = <verifier> into each page's frontmatter. The structural index " +
                "rebuilds verification state on the next save event; if the verifier is in the " +
                "trusted-authors registry, confidence is computed as 'authoritative'." )
            .inputSchema( new McpSchema.JsonSchema(
                "object", properties,
                List.of( "pageNames" ), null, null, null ) )
            .outputSchema( outputSchema )
            .annotations( new McpSchema.ToolAnnotations( null, false, false, true, null, null ) )
            .build();
    }

    @Override
    @SuppressWarnings( "unchecked" )
    public McpSchema.CallToolResult execute( final Map< String, Object > arguments ) {
        try {
            final Object rawNames = arguments.get( "pageNames" );
            if ( !( rawNames instanceof List< ? > nameList ) || nameList.isEmpty() ) {
                return McpToolUtils.errorResult( McpToolUtils.SHARED_GSON,
                    "pageNames must be a non-empty array" );
            }
            final String verifierArg = McpToolUtils.getString( arguments, "verifier" );
            final String verifier = verifierArg != null && !verifierArg.isBlank()
                    ? verifierArg : getDefaultAuthor();
            if ( verifier == null || verifier.isBlank() ) {
                return McpToolUtils.errorResult( McpToolUtils.SHARED_GSON,
                    "verifier could not be resolved — pass {verifier: <login_name>} or configure default_author" );
            }
            final String confidenceOverride = McpToolUtils.getString( arguments, "confidence" );
            final Confidence pinned = confidenceOverride == null || confidenceOverride.isBlank()
                    ? null
                    : Confidence.fromWire( confidenceOverride )
                          .orElseThrow( () -> new IllegalArgumentException(
                              "unknown confidence: " + confidenceOverride ) );
            final String changeNote = McpToolUtils.getString( arguments, "changeNote" );

            final Instant now = Instant.now();
            final List< Map< String, Object > > results = new ArrayList<>( nameList.size() );
            int succeeded = 0;
            for ( final Object n : nameList ) {
                final String pageName = n == null ? null : n.toString().trim();
                final Map< String, Object > result = new LinkedHashMap<>();
                result.put( "pageName", pageName );
                if ( pageName == null || pageName.isEmpty() ) {
                    result.put( "ok", false );
                    result.put( "error", "blank page name" );
                    results.add( result );
                    continue;
                }
                try {
                    final Page page = pageManager.getPage( pageName );
                    if ( page == null ) {
                        result.put( "ok", false );
                        result.put( "error", "page not found" );
                        results.add( result );
                        continue;
                    }
                    final String original = pageManager.getPureText( page );
                    final ParsedPage parsed = FrontmatterParser.parse( original );
                    final Map< String, Object > metadata = new LinkedHashMap<>( parsed.metadata() );
                    metadata.put( "verified_at", now.toString() );
                    metadata.put( "verified_by", verifier );
                    if ( pinned != null ) {
                        metadata.put( "confidence", pinned.wireName() );
                    }
                    final String rewritten = FrontmatterWriter.write( metadata, parsed.body() );

                    saveHelper.saveText( pageName, rewritten,
                        SaveOptions.builder()
                            .author( verifier )
                            .changeNote( changeNote != null && !changeNote.isBlank()
                                ? changeNote
                                : "verified by " + verifier )
                            .build() );

                    result.put( "ok", true );
                    result.put( "verifiedAt", now.toString() );
                    result.put( "verifiedBy", verifier );
                    if ( pinned != null ) {
                        result.put( "confidence", pinned.wireName() );
                    }
                    succeeded++;
                } catch ( final Exception perPage ) {
                    LOG.warn( "mark_page_verified failed for {}: {}", pageName, perPage.getMessage() );
                    result.put( "ok", false );
                    result.put( "error", perPage.getMessage() );
                }
                results.add( result );
            }

            final Map< String, Object > envelope = new LinkedHashMap<>();
            envelope.put( "results", results );
            envelope.put( "succeeded", succeeded );
            envelope.put( "total", results.size() );
            return McpToolUtils.jsonResult( McpToolUtils.SHARED_GSON, envelope );
        } catch ( final Exception e ) {
            LOG.error( "mark_page_verified failed: {}", e.getMessage(), e );
            return McpToolUtils.errorResult( McpToolUtils.SHARED_GSON, e.getMessage() );
        }
    }
}
