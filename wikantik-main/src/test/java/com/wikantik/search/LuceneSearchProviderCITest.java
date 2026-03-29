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
package com.wikantik.search;

import com.wikantik.MockEngineBuilder;
import com.wikantik.api.core.Acl;
import com.wikantik.api.core.Attachment;
import com.wikantik.api.core.Context;
import com.wikantik.api.core.Engine;
import com.wikantik.api.core.Page;
import com.wikantik.api.core.Session;
import com.wikantik.api.exceptions.ProviderException;
import com.wikantik.api.providers.PageProvider;
import com.wikantik.api.search.SearchResult;
import com.wikantik.attachment.AttachmentManager;
import com.wikantik.auth.AuthorizationManager;
import com.wikantik.auth.acl.AclManager;
import com.wikantik.auth.permissions.PagePermission;
import com.wikantik.pages.PageManager;
import org.apache.lucene.analysis.classic.ClassicAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.NIOFSDirectory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.Permission;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Constructor-injection tests for {@link LuceneSearchProvider}.
 * Exercises the logical core of the search engine (indexing, querying,
 * scoring, attachment content extraction) using mocked collaborators,
 * without starting a full wiki engine.
 */
@ExtendWith( MockitoExtension.class )
@MockitoSettings( strictness = Strictness.LENIENT )
class LuceneSearchProviderCITest {

    @TempDir
    File tempDir;

    @Mock private PageManager pageManager;
    @Mock private AttachmentManager attachmentManager;
    @Mock private AuthorizationManager authorizationManager;
    @Mock private AclManager aclManager;
    @Mock private Context wikiContext;
    @Mock private Session session;

    private LuceneSearchProvider provider;
    private Engine engine;
    private File luceneDir;

    @BeforeEach
    void setUp() throws Exception {
        provider = new LuceneSearchProvider( pageManager, attachmentManager, authorizationManager, aclManager );

        luceneDir = new File( tempDir, "lucene" );
        luceneDir.mkdirs();

        engine = MockEngineBuilder.engine()
                .with( PageManager.class, pageManager )
                .with( AttachmentManager.class, attachmentManager )
                .with( AuthorizationManager.class, authorizationManager )
                .with( AclManager.class, aclManager )
                .build();
        when( engine.getApplicationName() ).thenReturn( "test" );
        when( engine.getWorkDir() ).thenReturn( tempDir.getAbsolutePath() );

        when( wikiContext.getWikiSession() ).thenReturn( session );
        when( wikiContext.getEngine() ).thenReturn( engine );

        // Wire the internal luceneDirectory field and analyzer via reflection
        setField( provider, "luceneDirectory", luceneDir.getAbsolutePath() );
        setField( provider, "analyzer", new ClassicAnalyzer() );
        setField( provider, "searchExecutor", Executors.newCachedThreadPool() );
        setField( provider, "engine", engine );
    }

    // -----------------------------------------------------------------------
    // luceneIndexPage
    // -----------------------------------------------------------------------

    @Test
    void testLuceneIndexPageWithNullTextReturnsEmptyDocument() throws IOException {
        final Page page = mockPage( "NullPage" );
        try( final Directory dir = new NIOFSDirectory( luceneDir.toPath() );
             final IndexWriter writer = provider.getIndexWriter( dir ) ) {
            final Document doc = provider.luceneIndexPage( page, null, writer );
            assertNotNull( doc, "Document should not be null" );
            assertNull( doc.get( LuceneSearchProvider.LUCENE_ID ), "Null text should produce empty document" );
        }
    }

    @Test
    void testLuceneIndexPageBasicFields() throws Exception {
        final Page page = mockPage( "BasicPage" );
        when( page.getAuthor() ).thenReturn( "TestAuthor" );
        when( attachmentManager.listAttachments( page ) ).thenReturn( Collections.emptyList() );

        try( final Directory dir = new NIOFSDirectory( luceneDir.toPath() );
             final IndexWriter writer = provider.getIndexWriter( dir ) ) {
            final Document doc = provider.luceneIndexPage( page, "some searchable content", writer );
            assertEquals( "BasicPage", doc.get( LuceneSearchProvider.LUCENE_ID ) );
            assertEquals( "TestAuthor", doc.get( LuceneSearchProvider.LUCENE_AUTHOR ) );
            assertTrue( doc.get( LuceneSearchProvider.LUCENE_PAGE_CONTENTS ).contains( "searchable" ) );
        }
    }

