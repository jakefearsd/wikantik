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
import com.wikantik.api.core.Attachment;
import com.wikantik.api.core.Page;
import com.wikantik.api.managers.AttachmentManager;
import com.wikantik.api.managers.PageManager;
import com.wikantik.api.managers.SystemPageRegistry;

import com.wikantik.api.exceptions.ProviderException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * MCP tool that exports wiki pages as Markdown files to a working directory.
 * Each page becomes a {@code .md} file with YAML frontmatter intact. An
 * {@code _export.json} manifest records page versions for conflict detection on import.
 *
 * <p>This tool enables an export→edit→import workflow where agents work with
 * files directly using filesystem tools instead of chatty per-page CRUD calls.</p>
 */
public class ExportContentTool implements McpTool {

    private static final Logger LOG = LogManager.getLogger( ExportContentTool.class );
    public static final String TOOL_NAME = "export_content";
    private static final String ATTACHMENTS_DIR = "_attachments";

    private final PageManager pageManager;
    private final AttachmentManager attachmentManager;
    private final SystemPageRegistry systemPageRegistry;

    public ExportContentTool( final PageManager pageManager,
                              final AttachmentManager attachmentManager,
                              final SystemPageRegistry systemPageRegistry ) {
        this.pageManager = pageManager;
        this.attachmentManager = attachmentManager;
        this.systemPageRegistry = systemPageRegistry;
    }

    @Override
    public String name() {
        return TOOL_NAME;
    }

    @Override
    public McpSchema.Tool definition() {
        final Map< String, Object > properties = new LinkedHashMap<>();
        properties.put( "pages", Map.of(
                "type", "array",
                "items", Map.of( "type", "string" ),
                "description", "Page names or glob patterns (e.g. [\"AI*\", \"MachineLearning\"]). Omit to export all non-system pages." ) );
        properties.put( "includeAttachments", Map.of(
                "type", "boolean",
                "description", "If true, export attachments into an _attachments/ subdirectory (default false)" ) );
        properties.put( "directory", Map.of(
                "type", "string",
                "description", "Output directory path. If omitted, a temp directory is created and its path returned." ) );
        properties.put( "includeSystemPages", Map.of(
                "type", "boolean",
                "description", "If true, include system/template pages in the export (default false)" ) );

        return McpSchema.Tool.builder()
                .name( TOOL_NAME )
                .description( "Export wiki pages as Markdown files to a working directory. " +
                        "Each page becomes a .md file with YAML frontmatter. " +
                        "An _export.json manifest records page versions for conflict detection on import. " +
                        "Use this to export content, edit files with filesystem tools, then import_content to apply changes." )
                .inputSchema( new McpSchema.JsonSchema( "object", properties, List.of(), null, null, null ) )
                .annotations( new McpSchema.ToolAnnotations( null, true, false, false, null, null ) )
                .build();
    }

