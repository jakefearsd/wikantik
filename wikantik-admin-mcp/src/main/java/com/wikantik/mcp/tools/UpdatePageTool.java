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
import com.wikantik.api.frontmatter.FrontmatterParseException;
import com.wikantik.api.frontmatter.FrontmatterParser;
import com.wikantik.api.frontmatter.ParsedPage;
import com.wikantik.api.managers.PageManager;
import com.wikantik.api.managers.SystemPageRegistry;
import com.wikantik.api.pages.PageSaveHelper;
import com.wikantik.api.pages.SaveOptions;
import com.wikantik.api.providers.PageProvider;
import io.modelcontextprotocol.spec.McpSchema;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP tool: edit an existing wiki page with optimistic locking via
 * {@code expectedContentHash}. On hash mismatch, returns {updated:false,
 * error:"hash mismatch", currentHash} so the agent can re-fetch and retry.
 */
public class UpdatePageTool extends DefaultAuthorTool implements McpTool {

    private static final Logger LOG = LogManager.getLogger( UpdatePageTool.class );
    public static final String TOOL_NAME = "update_page";

    private final PageSaveHelper saveHelper;
    private final PageManager pageManager;
    private final SystemPageRegistry systemPageRegistry;

    public UpdatePageTool( final PageSaveHelper saveHelper, final PageManager pageManager,
                           final SystemPageRegistry systemPageRegistry ) {
        this.saveHelper = saveHelper;
        this.pageManager = pageManager;
        this.systemPageRegistry = systemPageRegistry;
    }

    @Override
    public String name() {
        return TOOL_NAME;
    }

    @Override
    public McpSchema.Tool definition() {
        final Map< String, Object > properties = new LinkedHashMap<>();
        properties.put( "pageName", Map.of(
                "type", "string",
                "description", "Name of the existing page to update.",
                "examples", List.of( "HybridRetrieval" )
        ) );
        properties.put( "content", Map.of(
                "type", "string",
                "description", "New markdown body.",
                "examples", List.of( "---\ntitle: Hybrid Retrieval\nsummary: BM25 + dense + graph-aware rerank, with fail-closed BM25 fallback.\n---\n\n# Hybrid Retrieval\n\nUpdated body..." )
        ) );
        properties.put( "metadata", Map.of(
                "type", "object",
                "description", "Optional frontmatter metadata to merge.",
                "examples", List.of( Map.of(
                        "tags", List.of( "retrieval", "search" ),
                        "verified_at", "2026-04-25"
                ) )
        ) );
        properties.put( "expectedContentHash", Map.of(
                "type", "string",
                "description", "SHA-256 of the page's current raw text, obtained from " +
                        "the last get_page or retrieve_context call. Required for optimistic locking.",
                "examples", List.of( "sha256:9c3f8a1d4b6e7c2a5f8e1d3b7a9c0e2d4f6a8b1c3e5d7f9a0b2c4d6e8f0a2b4d" )
        ) );

        final Map< String, Object > outputSchema = new LinkedHashMap<>();
        outputSchema.put( "type", "object" );
        outputSchema.put( "examples", List.of(
                Map.of(
                        "pageName", "HybridRetrieval",
                        "updated", true,
                        "newContentHash", "sha256:1a2b3c4d5e6f7a8b9c0d1e2f3a4b5c6d7e8f9a0b1c2d3e4f5a6b7c8d9e0f1a2b",
                        "newVersion", 8
                ),
                Map.of(
                        "pageName", "HybridRetrieval",
                        "updated", false,
                        "error", "hash mismatch",
                        "currentHash", "sha256:fedcba9876543210fedcba9876543210fedcba9876543210fedcba9876543210"
                )
        ) );

        return McpSchema.Tool.builder()
            .name( TOOL_NAME )
            .description( "Edit an existing page with optimistic locking. Returns " +
                "{updated, newContentHash, newVersion} on success or " +
                "{updated:false, error:'hash mismatch', currentHash} on drift so " +
                "the agent can re-fetch and retry. System pages (CSS themes, menu " +
                "fragments, help pages, anything shipped with the wiki) cannot be " +
                "edited via MCP — those updates require admin UI / direct DB access." )
            .inputSchema( new McpSchema.JsonSchema(
                "object", properties,
                List.of( "pageName", "content", "expectedContentHash" ), null, null, null ) )
            .outputSchema( outputSchema )
            .annotations( new McpSchema.ToolAnnotations( null, false, false, true, null, null ) )
            .build();
    }