    @Test
    void testLuceneIndexPageNoAuthor() throws Exception {
        final Page page = mockPage( "NoAuthorPage" );
        when( page.getAuthor() ).thenReturn( null );
        when( attachmentManager.listAttachments( page ) ).thenReturn( Collections.emptyList() );

        try( final Directory dir = new NIOFSDirectory( luceneDir.toPath() );
             final IndexWriter writer = provider.getIndexWriter( dir ) ) {
            final Document doc = provider.luceneIndexPage( page, "body text", writer );
            assertNull( doc.get( LuceneSearchProvider.LUCENE_AUTHOR ), "Author field should be absent when author is null" );
        }
    }

    @Test
    void testLuceneIndexPageWithKeywords() throws Exception {
        final Page page = mockPage( "KeywordPage" );
        when( page.getAttribute( "keywords" ) ).thenReturn( "wiki search engine" );
        when( attachmentManager.listAttachments( page ) ).thenReturn( Collections.emptyList() );

        try( final Directory dir = new NIOFSDirectory( luceneDir.toPath() );
             final IndexWriter writer = provider.getIndexWriter( dir ) ) {
            final Document doc = provider.luceneIndexPage( page, "body", writer );
            assertEquals( "wiki search engine", doc.get( LuceneSearchProvider.LUCENE_PAGE_KEYWORDS ) );
        }
    }

    @Test
    void testLuceneIndexPageWithFrontmatterMetadata() throws Exception {
        final Page page = mockPage( "FrontmatterPage" );
        when( attachmentManager.listAttachments( page ) ).thenReturn( Collections.emptyList() );

        final String text = """
                ---
                tags:
                  - java
                  - wiki
                cluster: technology
                summary: A page about wiki technology
                ---
                # Hello World
                Body content here.""";

        try( final Directory dir = new NIOFSDirectory( luceneDir.toPath() );
             final IndexWriter writer = provider.getIndexWriter( dir ) ) {
            final Document doc = provider.luceneIndexPage( page, text, writer );
            assertEquals( "java wiki", doc.get( LuceneSearchProvider.LUCENE_PAGE_TAGS ) );
            assertEquals( "technology", doc.get( LuceneSearchProvider.LUCENE_PAGE_CLUSTER ) );
            assertEquals( "A page about wiki technology", doc.get( LuceneSearchProvider.LUCENE_PAGE_SUMMARY ) );
        }
    }

    @Test
    void testLuceneIndexPageWithAttachments() throws Exception {
        final Page page = mockPage( "PageWithAttachments" );
        final Attachment att1 = mock( Attachment.class );
        when( att1.getName() ).thenReturn( "PageWithAttachments/file1.txt" );
        final Attachment att2 = mock( Attachment.class );
        when( att2.getName() ).thenReturn( "PageWithAttachments/file2.pdf" );
        when( attachmentManager.listAttachments( page ) ).thenReturn( List.of( att1, att2 ) );

        try( final Directory dir = new NIOFSDirectory( luceneDir.toPath() );
             final IndexWriter writer = provider.getIndexWriter( dir ) ) {
            final Document doc = provider.luceneIndexPage( page, "body", writer );
            final String attachmentField = doc.get( LuceneSearchProvider.LUCENE_ATTACHMENTS );
            assertTrue( attachmentField.contains( "file1.txt" ) );
            assertTrue( attachmentField.contains( "file2.pdf" ) );
        }
    }

    @Test
    void testLuceneIndexPageWithAttachmentListError() throws Exception {
        final Page page = mockPage( "AttErrorPage" );
        when( attachmentManager.listAttachments( page ) ).thenThrow( new ProviderException( "test error" ) );

        try( final Directory dir = new NIOFSDirectory( luceneDir.toPath() );
             final IndexWriter writer = provider.getIndexWriter( dir ) ) {
            // Should not throw; catches ProviderException internally
            final Document doc = provider.luceneIndexPage( page, "body", writer );
            assertNotNull( doc );
            assertEquals( "AttErrorPage", doc.get( LuceneSearchProvider.LUCENE_ID ) );
        }
    }

