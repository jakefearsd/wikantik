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
import com.wikantik.api.managers.PageManager;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Stream;

/**
 * MCP tool that previews what an import would change without applying anything.
 * Compares the files in a working directory against the current wiki state and
 * reports pages that would be added, modified, deleted, or have version conflicts.
 */
public class PreviewImportTool implements McpTool {

    private static final Logger LOG = LogManager.getLogger( PreviewImportTool.class );
    public static final String TOOL_NAME = "preview_import";

    private final PageManager pageManager;

    public PreviewImportTool( final PageManager pageManager ) {
        this.pageManager = pageManager;
    }

    @Override
    public String name() {
        return TOOL_NAME;
    }

    @Override
    public McpSchema.Tool definition() {
        final Map< String, Object > properties = new LinkedHashMap<>();
        properties.put( "directory", Map.of( "type", "string",
                "description", "Path to the working directory containing .md files (from export_content)" ) );
        properties.put( "deleteMissing", Map.of( "type", "boolean",
                "description", "If true, pages in the manifest but absent from the directory would be deleted (default false)" ) );

        return McpSchema.Tool.builder()
                .name( TOOL_NAME )
                .description( "Preview what import_content would change without applying anything. " +
                        "Compares .md files in a working directory against the current wiki state. " +
                        "Returns {added, modified, unchanged, deleted, conflicts} with details for each page." )
                .inputSchema( new McpSchema.JsonSchema( "object", properties, List.of( "directory" ), null, null, null ) )
                .annotations( new McpSchema.ToolAnnotations( null, true, false, true, null, null ) )
                .build();
    }

    @Override
    public McpSchema.CallToolResult execute( final Map< String, Object > arguments ) {
        final String directoryArg = McpToolUtils.getString( arguments, "directory" );
        final boolean deleteMissing = McpToolUtils.getBoolean( arguments, "deleteMissing" );

        if ( directoryArg == null || directoryArg.isBlank() ) {
            return McpToolUtils.errorResult( McpToolUtils.SHARED_GSON, "directory is required" );
        }

        final Path dir = Path.of( directoryArg );
        if ( !Files.isDirectory( dir ) ) {
            return McpToolUtils.errorResult( McpToolUtils.SHARED_GSON,
                    "Directory does not exist: " + directoryArg );
        }

        try {
            final ExportManifest manifest = ExportManifest.readFrom( dir );
            final Map< String, Integer > manifestVersions = manifest != null
                    ? manifest.getPageVersions()
                    : Map.of();

            // Find all .md files in the directory (non-recursive, skip _attachments etc.)
            final Map< String, Path > mdFiles = collectMarkdownFiles( dir );

            final List< Map< String, Object > > added = new ArrayList<>();
            final List< Map< String, Object > > modified = new ArrayList<>();
            final List< String > unchanged = new ArrayList<>();
            final List< Map< String, Object > > conflicts = new ArrayList<>();
            final List< String > deleted = new ArrayList<>();

            // Check each .md file against wiki state
            for ( final Map.Entry< String, Path > entry : mdFiles.entrySet() ) {
                final String pageName = entry.getKey();
                final String fileContent = Files.readString( entry.getValue(), StandardCharsets.UTF_8 );
                final Page currentPage = pageManager.getPage( pageName );

                if ( currentPage == null ) {
                    // New page
                    added.add( Map.of( "pageName", pageName ) );
                } else {
                    final String currentText = pageManager.getPureText( pageName, -1 );

                    // Check for version conflict if manifest exists
                    if ( manifestVersions.containsKey( pageName ) ) {
                        final int exportedVersion = manifestVersions.get( pageName );
                        final int currentVersion = McpToolUtils.normalizeVersion( currentPage.getVersion() );
                        if ( currentVersion != exportedVersion ) {
                            conflicts.add( Map.of(
                                    "pageName", pageName,
                                    "exportedVersion", exportedVersion,
                                    "currentVersion", currentVersion,
                                    "reason", "Page was modified in wiki after export" ) );
                            continue;
                        }
                    }

                    if ( !fileContent.equals( currentText ) ) {
                        modified.add( Map.of( "pageName", pageName ) );
                    } else {
                        unchanged.add( pageName );
                    }
                }
            }

            // Check for deletions: pages in manifest but not in directory
            if ( deleteMissing && manifest != null ) {
                for ( final String pageName : manifestVersions.keySet() ) {
                    if ( !mdFiles.containsKey( pageName ) ) {
                        deleted.add( pageName );
                    }
                }
            }

            final Map< String, Object > result = new LinkedHashMap<>();
            result.put( "added", added );
            result.put( "modified", modified );
            result.put( "unchanged", unchanged );
            result.put( "conflicts", conflicts );
            result.put( "deleted", deleted );

            final Map< String, Object > summary = new LinkedHashMap<>();
            summary.put( "totalFiles", mdFiles.size() );
            summary.put( "toAdd", added.size() );
            summary.put( "toModify", modified.size() );
            summary.put( "unchanged", unchanged.size() );
            summary.put( "conflicts", conflicts.size() );
            summary.put( "toDelete", deleted.size() );
            result.put( "summary", summary );

            return McpToolUtils.jsonResult( McpToolUtils.SHARED_GSON, result );

        } catch ( final IOException e ) {
            LOG.error( "Failed to preview import from {}: {}", directoryArg, e.getMessage(), e );
            return McpToolUtils.errorResult( McpToolUtils.SHARED_GSON,
                    "Preview failed: " + e.getMessage() );
        }
    }

    /**
     * Collects {@code .md} files in the given directory (non-recursive).
     * Returns a map of page name → file path, stripping the {@code .md} extension.
     */
    static Map< String, Path > collectMarkdownFiles( final Path dir ) throws IOException {
        final Map< String, Path > result = new TreeMap<>();
        try ( final Stream< Path > files = Files.list( dir ) ) {
            files.filter( p -> !Files.isDirectory( p ) )
                 .filter( p -> p.getFileName().toString().endsWith( ".md" ) )
                 .filter( p -> !p.getFileName().toString().startsWith( "_" ) )
                 .forEach( p -> {
                     final String fileName = p.getFileName().toString();
                     final String pageName = fileName.substring( 0, fileName.length() - 3 );
                     result.put( pageName, p );
                 } );
        }
        return result;
    }
}