    @Override
    public McpSchema.CallToolResult execute( final Map< String, Object > arguments ) {
        try {
            final String pageName = McpToolUtils.getString( arguments, "pageName" );
            final String content = McpToolUtils.getString( arguments, "content" );
            final String expectedHash = McpToolUtils.getString( arguments, "expectedContentHash" );
            if ( pageName == null || pageName.isBlank() ) {
                return McpToolUtils.errorResult( McpToolUtils.SHARED_GSON,
                    "pageName must not be blank" );
            }
            if ( content == null ) {
                return McpToolUtils.errorResult( McpToolUtils.SHARED_GSON,
                    "content must not be null" );
            }
            if ( expectedHash == null || expectedHash.isBlank() ) {
                return McpToolUtils.errorResult( McpToolUtils.SHARED_GSON,
                    "expectedContentHash required" );
            }

            if ( systemPageRegistry != null && systemPageRegistry.isSystemPage( pageName ) ) {
                final Map< String, Object > refused = new LinkedHashMap<>();
                refused.put( "pageName", pageName );
                refused.put( "updated", false );
                refused.put( "error", "system page — refusing to edit via MCP" );
                McpAudit.logWrite( TOOL_NAME, "refused-system-page", pageName, defaultAuthor );
                return McpToolUtils.jsonResult( McpToolUtils.SHARED_GSON, refused );
            }

            final Page existing = pageManager.getPage( pageName );
            if ( existing == null ) {
                final Map< String, Object > notFound = new LinkedHashMap<>();
                notFound.put( "pageName", pageName );
                notFound.put( "updated", false );
                notFound.put( "error", "not found" );
                return McpToolUtils.jsonResult( McpToolUtils.SHARED_GSON, notFound );
            }

            final String currentText = pageManager.getPureText( pageName, PageProvider.LATEST_VERSION );
            final String currentHash = McpToolUtils.computeContentHash(
                currentText == null ? "" : currentText );
            if ( !expectedHash.equals( currentHash ) ) {
                final Map< String, Object > mismatch = new LinkedHashMap<>();
                mismatch.put( "pageName", pageName );
                mismatch.put( "updated", false );
                mismatch.put( "error", "hash mismatch" );
                mismatch.put( "currentHash", currentHash );
                return McpToolUtils.jsonResult( McpToolUtils.SHARED_GSON, mismatch );
            }

            @SuppressWarnings( "unchecked" )
            final Map< String, Object > metadata = arguments.get( "metadata" ) instanceof Map< ?, ? >
                ? (Map< String, Object >) arguments.get( "metadata" ) : null;

            // Carry the existing canonical_id forward when the agent omits it. Without
            // this defensive re-injection the StructuralSpinePageFilter would mint a
            // fresh ULID (or, post-fix, fall back to a slug lookup), but in either case
            // we'd be relying on a downstream filter to recover an identity the page
            // already had on disk. Pin it here so update_page is identity-preserving
            // by construction.
            final String existingCanonicalId = extractCanonicalId( currentText );

            // Normalize agent input: parse any embedded frontmatter strictly, merge with
            // the explicit metadata arg (explicit wins), and let saveHelper re-emit YAML
            // via FrontmatterWriter — so values like 'title: Woodworking Joinery: Structural
            // Mechanics' get quoted correctly without the agent knowing YAML rules.
            final FrontmatterNormalizer.Normalized normalized;
            try {
                normalized = FrontmatterNormalizer.normalize( content, metadata, existingCanonicalId );
            } catch ( final FrontmatterParseException fpe ) {
                final Map< String, Object > parseFail = new LinkedHashMap<>();
                parseFail.put( "pageName", pageName );
                parseFail.put( "updated", false );
                parseFail.put( "error", "frontmatter parse error: " + fpe.getMessage() );
                if ( fpe.line() > 0 ) {
                    parseFail.put( "frontmatterLine", fpe.line() );
                }
                if ( fpe.column() > 0 ) {
                    parseFail.put( "frontmatterColumn", fpe.column() );
                }
                parseFail.put( "hint", "Wrap values containing ':' or other YAML special "
                    + "characters in double quotes, e.g. title: \"Foo: Bar\". Or pass the "
                    + "fields as the structured metadata argument and omit the YAML block "
                    + "from content." );
                return McpToolUtils.jsonResult( McpToolUtils.SHARED_GSON, parseFail );
            }
            final Map< String, Object > mergedMetadata = normalized.metadata();
            final boolean hasMetadata = !mergedMetadata.isEmpty();

            saveHelper.saveText( pageName, normalized.body(),
                SaveOptions.builder()
                    .author( defaultAuthor )
                    .changeNote( "update_page" )
                    .markupSyntax( "markdown" )
                    .metadata( hasMetadata ? mergedMetadata : null )
                    .replaceMetadata( hasMetadata )
                    .build() );
            McpAudit.logWrite( TOOL_NAME, "updated", pageName, defaultAuthor );

            // Hash the canonical post-save text so the agent's next update_page can
            // optimistic-lock against it. Composed deterministically from body +
            // merged metadata via the same writer the save pipeline uses.
            final String savedText = hasMetadata
                ? com.wikantik.api.frontmatter.FrontmatterWriter.write( mergedMetadata, normalized.body() )
                : normalized.body();
            final String newHash = McpToolUtils.computeContentHash( savedText );
            final Map< String, Object > ok = new LinkedHashMap<>();
            ok.put( "pageName", pageName );
            ok.put( "updated", true );
            ok.put( "newContentHash", newHash );
            ok.put( "newVersion", McpToolUtils.normalizeVersion( existing.getVersion() + 1 ) );
            return McpToolUtils.jsonResult( McpToolUtils.SHARED_GSON, ok );
        } catch ( final RuntimeException e ) {
            LOG.error( "update_page failed: {}", e.getMessage(), e );
            return McpToolUtils.errorResult( McpToolUtils.SHARED_GSON, e.getMessage() );
        } catch ( final Exception e ) {
            LOG.error( "update_page failed: {}", e.getMessage(), e );
            return McpToolUtils.errorResult( McpToolUtils.SHARED_GSON, e.getMessage() );
        }
    }

    /**
     * Best-effort extraction of {@code canonical_id} from the current on-disk text.
     * Uses the graceful parser so a page with a malformed frontmatter block (the
     * very thing FrontmatterValidationPageFilter exists to catch on save) still
     * round-trips here without throwing — we'd rather lose the canonical_id pin
     * than refuse to update a page whose frontmatter is already broken.
     */
    private static String extractCanonicalId( final String currentText ) {
        if ( currentText == null || currentText.isEmpty() ) {
            return null;
        }
        final ParsedPage parsed = FrontmatterParser.parse( currentText );
        final Object id = parsed.metadata().get( "canonical_id" );
        if ( id == null ) {
            return null;
        }
        final String s = id.toString().trim();
        return s.isEmpty() ? null : s;
    }
}