    @Test
    void testLuceneIndexPageUnderscoresReplacedWithSpaces() throws Exception {
        final Page page = mockPage( "UnderscorePage" );
        when( attachmentManager.listAttachments( page ) ).thenReturn( Collections.emptyList() );

        try( final Directory dir = new NIOFSDirectory( luceneDir.toPath() );
             final IndexWriter writer = provider.getIndexWriter( dir ) ) {
            final Document doc = provider.luceneIndexPage( page, "hello__world", writer );
            // The __ should be replaced with a single space in the indexed content
            assertEquals( "hello world", doc.get( LuceneSearchProvider.LUCENE_PAGE_CONTENTS ) );
        }
    }

    // -----------------------------------------------------------------------
    // getAttachmentContent
    // -----------------------------------------------------------------------

    @Test
    void testGetAttachmentContentSearchableSuffix() throws Exception {
        final Attachment att = mock( Attachment.class );
        when( att.getFileName() ).thenReturn( "readme.txt" );
        final InputStream stream = new ByteArrayInputStream( "file content here".getBytes( StandardCharsets.UTF_8 ) );
        when( attachmentManager.getAttachmentStream( att ) ).thenReturn( stream );

        final String result = provider.getAttachmentContent( att );
        assertTrue( result.contains( "readme.txt" ), "Result should contain filename" );
        assertTrue( result.contains( "file content here" ), "Result should contain file content" );
    }

    @Test
    void testGetAttachmentContentNonSearchableSuffix() {
        final Attachment att = mock( Attachment.class );
        when( att.getFileName() ).thenReturn( "image.png" );

        final String result = provider.getAttachmentContent( att );
        assertEquals( "image.png", result, "Non-searchable suffix should return just the filename" );
    }

    @Test
    void testGetAttachmentContentStreamError() throws Exception {
        final Attachment att = mock( Attachment.class );
        when( att.getFileName() ).thenReturn( "data.xml" );
        when( attachmentManager.getAttachmentStream( att ) ).thenThrow( new ProviderException( "stream error" ) );

        // Should catch the error and return just the filename
        final String result = provider.getAttachmentContent( att );
        assertEquals( "data.xml", result );
    }

    @Test
    void testGetAttachmentContentByNameFound() throws Exception {
        final Attachment att = mock( Attachment.class );
        when( att.getFileName() ).thenReturn( "notes.txt" );
        when( attachmentManager.getAttachmentInfo( "Page/notes.txt", -1 ) ).thenReturn( att );
        final InputStream stream = new ByteArrayInputStream( "note text".getBytes( StandardCharsets.UTF_8 ) );
        when( attachmentManager.getAttachmentStream( att ) ).thenReturn( stream );

        final String result = provider.getAttachmentContent( "Page/notes.txt", -1 );
        assertNotNull( result );
        assertTrue( result.contains( "note text" ) );
    }

    @Test
    void testGetAttachmentContentByNameNotFound() throws Exception {
        when( attachmentManager.getAttachmentInfo( "missing.txt", -1 ) ).thenReturn( null );

        final String result = provider.getAttachmentContent( "missing.txt", -1 );
        assertNull( result, "Should return null when attachment is not found" );
    }

    @Test
    void testGetAttachmentContentByNameProviderError() throws Exception {
        when( attachmentManager.getAttachmentInfo( anyString(), anyInt() ) )
                .thenThrow( new ProviderException( "provider error" ) );

        final String result = provider.getAttachmentContent( "broken.txt", -1 );
        assertNull( result, "Should return null when provider throws" );
    }

    // -----------------------------------------------------------------------
    // updateLuceneIndex / pageRemoved / getIndexedPageNames
    // -----------------------------------------------------------------------

    @Test
    void testUpdateAndSearchLuceneIndex() throws Exception {
        final Page page = mockPage( "UpdatedPage" );
        when( attachmentManager.listAttachments( page ) ).thenReturn( Collections.emptyList() );
        when( pageManager.getPage( "UpdatedPage", PageProvider.LATEST_VERSION ) ).thenReturn( page );
        stubAuthToAllow();

        // Index a page
        provider.updateLuceneIndex( page, "unique searchable content xyz123" );

        // Verify it's in the index
        final Set< String > indexed = provider.getIndexedPageNames();
        assertTrue( indexed.contains( "UpdatedPage" ), "Page should appear in indexed page names" );
    }