    @SuppressWarnings( "unchecked" )
    @Override
    public McpSchema.CallToolResult execute( final Map< String, Object > arguments ) {
        final List< String > pagePatterns = arguments.containsKey( "pages" )
                ? ( List< String > ) arguments.get( "pages" )
                : null;
        final boolean includeAttachments = McpToolUtils.getBoolean( arguments, "includeAttachments" );
        final String directoryArg = McpToolUtils.getString( arguments, "directory" );
        final boolean includeSystemPages = McpToolUtils.getBoolean( arguments, "includeSystemPages" );

        try {
            // Resolve output directory
            final Path outputDir;
            if ( directoryArg != null && !directoryArg.isBlank() ) {
                outputDir = Path.of( directoryArg );
                Files.createDirectories( outputDir );
            } else {
                outputDir = Files.createTempDirectory( "wiki-export-" );
            }

            // Collect matching pages
            final Collection< Page > allPages = pageManager.getAllPages();
            final List< Page > pagesToExport = filterPages( allPages, pagePatterns, includeSystemPages );

            if ( pagesToExport.isEmpty() ) {
                return McpToolUtils.errorResult( McpToolUtils.SHARED_GSON,
                        "No pages matched the given patterns.",
                        "Check page names with list_pages or use broader patterns." );
            }

            // Export each page as a .md file and build manifest
            final ExportManifest manifest = new ExportManifest(
                    "wikantik", Instant.now(), new LinkedHashMap<>() );

            int exported = 0;
            int attachmentCount = 0;
            for ( final Page page : pagesToExport ) {
                final String rawText = pageManager.getPureText( page.getName(), -1 );
                if ( rawText == null ) {
                    continue;
                }
                final Path pageFile = outputDir.resolve( page.getName() + ".md" );
                Files.writeString( pageFile, rawText, StandardCharsets.UTF_8 );
                manifest.putPageVersion( page.getName(), McpToolUtils.normalizeVersion( page.getVersion() ) );
                exported++;

                // Export attachments if requested
                if ( includeAttachments && attachmentManager != null ) {
                    attachmentCount += exportAttachments( page, outputDir );
                }
            }

            manifest.writeTo( outputDir );

            // Build result
            final Map< String, Object > result = new LinkedHashMap<>();
            result.put( "success", true );
            result.put( "directory", outputDir.toAbsolutePath().toString() );
            result.put( "pagesExported", exported );
            if ( includeAttachments ) {
                result.put( "attachmentsExported", attachmentCount );
            }
            result.put( "manifestFile", outputDir.resolve( ExportManifest.FILENAME ).toAbsolutePath().toString() );

            return McpToolUtils.jsonResult( McpToolUtils.SHARED_GSON, result );

        } catch ( final IOException | ProviderException e ) {
            LOG.error( "Failed to export content: {}", e.getMessage(), e );
            return McpToolUtils.errorResult( McpToolUtils.SHARED_GSON,
                    "Export failed: " + e.getMessage() );
        }
    }

    /**
     * Filters all pages by the given patterns and system-page preference.
     * Patterns support simple glob-style matching: {@code *} matches any sequence of characters.
     */
    List< Page > filterPages( final Collection< Page > allPages,
                              final List< String > patterns,
                              final boolean includeSystemPages ) {
        return allPages.stream()
                .filter( p -> includeSystemPages || systemPageRegistry == null
                              || !systemPageRegistry.isSystemPage( p.getName() ) )
                .filter( p -> matchesAnyPattern( p.getName(), patterns ) )
                .sorted( Comparator.comparing( Page::getName ) )
                .collect( Collectors.toList() );
    }

    private boolean matchesAnyPattern( final String pageName, final List< String > patterns ) {
        if ( patterns == null || patterns.isEmpty() ) {
            return true; // no filter → export all
        }
        for ( final String pattern : patterns ) {
            if ( matchesGlob( pageName, pattern ) ) {
                return true;
            }
        }
        return false;
    }

    /** Simple glob matching: {@code *} matches any sequence, everything else is literal. */
    static boolean matchesGlob( final String name, final String pattern ) {
        // Pattern.quote wraps in \Q...\E; we break out of quoting to insert .* for each *
        final String regex = "^" + Pattern.quote( pattern ).replace( "*", "\\E.*\\Q" ) + "$";
        return name.matches( regex );
    }

    private int exportAttachments( final Page page, final Path outputDir ) throws IOException, ProviderException {
        final List< Attachment > attachments = attachmentManager.listAttachments( page );
        if ( attachments.isEmpty() ) {
            return 0;
        }

        final Path attachDir = outputDir.resolve( ATTACHMENTS_DIR ).resolve( page.getName() );
        Files.createDirectories( attachDir );

        int count = 0;
        for ( final Attachment att : attachments ) {
            try ( final InputStream is = attachmentManager.getAttachmentStream( null, att ) ) {
                if ( is != null ) {
                    Files.copy( is, attachDir.resolve( att.getFileName() ) );
                    count++;
                }
            } catch ( final Exception e ) {
                LOG.warn( "Failed to export attachment {}/{}: {}", page.getName(), att.getFileName(), e.getMessage() );
            }
        }
        return count;
    }
}
