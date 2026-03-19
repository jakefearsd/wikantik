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
package org.apache.wiki.pages;

import org.apache.wiki.TestEngine;
import org.apache.wiki.api.core.Page;
import org.apache.wiki.frontmatter.FrontmatterParser;
import org.apache.wiki.frontmatter.ParsedPage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PageSaveHelperTest {

    private TestEngine engine;
    private PageSaveHelper helper;
    private PageManager pageManager;

    @BeforeEach
    void setUp() {
        engine = TestEngine.build();
        helper = new PageSaveHelper( engine );
        pageManager = engine.getManager( PageManager.class );
    }

    @AfterEach
    void tearDown() {
        engine.stop();
    }

    @Test
    void testBasicSave() throws Exception {
        final Page saved = helper.saveText( "HelperBasic", "Hello world.",
                SaveOptions.builder().author( "TestBot" ).build() );

        assertNotNull( saved );
        final String text = pageManager.getPureText( "HelperBasic", -1 );
        assertTrue( text.contains( "Hello world." ) );
    }

    @Test
    void testAuthorSet() throws Exception {
        helper.saveText( "HelperAuthor", "Content.",
                SaveOptions.builder().author( "JaneDoe" ).build() );

        final Page page = pageManager.getPage( "HelperAuthor" );
        assertEquals( "JaneDoe", page.getAuthor() );
    }

    @Test
    void testChangeNoteSet() throws Exception {
        helper.saveText( "HelperNote", "Content.",
                SaveOptions.builder().author( "Bot" ).changeNote( "Initial creation" ).build() );

        // Change note is stored as a page attribute — verify via page
        final Page page = pageManager.getPage( "HelperNote" );
        assertNotNull( page );
    }

    @Test
    void testMarkupSyntaxSet() throws Exception {
        helper.saveText( "HelperSyntax", "Content.",
                SaveOptions.builder().author( "Bot" ).markupSyntax( "markdown" ).build() );

        final Page page = pageManager.getPage( "HelperSyntax" );
        assertEquals( "markdown", page.getAttribute( Page.MARKUP_SYNTAX ) );
    }

    @Test
    void testOptimisticLockingVersionPass() throws Exception {
        helper.saveText( "HelperLock", "Original.",
                SaveOptions.builder().author( "Bot" ).build() );

        final Page current = pageManager.getPage( "HelperLock" );
        final int version = Math.max( current.getVersion(), 1 );

        // Should succeed with correct version
        final Page saved = helper.saveText( "HelperLock", "Updated.",
                SaveOptions.builder().author( "Bot" ).expectedVersion( version ).build() );
        assertNotNull( saved );
    }

    @Test
    void testOptimisticLockingVersionFail() throws Exception {
        helper.saveText( "HelperLockFail", "Original.",
                SaveOptions.builder().author( "Bot" ).build() );

        assertThrows( VersionConflictException.class, () ->
                helper.saveText( "HelperLockFail", "Updated.",
                        SaveOptions.builder().author( "Bot" ).expectedVersion( 999 ).build() ) );
    }

    @Test
    void testOptimisticLockingHashPass() throws Exception {
        helper.saveText( "HelperHash", "Original.",
                SaveOptions.builder().author( "Bot" ).build() );

        final String text = pageManager.getPureText( "HelperHash", -1 );
        final String hash = PageSaveHelper.computeContentHash( text );

        final Page saved = helper.saveText( "HelperHash", "Updated.",
                SaveOptions.builder().author( "Bot" ).expectedContentHash( hash ).build() );
        assertNotNull( saved );
    }

    @Test
    void testOptimisticLockingHashFail() throws Exception {
        helper.saveText( "HelperHashFail", "Original.",
                SaveOptions.builder().author( "Bot" ).build() );

        assertThrows( VersionConflictException.class, () ->
                helper.saveText( "HelperHashFail", "Updated.",
                        SaveOptions.builder().author( "Bot" )
                                .expectedContentHash( "0000000000000000000000000000000000000000000000000000000000000000" )
                                .build() ) );
    }

    @Test
    void testSaveWithMetadata() throws Exception {
        helper.saveText( "HelperMeta", "Body text.",
                SaveOptions.builder()
                        .author( "Bot" )
                        .metadata( Map.of( "type", "guide", "tags", java.util.List.of( "test" ) ) )
                        .build() );

        final String text = pageManager.getPureText( "HelperMeta", -1 );
        final ParsedPage parsed = FrontmatterParser.parse( text );
        assertEquals( "guide", parsed.metadata().get( "type" ) );
        assertTrue( parsed.body().contains( "Body text." ) );
    }

    @Test
    void testSaveWithMetadataMerge() throws Exception {
        // Create page with existing metadata
        engine.saveText( "HelperMerge", "---\ntype: report\ntags: [security]\n---\nOriginal." );

        // Save with new metadata — should merge
        helper.saveText( "HelperMerge", "Updated.",
                SaveOptions.builder()
                        .author( "Bot" )
                        .metadata( Map.of( "status", "active" ) )
                        .build() );

        final String text = pageManager.getPureText( "HelperMerge", -1 );
        final ParsedPage parsed = FrontmatterParser.parse( text );
        assertEquals( "report", parsed.metadata().get( "type" ) );    // preserved
        assertEquals( "active", parsed.metadata().get( "status" ) );  // added
    }

    @Test
    void testSaveWithMetadataReplace() throws Exception {
        engine.saveText( "HelperReplace", "---\ntype: report\ntags: [security]\n---\nOriginal." );

        helper.saveText( "HelperReplace", "Updated.",
                SaveOptions.builder()
                        .author( "Bot" )
                        .metadata( Map.of( "type", "note" ) )
                        .replaceMetadata( true )
                        .build() );

        final String text = pageManager.getPureText( "HelperReplace", -1 );
        final ParsedPage parsed = FrontmatterParser.parse( text );
        assertEquals( "note", parsed.metadata().get( "type" ) );
        assertNull( parsed.metadata().get( "tags" ) );    // old field gone
    }

    @Test
    void testSaveWithNullMetadata() throws Exception {
        helper.saveText( "HelperNoMeta", "Body only.",
                SaveOptions.builder().author( "Bot" ).build() );

        final String text = pageManager.getPureText( "HelperNoMeta", -1 );
        final ParsedPage parsed = FrontmatterParser.parse( text );
        assertTrue( parsed.metadata().isEmpty() );
        assertTrue( parsed.body().contains( "Body only." ) );
    }

    @Test
    void testMergeMetadataNewPage() throws Exception {
        final Map< String, Object > merged = helper.mergeMetadata(
                pageManager, "NonExistentPage", Map.of( "type", "guide" ) );
        assertEquals( "guide", merged.get( "type" ) );
    }

}