    @Test
    void testPageRemovedFromIndex() throws Exception {
        final Page page = mockPage( "PageToRemove" );
        when( attachmentManager.listAttachments( page ) ).thenReturn( Collections.emptyList() );

        // Index, then remove
        provider.updateLuceneIndex( page, "temporary content" );
        assertTrue( provider.getIndexedPageNames().contains( "PageToRemove" ) );

        provider.pageRemoved( page );
        assertFalse( provider.getIndexedPageNames().contains( "PageToRemove" ),
                "Page should not be in index after removal" );
    }

    @Test
    void testGetIndexedPageNamesEmptyIndex() {
        // With empty lucene directory, should return empty set
        final Set< String > names = provider.getIndexedPageNames();
        assertNotNull( names );
        assertTrue( names.isEmpty() );
    }

    @Test
    void testGetIndexedPageNamesMultiplePages() throws Exception {
        when( attachmentManager.listAttachments( any( Page.class ) ) ).thenReturn( Collections.emptyList() );
        provider.updateLuceneIndex( mockPage( "Alpha" ), "content alpha" );
        provider.updateLuceneIndex( mockPage( "Beta" ), "content beta" );
        provider.updateLuceneIndex( mockPage( "Gamma" ), "content gamma" );

        final Set< String > names = provider.getIndexedPageNames();
        assertEquals( 3, names.size() );
        assertTrue( names.contains( "Alpha" ) );
        assertTrue( names.contains( "Beta" ) );
        assertTrue( names.contains( "Gamma" ) );
    }

    // -----------------------------------------------------------------------
    // reindexPage
    // -----------------------------------------------------------------------

    @Test
    void testReindexPageNullPageIsNoOp() {
        // Should not throw or add anything to the queue
        provider.reindexPage( null );
        assertTrue( provider.updates.isEmpty() );
    }

    @Test
    void testReindexPageAddsToQueue() {
        final Page page = mockPage( "QueuePage" );
        when( pageManager.getPureText( page ) ).thenReturn( "page text" );

        provider.reindexPage( page );
        assertEquals( 1, provider.updates.size(), "One update should be queued" );

        final Object[] pair = provider.updates.get( 0 );
        assertEquals( page, pair[ 0 ] );
        assertEquals( "page text", pair[ 1 ] );
    }

    @Test
    void testReindexPageNullTextIsNotQueued() {
        final Page page = mockPage( "NullTextPage" );
        when( pageManager.getPureText( page ) ).thenReturn( null );

        provider.reindexPage( page );
        assertTrue( provider.updates.isEmpty(), "Null text should not be queued" );
    }

    @Test
    void testReindexAttachmentUsesAttachmentContent() {
        final Attachment att = mock( Attachment.class );
        when( att.getName() ).thenReturn( "AttPage/file.txt" );
        when( att.getFileName() ).thenReturn( "file.txt" );
        final InputStream stream = new ByteArrayInputStream( "att content".getBytes( StandardCharsets.UTF_8 ) );
        try {
            when( attachmentManager.getAttachmentStream( att ) ).thenReturn( stream );
        } catch( final Exception e ) {
            throw new RuntimeException( e );
        }

        provider.reindexPage( att );
        assertEquals( 1, provider.updates.size(), "Attachment should be queued" );
    }

    // -----------------------------------------------------------------------
    // findPages
    // -----------------------------------------------------------------------

    @Test
    void testFindPagesEmptyQuery() throws Exception {
        final Collection< SearchResult > results = provider.findPages( "", wikiContext );
        assertNotNull( results );
        assertTrue( results.isEmpty(), "Empty query should return empty results" );
    }

    @Test
    void testFindPagesWhitespaceQuery() throws Exception {
        final Collection< SearchResult > results = provider.findPages( "   ", wikiContext );
        assertNotNull( results );
        assertTrue( results.isEmpty(), "Whitespace query should return empty results" );
    }

