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
import com.wikantik.api.frontmatter.FrontmatterParser;
import com.wikantik.api.frontmatter.ParsedPage;
import com.wikantik.api.managers.PageManager;
import com.wikantik.api.pages.PageSaveHelper;
import com.wikantik.api.pages.SaveOptions;
import com.wikantik.api.pages.VersionConflictException;

import com.wikantik.api.exceptions.ProviderException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * MCP tool that imports wiki pages from a working directory of Markdown files.
 * Reads {@code .md} files (with YAML frontmatter), compares against the current
 * wiki state using the {@code _export.json} manifest for conflict detection,
 * and saves changes through {@link PageSaveHelper}.
 *
 * <p>This is the "apply" step of the export→edit→import workflow.</p>
 */
public class ImportContentTool implements McpTool, AuthorConfigurable {

    private static final Logger LOG = LogManager.getLogger( ImportContentTool.class );
    public static final String TOOL_NAME = "import_content";

    private final PageSaveHelper pageSaveHelper;
    private final PageManager pageManager;
    private String defaultAuthor = "MCP";

    public ImportContentTool( final PageSaveHelper pageSaveHelper, final PageManager pageManager ) {
        this.pageSaveHelper = pageSaveHelper;
        this.pageManager = pageManager;
    }

    @Override
    public String name() {
        return TOOL_NAME;
    }

    @Override
    public void setDefaultAuthor( final String author ) {
        this.defaultAuthor = author;
    }

    @Override
    public McpSchema.Tool definition() {
        final Map< String, Object > properties = new LinkedHashMap<>();
        properties.put( "directory", Map.of( "type", "string",
                "description", "Path to the working directory containing .md files (from export_content)" ) );
        properties.put( "author", Map.of( "type", "string",
                "description", "Author name for all changes (defaults to MCP client name)" ) );
        properties.put( "changeNote", Map.of( "type", "string",
                "description", "Change note applied to all saved pages" ) );
        properties.put( "deleteMissing", Map.of( "type", "boolean",
                "description", "If true, pages in the manifest but absent from the directory are deleted (default false)" ) );
        properties.put( "skipConflicts", Map.of( "type", "boolean",
                "description", "If true, skip pages with version conflicts instead of failing (default false)" ) );

        return McpSchema.Tool.builder()
                .name( TOOL_NAME )
                .description( "Import wiki pages from a working directory of Markdown files. " +
                        "Reads .md files with YAML frontmatter and saves them to the wiki. " +
                        "Uses _export.json manifest for conflict detection. " +
                        "Returns per-page results: {results: [{pageName, action, success, error?}], summary}." )
                .inputSchema( new McpSchema.JsonSchema( "object", properties, List.of( "directory" ), null, null, null ) )
                .annotations( new McpSchema.ToolAnnotations( null, false, false, false, null, null ) )
                .build();
    }

