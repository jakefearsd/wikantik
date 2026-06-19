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

import com.wikantik.util.WikiPageNameValidator;

import com.wikantik.api.content.ContentValidationException;
import com.wikantik.api.content.ContentWarningSink;
import com.wikantik.api.core.Page;
import com.wikantik.api.frontmatter.FrontmatterParseException;
import com.wikantik.api.frontmatter.FrontmatterParser;
import com.wikantik.api.frontmatter.ParsedPage;
import com.wikantik.api.managers.PageManager;
import com.wikantik.api.managers.SystemPageRegistry;
import com.wikantik.api.exceptions.FrontmatterValidationException;
import com.wikantik.api.frontmatter.schema.FrontmatterWarningSink;
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
 * error:"hash mismatch", currentHash, latestContent, currentVersion} —
 * everything the agent needs to rebase its edit without an extra round trip.
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
        properties.put( "slug", Map.of(
                "type", "string",
                "description", "Name of the existing page to update.",
                "examples", List.of( "HybridRetrieval" )
        ) );
        properties.put( "content", Map.of(
                "type", "string",
                "description", "Optional. The new markdown body (replaces the current body). "
                        + "OMIT it to edit only metadata — the body is then left unchanged. You do "
                        + "NOT need to include the YAML frontmatter; existing frontmatter is "
                        + "preserved automatically. Any frontmatter block you do include here is "
                        + "merged on top of the existing frontmatter, exactly like the metadata argument.",
                "examples", List.of( "# Hybrid Retrieval\n\nUpdated body..." )
        ) );
        properties.put( "metadata", Map.of(
                "type", "object",
                "description", "Optional frontmatter fields to MERGE onto the page's existing "
                        + "frontmatter: listed fields are added or overwritten, every other existing "
                        + "field (title, cluster, tags, …) is preserved. update_page never replaces "
                        + "the whole frontmatter, so a one-field edit cannot drop the rest.",
                "examples", List.of( Map.of(
                        "summary", "BM25 + dense + KG rerank, fail-closed BM25 fallback.",
                        "tags", List.of( "retrieval", "search" )
                ) )
        ) );
        properties.put( "expectedContentHash", Map.of(
                "type", "string",
                "description", "SHA-256 hex of the page's current raw text — the " +
                        "contentHash returned by read_page (or the newContentHash from a " +
                        "prior update_page). Bare lowercase hex, no 'sha256:' prefix. " +
                        "Required for optimistic locking.",
                "examples", List.of( "9c3f8a1d4b6e7c2a5f8e1d3b7a9c0e2d4f6a8b1c3e5d7f9a0b2c4d6e8f0a2b4d" )
        ) );

        final Map< String, Object > outputSchema = new LinkedHashMap<>();
        outputSchema.put( "type", "object" );
        outputSchema.put( "examples", List.of(
                Map.of(
                        "pageName", "HybridRetrieval",
                        "updated", true,
                        "newContentHash", "1a2b3c4d5e6f7a8b9c0d1e2f3a4b5c6d7e8f9a0b1c2d3e4f5a6b7c8d9e0f1a2b",
                        "newVersion", 8
                ),
                Map.of(
                        "pageName", "HybridRetrieval",
                        "updated", false,
                        "error", "hash mismatch",
                        "currentHash", "fedcba9876543210fedcba9876543210fedcba9876543210fedcba9876543210",
                        "currentVersion", 9,
                        "latestContent", "---\ntitle: Hybrid Retrieval\n---\n\nUpdated body..."
                )
        ) );

        return McpSchema.Tool.builder()
            .name( TOOL_NAME )
            .description( "Edit an existing page with optimistic locking. Frontmatter is " +
                "MERGE-by-default: existing fields are preserved and only what you pass " +
                "(in metadata, or a frontmatter block in content) is overwritten — a one-field " +
                "edit can never wipe the rest. content is optional: omit it to change only " +
                "metadata (body unchanged). Provide content and/or metadata. Returns " +
                "{updated, newContentHash, newVersion} on success or " +
                "{updated:false, error:'hash mismatch', currentHash, currentVersion, latestContent} " +
                "on drift — the agent can rebase against latestContent immediately " +
                "without a separate read_page round trip. System pages (CSS themes, " +
                "menu fragments, help pages, anything shipped with the wiki) cannot " +
                "be edited via MCP — those updates require admin UI / direct DB access." )
            .inputSchema( new McpSchema.JsonSchema(
                "object", properties,
                List.of( "slug", "expectedContentHash" ), null, null, null ) )
            .outputSchema( outputSchema )
            .annotations( new McpSchema.ToolAnnotations( null, false, false, true, null, null ) )
            .build();
    }

    @Override
    public McpSchema.CallToolResult execute( final Map< String, Object > arguments ) {
        try {
            final String pageName = McpToolUtils.pageSlug( arguments );
            final String content = McpToolUtils.getString( arguments, "content" );
            final String expectedHash = McpToolUtils.getString( arguments, "expectedContentHash" );
            try {
                WikiPageNameValidator.requireValid( pageName, "pageName" );
            } catch ( final IllegalArgumentException iae ) {
                McpAudit.logWrite( TOOL_NAME, "rejected-invalid-name", String.valueOf( pageName ), defaultAuthor );
                return McpToolUtils.errorResult( McpToolUtils.SHARED_GSON, iae.getMessage() );
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
                // Background mutations (link-update sweeps, structural-spine
                // enforcement, etc.) move the hash out from under an in-flight
                // edit. Rather than force a re-read round trip, hand the agent
                // the current page state in the same response so it can rebase
                // immediately. Per McpServerCritique2026 #3.
                final Map< String, Object > mismatch = new LinkedHashMap<>();
                mismatch.put( "pageName", pageName );
                mismatch.put( "updated", false );
                mismatch.put( "error", "hash mismatch" );
                mismatch.put( "currentHash", currentHash );
                mismatch.put( "latestContent", currentText == null ? "" : currentText );
                final Page existingForVersion = pageManager.getPage( pageName );
                if ( existingForVersion != null ) {
                    mismatch.put( "currentVersion", existingForVersion.getVersion() );
                }
                return McpToolUtils.jsonResult( McpToolUtils.SHARED_GSON, mismatch );
            }

            @SuppressWarnings( "unchecked" )
            final Map< String, Object > metadata = arguments.get( "metadata" ) instanceof Map< ?, ? >
                ? (Map< String, Object >) arguments.get( "metadata" ) : null;

            if ( content == null && metadata == null ) {
                return McpToolUtils.errorResult( McpToolUtils.SHARED_GSON,
                    "provide content (the new body) and/or metadata (frontmatter fields to merge) — "
                        + "nothing to update" );
            }

            // Parse the page's CURRENT frontmatter + body once. update_page is merge-by-default:
            // the existing frontmatter is the base, and only the fields the caller passes (in
            // metadata, or in a frontmatter block inside content) override it. This is what makes
            // a one-field edit incapable of wiping title/cluster/tags. The graceful parser keeps a
            // page with already-broken frontmatter updatable.
            final ParsedPage existingParsed = FrontmatterParser.parse(
                currentText == null ? "" : currentText );

            // Carry the existing canonical_id forward when the agent omits it, so update_page is
            // identity-preserving by construction rather than relying on a downstream filter.
            final String existingCanonicalId = extractCanonicalId( currentText );

            // content is optional: when omitted, keep the existing body unchanged and update only
            // the frontmatter. Feed the existing body (frontmatter already stripped) to the
            // normalizer so its body() round-trips unchanged.
            final String contentToNormalize = content != null ? content : existingParsed.body();

            // Normalize agent input: parse any embedded frontmatter strictly, merge with
            // the explicit metadata arg (explicit wins), and let saveHelper re-emit YAML
            // via FrontmatterWriter — so values like 'title: Woodworking Joinery: Structural
            // Mechanics' get quoted correctly without the agent knowing YAML rules.
            final FrontmatterNormalizer.Normalized normalized;
            try {
                normalized = FrontmatterNormalizer.normalize( contentToNormalize, metadata, existingCanonicalId );
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
            // Merge: existing frontmatter is the base; content-frontmatter + the explicit
            // metadata arg (both carried in normalized.metadata()) override field-by-field.
            // Every existing field the caller did not touch is preserved.
            final Map< String, Object > mergedMetadata = new LinkedHashMap<>( existingParsed.metadata() );
            mergedMetadata.putAll( normalized.metadata() );
            final boolean hasMetadata = !mergedMetadata.isEmpty();

            FrontmatterWarningSink.clear();
            ContentWarningSink.clear();
            saveHelper.saveText( pageName, normalized.body(),
                SaveOptions.builder()
                    .author( defaultAuthor )
                    .changeNote( "update_page" )
                    .markupSyntax( "markdown" )
                    .metadata( hasMetadata ? mergedMetadata : null )
                    .replaceMetadata( hasMetadata )
                    .build() );
            McpAudit.logWrite( TOOL_NAME, "updated", pageName, defaultAuthor );

            // Hash the ACTUAL persisted text — re-read it rather than reconstructing
            // it here. Save-time filters (StructuralSpinePageFilter canonical_id
            // enforcement, frontmatter/line-ending normalization, etc.) can rewrite the
            // stored bytes after saveText returns, so any tool-side reconstruction would
            // drift from what read_page later reports. Reading getPureText (the same
            // source read_page and the mismatch check above use) makes newContentHash
            // authoritative, so the agent can chain its next update_page against it
            // without a read-after-write round trip.
            final String savedText = pageManager.getPureText( pageName, PageProvider.LATEST_VERSION );
            final String newHash = McpToolUtils.computeContentHash( savedText == null ? "" : savedText );
            final Map< String, Object > ok = new LinkedHashMap<>();
            ok.put( "pageName", pageName );
            ok.put( "updated", true );
            ok.put( "newContentHash", newHash );
            ok.put( "newVersion", McpToolUtils.normalizeVersion( existing.getVersion() + 1 ) );
            final var fmWarnings = FrontmatterWarningSink.drain();
            if ( !fmWarnings.isEmpty() ) {
                ok.put( "frontmatterWarnings", fmWarnings );
            }
            final var mathWarnings = ContentWarningSink.drain();
            if ( !mathWarnings.isEmpty() ) {
                ok.put( "mathWarnings", mathWarnings );
            }
            return McpToolUtils.jsonResult( McpToolUtils.SHARED_GSON, ok );
        } catch ( final ContentValidationException e ) {
            // Math validator refused this write (ERROR-severity).
            // Cite violations with excerpt+caret so the agent can locate and fix the error.
            ContentWarningSink.clear();
            final String rejectedPage = McpToolUtils.pageSlug( arguments );
            LOG.debug( "update_page rejected by math validation for {}: {}", rejectedPage, e.getMessage() );
            final Map< String, Object > refused = new LinkedHashMap<>();
            refused.put( "pageName", rejectedPage );
            refused.put( "updated", false );
            refused.put( "error", "math validation failed" );
            refused.put( "violations", e.violations() );
            return McpToolUtils.jsonResult( McpToolUtils.SHARED_GSON, refused );
        } catch ( final FrontmatterValidationException e ) {
            // Same schema validator the form + REST use refused this write (ERROR-severity).
            // Cite the structured violations so the agent can self-correct and retry.
            FrontmatterWarningSink.clear();
            final String rejectedPage = McpToolUtils.pageSlug( arguments );
            LOG.debug( "update_page rejected by frontmatter validation for {}: {}", rejectedPage, e.getMessage() );
            final Map< String, Object > refused = new LinkedHashMap<>();
            refused.put( "pageName", rejectedPage );
            refused.put( "updated", false );
            refused.put( "error", "frontmatter validation failed" );
            refused.put( "violations", e.violations() );
            return McpToolUtils.jsonResult( McpToolUtils.SHARED_GSON, refused );
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