    @Test
    void testFindPagesWithResults() throws Exception {
        // Index a page first
        final Page page = mockPage( "SearchablePage" );
        when( page.getAuthor() ).thenReturn( "Author" );
        when( attachmentManager.listAttachments( page ) ).thenReturn( Collections.emptyList() );
        provider.updateLuceneIndex( page, "unique findable content xyz789" );

        // Set up for search
        when( pageManager.getPage( "SearchablePage", PageProvider.LATEST_VERSION ) ).thenReturn( page );
        stubAuthToAllow();

        final Collection< SearchResult > results = provider.findPages( "xyz789", LuceneSearchProvider.FLAG_CONTEXTS, wikiContext );
        assertNotNull( results );
        assertEquals( 1, results.size(), "Should find exactly one result" );

        final SearchResult result = results.iterator().next();
        assertEquals( "SearchablePage", result.getPage().getName() );
        assertTrue( result.getScore() > 0, "Score should be positive" );
    }

    @Test
    void testFindPagesWithoutContextsFlag() throws Exception {
        final Page page = mockPage( "NoCtxPage" );
        when( attachmentManager.listAttachments( page ) ).thenReturn( Collections.emptyList() );
        provider.updateLuceneIndex( page, "nocxt searchcontent abc456" );
        when( pageManager.getPage( "NoCtxPage", PageProvider.LATEST_VERSION ) ).thenReturn( page );
        stubAuthToAllow();

        // flags = 0 means no contexts
        final Collection< SearchResult > results = provider.findPages( "abc456", 0, wikiContext );
        assertNotNull( results );
        assertEquals( 1, results.size() );

        // Contexts should be empty (no highlighter was used)
        final SearchResult result = results.iterator().next();
        final String[] contexts = result.getContexts();
        assertNotNull( contexts );
        assertEquals( 0, contexts.length, "Without FLAG_CONTEXTS, contexts should be empty" );
    }

    @Test
    void testFindPagesWithContextsHasHighlights() throws Exception {
        final Page page = mockPage( "ContextPage" );
        when( attachmentManager.listAttachments( page ) ).thenReturn( Collections.emptyList() );
        provider.updateLuceneIndex( page, "The brilliant searchable highlight content searchword more text here" );
        when( pageManager.getPage( "ContextPage", PageProvider.LATEST_VERSION ) ).thenReturn( page );
        stubAuthToAllow();

        final Collection< SearchResult > results = provider.findPages( "searchword", LuceneSearchProvider.FLAG_CONTEXTS, wikiContext );
        assertEquals( 1, results.size() );

        final SearchResult result = results.iterator().next();
        final String[] contexts = result.getContexts();
        // With FLAG_CONTEXTS and matching content, we should get highlighted fragments
        assertTrue( contexts.length > 0, "FLAG_CONTEXTS should produce highlighted fragments" );
        assertTrue( String.join( " ", contexts ).contains( "searchmatch" ),
                "Highlighted fragment should contain 'searchmatch' CSS class" );
    }

    @Test
    void testFindPagesPageNotFoundInManager() throws Exception {
        // Index a page, but then make PageManager unable to find it
        final Page page = mockPage( "GhostPage" );
        when( attachmentManager.listAttachments( page ) ).thenReturn( Collections.emptyList() );
        provider.updateLuceneIndex( page, "ghost content ghostword999" );

        when( pageManager.getPage( "GhostPage", PageProvider.LATEST_VERSION ) ).thenReturn( null );
        stubAuthToAllow();

        // Should not include the page in results (and should remove it from index)
        final Collection< SearchResult > results = provider.findPages( "ghostword999", LuceneSearchProvider.FLAG_CONTEXTS, wikiContext );
        assertNotNull( results );
        assertTrue( results.isEmpty(), "Page not found in PageManager should not appear in results" );
    }

    @Test
    void testFindPagesDeniedByAcl() throws Exception {
        final Page page = mockPage( "DeniedPage" );
        when( attachmentManager.listAttachments( page ) ).thenReturn( Collections.emptyList() );
        provider.updateLuceneIndex( page, "denied content deniedword777" );
        when( pageManager.getPage( "DeniedPage", PageProvider.LATEST_VERSION ) ).thenReturn( page );

        // Policy does NOT allow view globally
        when( authorizationManager.checkStaticPermission( any( Session.class ), any( Permission.class ) ) ).thenReturn( false );
        // ACL denies permission too
        final Acl acl = mock( Acl.class );
        when( acl.isEmpty() ).thenReturn( false );
        when( aclManager.getPermissions( page ) ).thenReturn( acl );
        when( authorizationManager.checkPermission( any( Session.class ), any( Permission.class ) ) ).thenReturn( false );

        final Collection< SearchResult > results = provider.findPages( "deniedword777", LuceneSearchProvider.FLAG_CONTEXTS, wikiContext );
        assertNotNull( results );
        assertTrue( results.isEmpty(), "Pages denied by ACL should not appear in results" );
    }