    @Override
    public McpSchema.CallToolResult execute( final Map< String, Object > arguments ) {
        final String directoryArg = McpToolUtils.getString( arguments, "directory" );
        final String author = McpToolUtils.getString( arguments, "author" );
        final String changeNote = McpToolUtils.getString( arguments, "changeNote" );
        final boolean deleteMissing = McpToolUtils.getBoolean( arguments, "deleteMissing" );
        final boolean skipConflicts = McpToolUtils.getBoolean( arguments, "skipConflicts" );

        if ( directoryArg == null || directoryArg.isBlank() ) {
            return McpToolUtils.errorResult( McpToolUtils.SHARED_GSON, "directory is required" );
        }

        final Path dir = Path.of( directoryArg );
        if ( !Files.isDirectory( dir ) ) {
            return McpToolUtils.errorResult( McpToolUtils.SHARED_GSON,
                    "Directory does not exist: " + directoryArg );
        }

        final String effectiveAuthor = author != null ? author : defaultAuthor;

        try {
            final ExportManifest manifest = ExportManifest.readFrom( dir );
            final Map< String, Integer > manifestVersions = manifest != null
                    ? manifest.getPageVersions()
                    : Map.of();

            final Map< String, Path > mdFiles = PreviewImportTool.collectMarkdownFiles( dir );

            if ( mdFiles.isEmpty() ) {
                return McpToolUtils.errorResult( McpToolUtils.SHARED_GSON,
                        "No .md files found in directory.",
                        "Ensure the directory contains Markdown files exported by export_content." );
            }

            final List< Map< String, Object > > results = new ArrayList<>();
            int added = 0, modified = 0, skipped = 0, deleted = 0, errors = 0;

            // Process each .md file
            for ( final Map.Entry< String, Path > entry : mdFiles.entrySet() ) {
                final String pageName = entry.getKey();
                final String fileContent = Files.readString( entry.getValue(), StandardCharsets.UTF_8 );
                final Page currentPage = pageManager.getPage( pageName );

                // Check for version conflict
                if ( currentPage != null && manifestVersions.containsKey( pageName ) ) {
                    final int exportedVersion = manifestVersions.get( pageName );
                    final int currentVersion = McpToolUtils.normalizeVersion( currentPage.getVersion() );
                    if ( currentVersion != exportedVersion ) {
                        if ( skipConflicts ) {
                            results.add( Map.of( "pageName", pageName, "action", "skipped",
                                    "success", false, "reason", "Version conflict: exported v" + exportedVersion
                                                                + ", current v" + currentVersion ) );
                            skipped++;
                            continue;
                        } else {
                            results.add( Map.of( "pageName", pageName, "action", "conflict",
                                    "success", false, "error", "Version conflict: exported v" + exportedVersion
                                                               + ", current v" + currentVersion ) );
                            errors++;
                            continue;
                        }
                    }
                }

                // Skip if content unchanged
                if ( currentPage != null ) {
                    final String currentText = pageManager.getPureText( pageName, -1 );
                    if ( fileContent.equals( currentText ) ) {
                        results.add( Map.of( "pageName", pageName, "action", "unchanged", "success", true ) );
                        skipped++;
                        continue;
                    }
                }

                // Save the page
                final String action = currentPage == null ? "created" : "updated";
                try {
                    final ParsedPage parsed = FrontmatterParser.parse( fileContent );
                    final Map< String, Object > metadata = parsed.metadata().isEmpty() ? null : new LinkedHashMap<>( parsed.metadata() );

                    pageSaveHelper.saveText( pageName, parsed.body(),
                            SaveOptions.builder()
                                    .author( effectiveAuthor )
                                    .changeNote( changeNote )
                                    .markupSyntax( "markdown" )
                                    .metadata( metadata )
                                    .replaceMetadata( true )   // file content is authoritative
                                    .build() );

                    McpAudit.logWrite( TOOL_NAME, action, pageName, effectiveAuthor );
                    results.add( Map.of( "pageName", pageName, "action", action, "success", true ) );
                    if ( "created".equals( action ) ) {
                        added++;
                    } else {
                        modified++;
                    }
                } catch ( final VersionConflictException e ) {
                    results.add( Map.of( "pageName", pageName, "action", "conflict",
                            "success", false, "error", e.getMessage() ) );
                    errors++;
                } catch ( final Exception e ) {
                    LOG.error( "Failed to import page {}: {}", pageName, e.getMessage(), e );
                    results.add( Map.of( "pageName", pageName, "action", "error",
                            "success", false, "error", e.getMessage() ) );
                    errors++;
                }
            }

            // Handle deletions
            if ( deleteMissing && manifest != null ) {
                for ( final String pageName : manifestVersions.keySet() ) {
                    if ( !mdFiles.containsKey( pageName ) && pageManager.pageExists( pageName ) ) {
                        try {
                            pageManager.deletePage( pageName );
                            McpAudit.logWrite( TOOL_NAME, "deleted", pageName, effectiveAuthor );
                            results.add( Map.of( "pageName", pageName, "action", "deleted", "success", true ) );
                            deleted++;
                        } catch ( final Exception e ) {
                            LOG.error( "Failed to delete page {}: {}", pageName, e.getMessage(), e );
                            results.add( Map.of( "pageName", pageName, "action", "delete_failed",
                                    "success", false, "error", e.getMessage() ) );
                            errors++;
                        }
                    }
                }
            }

            final Map< String, Object > response = new LinkedHashMap<>();
            response.put( "results", results );

            final Map< String, Object > summary = new LinkedHashMap<>();
            summary.put( "totalProcessed", mdFiles.size() );
            summary.put( "created", added );
            summary.put( "updated", modified );
            summary.put( "skipped", skipped );
            summary.put( "deleted", deleted );
            summary.put( "errors", errors );
            response.put( "summary", summary );

            return McpToolUtils.jsonResult( McpToolUtils.SHARED_GSON, response );

        } catch ( final IOException | ProviderException e ) {
            LOG.error( "Failed to import content from {}: {}", directoryArg, e.getMessage(), e );
            return McpToolUtils.errorResult( McpToolUtils.SHARED_GSON,
                    "Import failed: " + e.getMessage() );
        }
    }
}
