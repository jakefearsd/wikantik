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

import com.google.gson.Gson;
import io.modelcontextprotocol.spec.McpSchema;
import com.wikantik.test.StubPageManager;
import com.wikantik.test.StubPageSaveHelper;
import com.wikantik.test.StubSystemPageRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ExportImportRoundTripTest {

    private StubPageManager pm;
    private StubPageSaveHelper saveHelper;
    private StubSystemPageRegistry spr;
    private ExportContentTool exportTool;
    private PreviewImportTool previewTool;
    private ImportContentTool importTool;
    private final Gson gson = new Gson();

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        pm = new StubPageManager();
        saveHelper = new StubPageSaveHelper( pm );
        spr = new StubSystemPageRegistry();
        exportTool = new ExportContentTool( pm, null, spr );
        previewTool = new PreviewImportTool( pm );
        importTool = new ImportContentTool( saveHelper, pm );
    }

    // -------------------------------------------------------------------------
    // ExportManifest
    // -------------------------------------------------------------------------

    @Test
    void testManifestRoundTrip() throws IOException {
        final ExportManifest manifest = new ExportManifest( "wikantik",
                java.time.Instant.parse( "2026-04-08T10:00:00Z" ),
                Map.of( "PageOne", 3, "PageTwo", 1 ) );

        manifest.writeTo( tempDir );

        final ExportManifest loaded = ExportManifest.readFrom( tempDir );
        assertNotNull( loaded );
        assertEquals( "wikantik", loaded.getWikiName() );
        assertEquals( 2, loaded.getPageVersions().size() );
        assertEquals( 3, loaded.getPageVersions().get( "PageOne" ) );
    }

    @Test
    void testManifestReadFromMissingReturnsNull() throws IOException {
        assertNull( ExportManifest.readFrom( tempDir ) );
    }

    // -------------------------------------------------------------------------
    // ExportContentTool
    // -------------------------------------------------------------------------

    @Test
    @SuppressWarnings( "unchecked" )
    void testExportAllPages() {
        pm.savePage( "Alpha", "---\ntype: note\n---\nAlpha content." );
        pm.savePage( "Beta", "Beta content." );

        final Path exportDir = tempDir.resolve( "export1" );
        final McpSchema.CallToolResult result = exportTool.execute( Map.of( "directory", exportDir.toString() ) );
        final Map< String, Object > data = parseResult( result );

        assertEquals( true, data.get( "success" ) );
        assertEquals( 2.0, data.get( "pagesExported" ) );
        assertTrue( Files.exists( exportDir.resolve( "Alpha.md" ) ) );
        assertTrue( Files.exists( exportDir.resolve( "Beta.md" ) ) );
        assertTrue( Files.exists( exportDir.resolve( "_export.json" ) ) );
    }

    @Test
    @SuppressWarnings( "unchecked" )
    void testExportWithGlobPattern() {
        pm.savePage( "AIHistory", "AI history content." );
        pm.savePage( "AIEthics", "AI ethics content." );
        pm.savePage( "Cooking", "Cooking content." );

        final Path exportDir = tempDir.resolve( "export2" );
        final Map< String, Object > args = new HashMap<>();
        args.put( "pages", List.of( "AI*" ) );
        args.put( "directory", exportDir.toString() );

        final McpSchema.CallToolResult result = exportTool.execute( args );
        final Map< String, Object > data = parseResult( result );

        assertEquals( true, data.get( "success" ) );
        assertEquals( 2.0, data.get( "pagesExported" ) );
        assertTrue( Files.exists( exportDir.resolve( "AIHistory.md" ) ) );
        assertTrue( Files.exists( exportDir.resolve( "AIEthics.md" ) ) );
        assertFalse( Files.exists( exportDir.resolve( "Cooking.md" ) ) );
    }

    @Test
    void testExportExcludesSystemPages() {
        pm.savePage( "UserPage", "User content." );
        pm.savePage( "SystemTemplate", "System content." );
        spr.addSystemPage( "SystemTemplate" );

        final Path exportDir = tempDir.resolve( "export3" );
        final McpSchema.CallToolResult result = exportTool.execute( Map.of( "directory", exportDir.toString() ) );
        final Map< String, Object > data = parseResult( result );

        assertEquals( 1.0, data.get( "pagesExported" ) );
        assertTrue( Files.exists( exportDir.resolve( "UserPage.md" ) ) );
        assertFalse( Files.exists( exportDir.resolve( "SystemTemplate.md" ) ) );
    }

    @Test
    void testExportNoMatchReturnsError() {
        pm.savePage( "OnlyPage", "Content." );

        final Map< String, Object > args = new HashMap<>();
        args.put( "pages", List.of( "NonExistent*" ) );
        args.put( "directory", tempDir.resolve( "export4" ).toString() );

        final McpSchema.CallToolResult result = exportTool.execute( args );
        assertTrue( result.isError() );
    }

    @Test
    void testExportPreservesContentExactly() throws IOException {
        final String content = "---\nsummary: Test page\ntags:\n- ai\n- ml\n---\n# Heading\n\nBody text here.";
        pm.savePage( "ExactPage", content );

        final Path exportDir = tempDir.resolve( "export5" );
        exportTool.execute( Map.of( "directory", exportDir.toString() ) );

        final String exported = Files.readString( exportDir.resolve( "ExactPage.md" ), StandardCharsets.UTF_8 );
        assertEquals( content, exported );
    }

    // -------------------------------------------------------------------------
    // PreviewImportTool
    // -------------------------------------------------------------------------

    @Test
    @SuppressWarnings( "unchecked" )
    void testPreviewDetectsNewPages() throws IOException {
        // No pages in wiki, but a file exists in directory
        Files.writeString( tempDir.resolve( "NewPage.md" ), "# New\nContent.", StandardCharsets.UTF_8 );

        final McpSchema.CallToolResult result = previewTool.execute( Map.of( "directory", tempDir.toString() ) );
        final Map< String, Object > data = parseResult( result );

        final List< Map< String, Object > > added = ( List< Map< String, Object > > ) data.get( "added" );
        assertEquals( 1, added.size() );
        assertEquals( "NewPage", added.get( 0 ).get( "pageName" ) );
    }

    @Test
    @SuppressWarnings( "unchecked" )
    void testPreviewDetectsModifiedPages() throws IOException {
        pm.savePage( "Existing", "Original content." );

        Files.writeString( tempDir.resolve( "Existing.md" ), "Modified content.", StandardCharsets.UTF_8 );

        final McpSchema.CallToolResult result = previewTool.execute( Map.of( "directory", tempDir.toString() ) );
        final Map< String, Object > data = parseResult( result );

        final List< Map< String, Object > > modified = ( List< Map< String, Object > > ) data.get( "modified" );
        assertEquals( 1, modified.size() );
        assertEquals( "Existing", modified.get( 0 ).get( "pageName" ) );
    }

    @Test
    @SuppressWarnings( "unchecked" )
    void testPreviewDetectsUnchangedPages() throws IOException {
        pm.savePage( "Same", "Same content." );

        Files.writeString( tempDir.resolve( "Same.md" ), "Same content.", StandardCharsets.UTF_8 );

        final McpSchema.CallToolResult result = previewTool.execute( Map.of( "directory", tempDir.toString() ) );
        final Map< String, Object > data = parseResult( result );

        final List< String > unchanged = ( List< String > ) data.get( "unchanged" );
        assertTrue( unchanged.contains( "Same" ) );
    }

    @Test
    @SuppressWarnings( "unchecked" )
    void testPreviewDetectsVersionConflicts() throws IOException {
        pm.savePage( "Conflicted", "Content v1." );

        // Write a manifest claiming version 1, but bump page to version 2
        final ExportManifest manifest = new ExportManifest( "wikantik",
                java.time.Instant.now(), Map.of( "Conflicted", 1 ) );
        manifest.writeTo( tempDir );

        // Simulate a wiki-side edit by changing the version
        pm.getPage( "Conflicted" ).setVersion( 2 );

        Files.writeString( tempDir.resolve( "Conflicted.md" ), "Modified content.", StandardCharsets.UTF_8 );

        final McpSchema.CallToolResult result = previewTool.execute( Map.of( "directory", tempDir.toString() ) );
        final Map< String, Object > data = parseResult( result );

        final List< Map< String, Object > > conflicts = ( List< Map< String, Object > > ) data.get( "conflicts" );
        assertEquals( 1, conflicts.size() );
        assertEquals( "Conflicted", conflicts.get( 0 ).get( "pageName" ) );
    }

    @Test
    @SuppressWarnings( "unchecked" )
    void testPreviewDetectsDeletions() throws IOException {
        pm.savePage( "ToDelete", "Will be deleted." );
        pm.savePage( "ToKeep", "Will be kept." );

        // Manifest says both existed at export time
        final ExportManifest manifest = new ExportManifest( "wikantik",
                java.time.Instant.now(), Map.of( "ToDelete", 1, "ToKeep", 1 ) );
        manifest.writeTo( tempDir );

        // Only ToKeep.md is in the directory
        Files.writeString( tempDir.resolve( "ToKeep.md" ), "Will be kept.", StandardCharsets.UTF_8 );

        final Map< String, Object > args = new HashMap<>();
        args.put( "directory", tempDir.toString() );
        args.put( "deleteMissing", true );

        final McpSchema.CallToolResult result = previewTool.execute( args );
        final Map< String, Object > data = parseResult( result );

        final List< String > deleted = ( List< String > ) data.get( "deleted" );
        assertTrue( deleted.contains( "ToDelete" ) );
        assertFalse( deleted.contains( "ToKeep" ) );
    }

    @Test
    void testPreviewRequiresDirectory() {
        final McpSchema.CallToolResult result = previewTool.execute( Map.of() );
        assertTrue( result.isError() );
    }

    // -------------------------------------------------------------------------
    // ImportContentTool
    // -------------------------------------------------------------------------

    @Test
    @SuppressWarnings( "unchecked" )
    void testImportCreatesNewPages() throws IOException {
        Files.writeString( tempDir.resolve( "BrandNew.md" ),
                "---\ntype: guide\n---\n# Brand New\nContent here.", StandardCharsets.UTF_8 );

        final Map< String, Object > args = new HashMap<>();
        args.put( "directory", tempDir.toString() );
        args.put( "author", "test-user" );
        args.put( "changeNote", "Imported via test" );

        final McpSchema.CallToolResult result = importTool.execute( args );
        final Map< String, Object > data = parseResult( result );

        final List< Map< String, Object > > results = ( List< Map< String, Object > > ) data.get( "results" );
        assertEquals( 1, results.size() );
        assertEquals( "created", results.get( 0 ).get( "action" ) );
        assertEquals( true, results.get( 0 ).get( "success" ) );

        // Verify the page was actually saved
        assertNotNull( pm.getPage( "BrandNew" ) );
        final String savedText = pm.getPureText( "BrandNew", -1 );
        assertTrue( savedText.contains( "Brand New" ) );
        assertTrue( savedText.contains( "type: guide" ) );
    }

    @Test
    @SuppressWarnings( "unchecked" )
    void testImportUpdatesExistingPages() throws IOException {
        pm.savePage( "Existing", "Old content." );

        Files.writeString( tempDir.resolve( "Existing.md" ), "Updated content.", StandardCharsets.UTF_8 );

        final McpSchema.CallToolResult result = importTool.execute(
                Map.of( "directory", tempDir.toString() ) );
        final Map< String, Object > data = parseResult( result );

        final List< Map< String, Object > > results = ( List< Map< String, Object > > ) data.get( "results" );
        assertEquals( 1, results.size() );
        assertEquals( "updated", results.get( 0 ).get( "action" ) );

        assertEquals( "Updated content.", pm.getPureText( "Existing", -1 ) );
    }

    @Test
    @SuppressWarnings( "unchecked" )
    void testImportSkipsUnchangedPages() throws IOException {
        pm.savePage( "Unchanged", "Same content." );

        Files.writeString( tempDir.resolve( "Unchanged.md" ), "Same content.", StandardCharsets.UTF_8 );

        final McpSchema.CallToolResult result = importTool.execute(
                Map.of( "directory", tempDir.toString() ) );
        final Map< String, Object > data = parseResult( result );

        final List< Map< String, Object > > results = ( List< Map< String, Object > > ) data.get( "results" );
        assertEquals( 1, results.size() );
        assertEquals( "unchanged", results.get( 0 ).get( "action" ) );
    }

    @Test
    @SuppressWarnings( "unchecked" )
    void testImportDetectsVersionConflicts() throws IOException {
        pm.savePage( "Conflicted", "Content v1." );
        pm.getPage( "Conflicted" ).setVersion( 2 );

        // Manifest says version 1 at export time
        final ExportManifest manifest = new ExportManifest( "wikantik",
                java.time.Instant.now(), Map.of( "Conflicted", 1 ) );
        manifest.writeTo( tempDir );

        Files.writeString( tempDir.resolve( "Conflicted.md" ), "Modified content.", StandardCharsets.UTF_8 );

        final McpSchema.CallToolResult result = importTool.execute(
                Map.of( "directory", tempDir.toString() ) );
        final Map< String, Object > data = parseResult( result );

        final List< Map< String, Object > > results = ( List< Map< String, Object > > ) data.get( "results" );
        assertEquals( 1, results.size() );
        assertEquals( "conflict", results.get( 0 ).get( "action" ) );
        assertEquals( false, results.get( 0 ).get( "success" ) );
    }

    @Test
    @SuppressWarnings( "unchecked" )
    void testImportSkipConflictsFlag() throws IOException {
        pm.savePage( "Conflicted", "Content v1." );
        pm.getPage( "Conflicted" ).setVersion( 2 );

        final ExportManifest manifest = new ExportManifest( "wikantik",
                java.time.Instant.now(), Map.of( "Conflicted", 1 ) );
        manifest.writeTo( tempDir );

        Files.writeString( tempDir.resolve( "Conflicted.md" ), "Modified content.", StandardCharsets.UTF_8 );

        final Map< String, Object > args = new HashMap<>();
        args.put( "directory", tempDir.toString() );
        args.put( "skipConflicts", true );

        final McpSchema.CallToolResult result = importTool.execute( args );
        final Map< String, Object > data = parseResult( result );

        final List< Map< String, Object > > results = ( List< Map< String, Object > > ) data.get( "results" );
        assertEquals( 1, results.size() );
        assertEquals( "skipped", results.get( 0 ).get( "action" ) );
    }

    @Test
    @SuppressWarnings( "unchecked" )
    void testImportDeletesMissingPages() throws IOException {
        pm.savePage( "ToDelete", "Will be deleted." );
        pm.savePage( "ToKeep", "Will be kept." );

        final ExportManifest manifest = new ExportManifest( "wikantik",
                java.time.Instant.now(), Map.of( "ToDelete", 1, "ToKeep", 1 ) );
        manifest.writeTo( tempDir );

        // Only ToKeep in the directory
        Files.writeString( tempDir.resolve( "ToKeep.md" ), "Will be kept.", StandardCharsets.UTF_8 );

        final Map< String, Object > args = new HashMap<>();
        args.put( "directory", tempDir.toString() );
        args.put( "deleteMissing", true );

        final McpSchema.CallToolResult result = importTool.execute( args );
        final Map< String, Object > data = parseResult( result );

        final Map< String, Object > summary = ( Map< String, Object > ) data.get( "summary" );
        assertEquals( 1.0, summary.get( "deleted" ) );

        assertNull( pm.getPage( "ToDelete" ) );
        assertNotNull( pm.getPage( "ToKeep" ) );
    }

    @Test
    void testImportRequiresDirectory() {
        final McpSchema.CallToolResult result = importTool.execute( Map.of() );
        assertTrue( result.isError() );
    }

    @Test
    void testImportEmptyDirectoryReturnsError() throws IOException {
        final Path emptyDir = tempDir.resolve( "empty" );
        Files.createDirectories( emptyDir );

        final McpSchema.CallToolResult result = importTool.execute(
                Map.of( "directory", emptyDir.toString() ) );
        assertTrue( result.isError() );
    }

    // -------------------------------------------------------------------------
    // Full round-trip: export → no edits → import = no changes
    // -------------------------------------------------------------------------

    @Test
    @SuppressWarnings( "unchecked" )
    void testFullRoundTripNoChanges() throws IOException {
        pm.savePage( "PageA", "---\ntype: note\n---\nContent A." );
        pm.savePage( "PageB", "Content B." );

        final Path exportDir = tempDir.resolve( "roundtrip" );

        // Export
        exportTool.execute( Map.of( "directory", exportDir.toString() ) );

        // Preview — should show all unchanged
        final McpSchema.CallToolResult preview = previewTool.execute(
                Map.of( "directory", exportDir.toString() ) );
        final Map< String, Object > previewData = parseResult( preview );
        final List< String > unchanged = ( List< String > ) previewData.get( "unchanged" );
        assertEquals( 2, unchanged.size() );
        assertTrue( ( ( List< ? > ) previewData.get( "added" ) ).isEmpty() );
        assertTrue( ( ( List< ? > ) previewData.get( "modified" ) ).isEmpty() );

        // Import — should skip all (unchanged)
        final McpSchema.CallToolResult importResult = importTool.execute(
                Map.of( "directory", exportDir.toString() ) );
        final Map< String, Object > importData = parseResult( importResult );
        final Map< String, Object > summary = ( Map< String, Object > ) importData.get( "summary" );
        assertEquals( 0.0, summary.get( "created" ) );
        assertEquals( 0.0, summary.get( "updated" ) );
        assertEquals( 2.0, summary.get( "skipped" ) );
    }

    // -------------------------------------------------------------------------
    // Full round-trip: export → edit → import = changes applied
    // -------------------------------------------------------------------------

    @Test
    @SuppressWarnings( "unchecked" )
    void testFullRoundTripWithEdits() throws IOException {
        pm.savePage( "EditMe", "---\nstatus: draft\n---\nOriginal." );

        final Path exportDir = tempDir.resolve( "roundtrip-edit" );

        // Export
        exportTool.execute( Map.of( "directory", exportDir.toString() ) );

        // Edit the exported file
        Files.writeString( exportDir.resolve( "EditMe.md" ),
                "---\nstatus: active\n---\nEdited content.", StandardCharsets.UTF_8 );

        // Add a new file
        Files.writeString( exportDir.resolve( "NewlyCreated.md" ),
                "---\ntype: article\n---\n# New Article\nBody.", StandardCharsets.UTF_8 );

        // Preview
        final McpSchema.CallToolResult preview = previewTool.execute(
                Map.of( "directory", exportDir.toString() ) );
        final Map< String, Object > previewData = parseResult( preview );
        assertEquals( 1, ( ( List< ? > ) previewData.get( "modified" ) ).size() );
        assertEquals( 1, ( ( List< ? > ) previewData.get( "added" ) ).size() );

        // Import
        final McpSchema.CallToolResult importResult = importTool.execute(
                Map.of( "directory", exportDir.toString(), "author", "editor" ) );
        final Map< String, Object > importData = parseResult( importResult );
        final Map< String, Object > summary = ( Map< String, Object > ) importData.get( "summary" );
        assertEquals( 1.0, summary.get( "created" ) );
        assertEquals( 1.0, summary.get( "updated" ) );

        // Verify saved content
        assertTrue( pm.getPureText( "EditMe", -1 ).contains( "status: active" ) );
        assertTrue( pm.getPureText( "EditMe", -1 ).contains( "Edited content." ) );
        assertNotNull( pm.getPage( "NewlyCreated" ) );
    }

    // -------------------------------------------------------------------------
    // Tool definitions
    // -------------------------------------------------------------------------

    @Test
    void testExportToolDefinition() {
        final McpSchema.Tool def = exportTool.definition();
        assertEquals( "export_content", def.name() );
        assertNotNull( def.description() );
        assertTrue( def.annotations().readOnlyHint() );
    }

    @Test
    void testPreviewToolDefinition() {
        final McpSchema.Tool def = previewTool.definition();
        assertEquals( "preview_import", def.name() );
        assertNotNull( def.description() );
        assertTrue( def.annotations().readOnlyHint() );
    }

    @Test
    void testImportToolDefinition() {
        final McpSchema.Tool def = importTool.definition();
        assertEquals( "import_content", def.name() );
        assertNotNull( def.description() );
        assertFalse( def.annotations().readOnlyHint() );
    }

    // -------------------------------------------------------------------------
    // Glob matching
    // -------------------------------------------------------------------------

    @Test
    void testGlobMatching() {
        assertTrue( ExportContentTool.matchesGlob( "AIHistory", "AI*" ) );
        assertTrue( ExportContentTool.matchesGlob( "AIEthics", "AI*" ) );
        assertFalse( ExportContentTool.matchesGlob( "Cooking", "AI*" ) );
        assertTrue( ExportContentTool.matchesGlob( "ExactMatch", "ExactMatch" ) );
        assertFalse( ExportContentTool.matchesGlob( "ExactMatch", "Exact" ) );
        assertTrue( ExportContentTool.matchesGlob( "FooBarBaz", "*Bar*" ) );
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    @SuppressWarnings( "unchecked" )
    private Map< String, Object > parseResult( final McpSchema.CallToolResult result ) {
        final String json = ( ( McpSchema.TextContent ) result.content().get( 0 ) ).text();
        return gson.fromJson( json, Map.class );
    }
}