    @Test
    void testFindPagesAllowedByPolicyFastPath() throws Exception {
        final Page page = mockPage( "FastPathPage" );
        when( attachmentManager.listAttachments( page ) ).thenReturn( Collections.emptyList() );
        provider.updateLuceneIndex( page, "fastpath content fastword111" );
        when( pageManager.getPage( "FastPathPage", PageProvider.LATEST_VERSION ) ).thenReturn( page );

        // Policy allows view globally
        when( authorizationManager.checkStaticPermission( any( Session.class ), any( Permission.class ) ) ).thenReturn( true );
        // Page has no ACL -> fast path
        when( aclManager.getPermissions( page ) ).thenReturn( null );

        final Collection< SearchResult > results = provider.findPages( "fastword111", 0, wikiContext );
        assertEquals( 1, results.size(), "Fast path (no ACL) should allow the result" );
    }

    @Test
    void testFindPagesAllowedByEmptyAclFastPath() throws Exception {
        final Page page = mockPage( "EmptyAclPage" );
        when( attachmentManager.listAttachments( page ) ).thenReturn( Collections.emptyList() );
        provider.updateLuceneIndex( page, "emptyacl content emptyaclword222" );
        when( pageManager.getPage( "EmptyAclPage", PageProvider.LATEST_VERSION ) ).thenReturn( page );

        when( authorizationManager.checkStaticPermission( any( Session.class ), any( Permission.class ) ) ).thenReturn( true );
        final Acl acl = mock( Acl.class );
        when( acl.isEmpty() ).thenReturn( true );
        when( aclManager.getPermissions( page ) ).thenReturn( acl );

        final Collection< SearchResult > results = provider.findPages( "emptyaclword222", 0, wikiContext );
        assertEquals( 1, results.size(), "Fast path (empty ACL) should allow the result" );
    }

    @Test
    void testFindPagesAllowedByCheckPermission() throws Exception {
        final Page page = mockPage( "AclCheckPage" );
        when( attachmentManager.listAttachments( page ) ).thenReturn( Collections.emptyList() );
        provider.updateLuceneIndex( page, "aclcheck content aclcheckword333" );
        when( pageManager.getPage( "AclCheckPage", PageProvider.LATEST_VERSION ) ).thenReturn( page );

        // Policy does NOT allow globally -> must check per-page
        when( authorizationManager.checkStaticPermission( any( Session.class ), any( Permission.class ) ) ).thenReturn( false );
        final Acl acl = mock( Acl.class );
        when( acl.isEmpty() ).thenReturn( false );
        when( aclManager.getPermissions( page ) ).thenReturn( acl );
        // Per-page check succeeds
        when( authorizationManager.checkPermission( any( Session.class ), any( Permission.class ) ) ).thenReturn( true );

        final Collection< SearchResult > results = provider.findPages( "aclcheckword333", 0, wikiContext );
        assertEquals( 1, results.size(), "Page allowed by per-page checkPermission should appear" );
    }

    @Test
    void testFindPagesBrokenQueryThrowsProviderException() throws Exception {
        // First index a page so the Lucene directory has a valid index
        final Page page = mockPage( "DummyForBrokenQuery" );
        when( attachmentManager.listAttachments( page ) ).thenReturn( Collections.emptyList() );
        provider.updateLuceneIndex( page, "dummy content" );

        // Lucene cannot parse queries with unmatched brackets
        assertThrows( ProviderException.class,
                () -> provider.findPages( "broken[query", LuceneSearchProvider.FLAG_CONTEXTS, wikiContext ),
                "Broken Lucene query should throw ProviderException" );
    }

