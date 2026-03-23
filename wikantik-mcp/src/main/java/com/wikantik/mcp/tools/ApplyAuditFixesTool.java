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


import io.modelcontextprotocol.spec.McpSchema;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import com.wikantik.api.core.Page;
import com.wikantik.api.providers.PageProvider;
import com.wikantik.frontmatter.FrontmatterParser;
import com.wikantik.frontmatter.ParsedPage;
import com.wikantik.pages.PageManager;
import com.wikantik.pages.PageSaveHelper;
import com.wikantik.pages.SaveOptions;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP tool that applies trivial, unambiguous audit fixes identified by {@code audit_cluster}.
 * Each fix is applied independently — failures for one fix do not block others.
 *
 * <h2>Command Pattern</h2>
 * <p>Fix actions are dispatched via a {@link FixAction} command interface backed by an
 * instance-level dispatch map ({@link #actions}). Each supported action string
 * (e.g. {@code "set_metadata"}) maps to a {@code FixAction} implementation that encapsulates
 * the logic for that fix type. Adding a new fix action requires only creating a new
 * {@code FixAction} static inner class and registering it in the map — no switch statement
 * to modify.</p>
 */
public class ApplyAuditFixesTool implements McpTool, AuthorConfigurable {

    private static final Logger LOG = LogManager.getLogger( ApplyAuditFixesTool.class );
    public static final String TOOL_NAME = "apply_audit_fixes";

    /**
     * Command interface for individual fix actions.
     * <p>Each implementation encapsulates the logic for one kind of audit fix
     * (e.g. setting metadata, adding a hub backlink, or correcting a typo link).
     * Implementations are registered in {@link #actions} and invoked from
     * {@link ApplyAuditFixesTool#execute(Map)}.</p>
     */
    interface FixAction {
        void apply( Map< String, Object > fix, String pageName, String author,
                    String changeNote, PageManager pm, Map< String, Object > result ) throws Exception;
    }

    @Override
    public String name() {
        return TOOL_NAME;
    }

    private final PageSaveHelper pageSaveHelper;
    private final PageManager pageManager;

    /** Dispatch table mapping action name strings to their {@link FixAction} implementations. */
    private final Map< String, FixAction > actions;

    private String defaultAuthor = "MCP";

    public ApplyAuditFixesTool( final PageSaveHelper pageSaveHelper, final PageManager pageManager ) {
        this.pageSaveHelper = pageSaveHelper;
        this.pageManager = pageManager;
        this.actions = Map.of(
                "set_metadata", new SetMetadataAction( pageSaveHelper ),
                "add_hub_backlink", new AddHubBacklinkAction( pageSaveHelper ),
                "fix_typo_link", new FixTypoLinkAction( pageSaveHelper )
        );
    }

    @Override
    public void setDefaultAuthor( final String defaultAuthor ) {
        this.defaultAuthor = defaultAuthor;
    }

    @Override
    public McpSchema.Tool definition() {
        final Map< String, Object > fixSchema = new LinkedHashMap<>();
        fixSchema.put( "type", "object" );
        fixSchema.put( "properties", Map.of(
                "page", Map.of( "type", "string", "description", "Page name to fix" ),
                "action", Map.of( "type", "string", "description",
                        "Fix action: set_metadata, add_hub_backlink, fix_typo_link" ),
                "field", Map.of( "type", "string", "description", "Metadata field name (for set_metadata)" ),
                "value", Map.of( "description", "Value to set (for set_metadata)" ),
                "hubPage", Map.of( "type", "string", "description", "Hub page name to link to (for add_hub_backlink)" ),
                "brokenLink", Map.of( "type", "string", "description", "The broken link target (for fix_typo_link)" ),
                "correctedLink", Map.of( "type", "string", "description", "The corrected link target (for fix_typo_link)" )
        ) );
        fixSchema.put( "required", List.of( "page", "action" ) );

        final Map< String, Object > properties = new LinkedHashMap<>();
        properties.put( "fixes", Map.of( "type", "array", "description",
                "Array of fix actions to apply", "items", fixSchema ) );
        properties.put( "author", Map.of( "type", "string", "description",
                "Author name for all fixes (defaults to MCP client name)" ) );
        properties.put( "changeNote", Map.of( "type", "string", "description",
                "Optional change note applied to all fixes" ) );

        return McpSchema.Tool.builder()
                .name( TOOL_NAME )
                .description( "Apply trivial audit fixes identified by audit_cluster. " +
                        "Supports: set_metadata (set a frontmatter field), " +
                        "add_hub_backlink (append hub link to See Also section), " +
                        "fix_typo_link (replace a broken link with the corrected one). " +
                        "Each fix is independent — partial failures don't block others. " +
                        "Returns {results: [{page, action, success, detail, previousValue}]}." )
                .inputSchema( new McpSchema.JsonSchema( "object", properties, List.of( "fixes" ), null, null, null ) )
                .annotations( new McpSchema.ToolAnnotations( null, false, false, true, null, null ) )
                .build();
    }

    @SuppressWarnings( "unchecked" )
    @Override
    public McpSchema.CallToolResult execute( final Map< String, Object > arguments ) {
        final List< Map< String, Object > > fixes = ( List< Map< String, Object > > ) arguments.get( "fixes" );
        final String author = McpToolUtils.getString( arguments, "author" );
        final String changeNote = McpToolUtils.getString( arguments, "changeNote" );
        final String effectiveAuthor = author != null ? author : defaultAuthor;

        if ( fixes == null || fixes.isEmpty() ) {
            return McpToolUtils.errorResult( McpToolUtils.SHARED_GSON,
                    "No fixes provided",
                    "Provide an array of fix objects in the fixes parameter." );
        }

        final List< Map< String, Object > > results = new ArrayList<>();

        for ( final Map< String, Object > fix : fixes ) {
            final String pageName = ( String ) fix.get( "page" );
            final String action = ( String ) fix.get( "action" );

            final Map< String, Object > entry = new LinkedHashMap<>();
            entry.put( "page", pageName );
            entry.put( "action", action );

            try {
                final Page currentPage = pageManager.getPage( pageName );
                if ( currentPage == null ) {
                    entry.put( "success", false );
                    entry.put( "detail", "Page not found: " + pageName );
                    results.add( entry );
                    continue;
                }

                final FixAction fixAction = actions.get( action );
                if ( fixAction != null ) {
                    fixAction.apply( fix, pageName, effectiveAuthor, changeNote, pageManager, entry );
                } else {
                    entry.put( "success", false );
                    entry.put( "detail", "Unknown action: " + action );
                }
            } catch ( final Exception e ) {
                LOG.error( "Failed to apply fix {} on page {}: {}", action, pageName, e.getMessage(), e );
                entry.put( "success", false );
                entry.put( "detail", e.getMessage() );
            }

            results.add( entry );
        }

        return McpToolUtils.jsonResult( McpToolUtils.SHARED_GSON, Map.of( "results", results ) );
    }

    // ---- FixAction implementations (static inner classes) ----

    /** Sets a single frontmatter metadata field to the given value. */
    static class SetMetadataAction implements FixAction {

        private final PageSaveHelper pageSaveHelper;

        SetMetadataAction( final PageSaveHelper pageSaveHelper ) {
            this.pageSaveHelper = pageSaveHelper;
        }

        @Override
        public void apply( final Map< String, Object > fix, final String pageName, final String author,
                           final String changeNote, final PageManager pageManager,
                           final Map< String, Object > entry ) throws Exception {
            final String field = ( String ) fix.get( "field" );
            final Object value = fix.get( "value" );

            final String rawText = pageManager.getPureText( pageName, PageProvider.LATEST_VERSION );
            final ParsedPage parsed = FrontmatterParser.parse( rawText );
            final Map< String, Object > metadata = new LinkedHashMap<>( parsed.metadata() );

            // Capture previous value
            final Object previousValue = metadata.get( field );
            entry.put( "previousValue", previousValue );

            final String opError = MetadataOperations.apply( metadata, field, "set", value );
            if ( opError != null ) {
                entry.put( "success", false );
                entry.put( "detail", opError );
                return;
            }

            pageSaveHelper.saveText( pageName, parsed.body(),
                    SaveOptions.builder()
                            .author( author )
                            .changeNote( changeNote != null ? changeNote : "Audit fix: set " + field )
                            .markupSyntax( "markdown" )
                            .metadata( metadata )
                            .replaceMetadata( true )
                            .build() );

            entry.put( "success", true );
            entry.put( "detail", "Set " + field + " = " + value );
        }
    }

    /** Appends a hub backlink to the page's "See Also" section, creating it if absent. */
    static class AddHubBacklinkAction implements FixAction {

        private final PageSaveHelper pageSaveHelper;

        AddHubBacklinkAction( final PageSaveHelper pageSaveHelper ) {
            this.pageSaveHelper = pageSaveHelper;
        }

        @Override
        public void apply( final Map< String, Object > fix, final String pageName, final String author,
                           final String changeNote, final PageManager pageManager,
                           final Map< String, Object > entry ) throws Exception {
            final String hubPage = ( String ) fix.get( "hubPage" );
            if ( hubPage == null || hubPage.isBlank() ) {
                entry.put( "success", false );
                entry.put( "detail", "hubPage is required for add_hub_backlink" );
                return;
            }

            final String rawText = pageManager.getPureText( pageName, PageProvider.LATEST_VERSION );
            final ParsedPage parsed = FrontmatterParser.parse( rawText );
            String body = parsed.body();

            // Check if link already exists
            final String linkText = "[" + hubPage + "](" + hubPage + ")";
            if ( body.contains( "(" + hubPage + ")" ) ) {
                entry.put( "success", true );
                entry.put( "detail", "Hub backlink already exists" );
                return;
            }

            // Find or create See Also section
            final int seeAlsoIdx = body.indexOf( "## See Also" );
            if ( seeAlsoIdx >= 0 ) {
                // Find the end of the See Also section (next ## heading or end of text)
                int insertPos = body.indexOf( "\n## ", seeAlsoIdx + 1 );
                if ( insertPos < 0 ) {
                    insertPos = body.length();
                }
                // Insert before the next section
                final String insertion = "\n- " + linkText;
                body = body.substring( 0, insertPos ) + insertion + body.substring( insertPos );
            } else {
                // Append new See Also section
                body = body + "\n\n## See Also\n\n- " + linkText + "\n";
            }

            pageSaveHelper.saveText( pageName, body,
                    SaveOptions.builder()
                            .author( author )
                            .changeNote( changeNote != null ? changeNote : "Audit fix: add hub backlink to " + hubPage )
                            .markupSyntax( "markdown" )
                            .metadata( new LinkedHashMap<>( parsed.metadata() ) )
                            .replaceMetadata( true )
                            .build() );

            entry.put( "success", true );
            entry.put( "detail", "Added hub backlink to " + hubPage + " in See Also section" );
        }
    }

    /** Replaces a broken/typo link target with the corrected one throughout the page body. */
    static class FixTypoLinkAction implements FixAction {

        private final PageSaveHelper pageSaveHelper;

        FixTypoLinkAction( final PageSaveHelper pageSaveHelper ) {
            this.pageSaveHelper = pageSaveHelper;
        }

        @Override
        public void apply( final Map< String, Object > fix, final String pageName, final String author,
                           final String changeNote, final PageManager pageManager,
                           final Map< String, Object > entry ) throws Exception {
            final String brokenLink = ( String ) fix.get( "brokenLink" );
            final String correctedLink = ( String ) fix.get( "correctedLink" );

            if ( brokenLink == null || correctedLink == null ) {
                entry.put( "success", false );
                entry.put( "detail", "brokenLink and correctedLink are required for fix_typo_link" );
                return;
            }

            final String rawText = pageManager.getPureText( pageName, PageProvider.LATEST_VERSION );
            final ParsedPage parsed = FrontmatterParser.parse( rawText );
            String body = parsed.body();

            // Replace link targets: ](brokenLink) → ](correctedLink)
            final String oldTarget = "](" + brokenLink + ")";
            final String newTarget = "](" + correctedLink + ")";
            if ( !body.contains( oldTarget ) ) {
                entry.put( "success", true );
                entry.put( "detail", "Broken link not found in body — already fixed or different format" );
                return;
            }

            body = body.replace( oldTarget, newTarget );

            // Also fix cases where the link text matches the broken link: [brokenLink](brokenLink) → [correctedLink](correctedLink)
            final String oldFullLink = "[" + brokenLink + "](" + correctedLink + ")";
            final String newFullLink = "[" + correctedLink + "](" + correctedLink + ")";
            body = body.replace( oldFullLink, newFullLink );

            pageSaveHelper.saveText( pageName, body,
                    SaveOptions.builder()
                            .author( author )
                            .changeNote( changeNote != null ? changeNote : "Audit fix: correct link " + brokenLink + " → " + correctedLink )
                            .markupSyntax( "markdown" )
                            .metadata( new LinkedHashMap<>( parsed.metadata() ) )
                            .replaceMetadata( true )
                            .build() );

            entry.put( "success", true );
            entry.put( "detail", "Replaced " + brokenLink + " → " + correctedLink );
            entry.put( "previousValue", brokenLink );
        }
    }
}