    @Test
    void testFindPagesMultipleResultsSorted() throws Exception {
        when( attachmentManager.listAttachments( any( Page.class ) ) ).thenReturn( Collections.emptyList() );

        final Page page1 = mockPage( "Page1" );
        provider.updateLuceneIndex( page1, "shared keyword multitest" );
        final Page page2 = mockPage( "Page2" );
        provider.updateLuceneIndex( page2, "shared keyword multitest additional content" );

        when( pageManager.getPage( "Page1", PageProvider.LATEST_VERSION ) ).thenReturn( page1 );
        when( pageManager.getPage( "Page2", PageProvider.LATEST_VERSION ) ).thenReturn( page2 );
        stubAuthToAllow();

        final Collection< SearchResult > results = provider.findPages( "multitest", 0, wikiContext );
        assertEquals( 2, results.size(), "Both pages matching the keyword should be returned" );
    }

    // -----------------------------------------------------------------------
    // doFullLuceneReindex
    // -----------------------------------------------------------------------

    @Test
    void testDoFullLuceneReindexEmptyDirectory() throws Exception {
        // Remove and recreate the lucene directory so it's empty
        deleteDir( luceneDir );
        luceneDir.mkdirs();

        final Page page = mockPage( "ReindexPage" );
        when( pageManager.getAllPages() ).thenReturn( List.of( page ) );
        when( pageManager.getPageText( "ReindexPage", -1 ) ).thenReturn( "reindex content" );
        when( attachmentManager.listAttachments( page ) ).thenReturn( Collections.emptyList() );
        when( attachmentManager.getAllAttachments() ).thenReturn( Collections.emptyList() );

        provider.doFullLuceneReindex();

        final Set< String > indexed = provider.getIndexedPageNames();
        assertTrue( indexed.contains( "ReindexPage" ), "Full reindex should index pages" );
    }

    @Test
    void testDoFullLuceneReindexWithAttachments() throws Exception {
        deleteDir( luceneDir );
        luceneDir.mkdirs();

        when( pageManager.getAllPages() ).thenReturn( Collections.emptyList() );

        final Attachment att = mock( Attachment.class );
        when( att.getName() ).thenReturn( "AttPage/file.txt" );
        when( att.getFileName() ).thenReturn( "file.txt" );
        when( attachmentManager.getAllAttachments() ).thenReturn( List.of( att ) );
        when( attachmentManager.getAttachmentInfo( "AttPage/file.txt", -1 ) ).thenReturn( att );
        final InputStream stream = new ByteArrayInputStream( "attachment text".getBytes( StandardCharsets.UTF_8 ) );
        when( attachmentManager.getAttachmentStream( att ) ).thenReturn( stream );
        when( attachmentManager.listAttachments( any( Page.class ) ) ).thenReturn( Collections.emptyList() );

        provider.doFullLuceneReindex();

        final Set< String > indexed = provider.getIndexedPageNames();
        assertTrue( indexed.contains( "AttPage/file.txt" ), "Full reindex should index attachments" );
    }

    @Test
    void testDoFullLuceneReindexExistingIndexCallsIndexMissing() throws Exception {
        // Create an index first so the directory is not empty
        final Page page = mockPage( "ExistingPage" );
        when( attachmentManager.listAttachments( page ) ).thenReturn( Collections.emptyList() );
        provider.updateLuceneIndex( page, "existing content" );

        // Now when doFullLuceneReindex is called, it should go to the "else" branch
        // (index exists) and call indexMissingPages
        when( pageManager.getAllPages() ).thenReturn( List.of( page ) );
        when( attachmentManager.getAllAttachments() ).thenReturn( Collections.emptyList() );

        // Should not throw
        provider.doFullLuceneReindex();
    }

    // -----------------------------------------------------------------------
    // indexMissingPages
    // -----------------------------------------------------------------------

    @Test
    void testIndexMissingPagesNoIndexExists() {
        // Use a non-existent directory
        setField( provider, "luceneDirectory", new File( tempDir, "nonexistent" ).getAbsolutePath() );
        final int result = provider.indexMissingPages();
        assertEquals( 0, result, "Should return 0 when no index directory exists" );
    }

    @Test
    void testIndexMissingPagesFindsAndIndexesMissing() throws Exception {
        // Create an index with one page
        final Page existing = mockPage( "ExistingMissingTest" );
        when( attachmentManager.listAttachments( any( Page.class ) ) ).thenReturn( Collections.emptyList() );
        provider.updateLuceneIndex( existing, "existing page content" );

        // Report two pages from PageManager, one is already indexed
        final Page missing = mockPage( "MissingPage" );
        when( pageManager.getAllPages() ).thenReturn( List.of( existing, missing ) );
        when( pageManager.getPageText( "MissingPage", -1 ) ).thenReturn( "missing page content" );
        when( attachmentManager.getAllAttachments() ).thenReturn( Collections.emptyList() );

        final int count = provider.indexMissingPages();
        assertEquals( 1, count, "Should index exactly one missing page" );
        assertTrue( provider.getIndexedPageNames().contains( "MissingPage" ) );
    }

    @Test
    void testIndexMissingPagesWithMissingAttachments() throws Exception {
        // Create an index with a page
        final Page existing = mockPage( "AttMissingBase" );
        when( attachmentManager.listAttachments( any( Page.class ) ) ).thenReturn( Collections.emptyList() );
        provider.updateLuceneIndex( existing, "base content" );

        when( pageManager.getAllPages() ).thenReturn( List.of( existing ) );

        // Report a missing attachment
        final Attachment missingAtt = mock( Attachment.class );
        when( missingAtt.getName() ).thenReturn( "AttMissingBase/newfile.txt" );
        when( missingAtt.getFileName() ).thenReturn( "newfile.txt" );
        when( attachmentManager.getAllAttachments() ).thenReturn( List.of( missingAtt ) );
        when( attachmentManager.getAttachmentInfo( "AttMissingBase/newfile.txt", -1 ) ).thenReturn( missingAtt );
        final InputStream stream = new ByteArrayInputStream( "new file content".getBytes( StandardCharsets.UTF_8 ) );
        when( attachmentManager.getAttachmentStream( missingAtt ) ).thenReturn( stream );

        provider.indexMissingPages();

        assertTrue( provider.getIndexedPageNames().contains( "AttMissingBase/newfile.txt" ),
                "Missing attachment should be indexed" );
    }

    @Test
    void testIndexMissingPagesAllAlreadyIndexed() throws Exception {
        final Page page = mockPage( "AlreadyIndexedPage" );
        when( attachmentManager.listAttachments( any( Page.class ) ) ).thenReturn( Collections.emptyList() );
        provider.updateLuceneIndex( page, "already indexed content" );

        when( pageManager.getAllPages() ).thenReturn( List.of( page ) );
        when( attachmentManager.getAllAttachments() ).thenReturn( Collections.emptyList() );

        final int count = provider.indexMissingPages();
        assertEquals( 0, count, "Should not reindex already-indexed pages" );
    }

    @Test
    void testIndexMissingPagesProviderException() throws Exception {
        // Create a valid index first
        final Page page = mockPage( "ProvExPage" );
        when( attachmentManager.listAttachments( any( Page.class ) ) ).thenReturn( Collections.emptyList() );
        provider.updateLuceneIndex( page, "content" );

        when( pageManager.getAllPages() ).thenThrow( new ProviderException( "test provider error" ) );

        // Should handle the exception and return 0
        final int count = provider.indexMissingPages();
        assertEquals( 0, count );
    }

    // -----------------------------------------------------------------------
    // getProviderInfo
    // -----------------------------------------------------------------------

    @Test
    void testGetProviderInfo() {
        assertEquals( "LuceneSearchProvider", provider.getProviderInfo() );
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private Page mockPage( final String name ) {
        final Page page = mock( Page.class );
        when( page.getName() ).thenReturn( name );
        return page;
    }

    private void stubAuthToAllow() {
        when( authorizationManager.checkStaticPermission( any( Session.class ), any( Permission.class ) ) ).thenReturn( true );
        when( aclManager.getPermissions( any( Page.class ) ) ).thenReturn( null );
    }

    private static void setField( final Object target, final String fieldName, final Object value ) {
        try {
            java.lang.reflect.Field field = target.getClass().getDeclaredField( fieldName );
            field.setAccessible( true );
            field.set( target, value );
        } catch( final Exception e ) {
            throw new RuntimeException( "Failed to set field " + fieldName, e );
        }
    }

    private static void deleteDir( final File dir ) {
        if( dir.exists() ) {
            final File[] files = dir.listFiles();
            if( files != null ) {
                for( final File file : files ) {
                    if( file.isDirectory() ) {
                        deleteDir( file );
                    } else {
                        file.delete();
                    }
                }
            }
            dir.delete();
        }
    }

}
