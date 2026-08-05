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
package com.wikantik.providers;

import com.wikantik.TestEngine;
import com.wikantik.WikiPage;
import com.wikantik.api.core.Engine;
import com.wikantik.api.exceptions.ProviderException;
import com.wikantik.api.managers.PageManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.util.Collection;
import java.util.Properties;

import static com.wikantik.TestEngine.with;

/**
 * Tests for error/edge-case paths in {@link AbstractFileProvider}.
 * <p>
 * Covers: unreadable page files, null directory listing, WikiFileFilter accept logic,
 * and validateCustomPageProperties validation rules.
 */
class AbstractFileProviderTest {

    private static final String PAGE_DIR = "./target/wikantik.abstractfilter.test.pages";

    private Engine engine;
    private FileSystemProvider provider;
    private Properties props;

    @BeforeEach
    void setUp() throws Exception {
        engine = TestEngine.build(
            with( PageManager.PROP_PAGEPROVIDER, "FileSystemProvider" ),
            with( FileSystemProvider.PROP_PAGEDIR, PAGE_DIR )
        );
        props = TestEngine.getTestProperties();
        props.setProperty( PageManager.PROP_PAGEPROVIDER, "FileSystemProvider" );
        props.setProperty( FileSystemProvider.PROP_PAGEDIR, PAGE_DIR );

        provider = new FileSystemProvider();
        provider.initialize( engine, props );
    }

    @AfterEach
    void tearDown() {
        TestEngine.deleteAll( new File( PAGE_DIR ) );
        engine.stop();
    }

    // -------------------------------------------------------------------------
    // getPageText() — unreadable file
    // -------------------------------------------------------------------------

    /**
     * When a page file exists but cannot be read (no read permission), getPageText should
     * return null rather than throwing an exception, logging a warning instead.
     */
    @Test
    void testGetPageTextUnreadableFileReturnsNull() throws Exception {
        // Create the page normally first
        provider.putPageText( new WikiPage( engine, "UnreadablePage" ), "some content" );

        // Make the file unreadable
        final File pageFile = new File( PAGE_DIR, "UnreadablePage" + AbstractFileProvider.MARKDOWN_EXT );
        Assertions.assertTrue( pageFile.exists(), "page file should exist" );

        final boolean madeUnreadable = pageFile.setReadable( false );
        if ( !madeUnreadable ) {
            // On some CI environments running as root, chmod is a no-op — skip the test gracefully
            return;
        }

        try {
            final String result = provider.getPageText( "UnreadablePage", -1 );
            // The provider logs a warning and returns null for unreadable files
            Assertions.assertNull( result, "should return null for unreadable file" );
        } finally {
            pageFile.setReadable( true );
        }
    }

    // -------------------------------------------------------------------------
    // getAllPages() — null directory listing (simulated via invalid path)
    // -------------------------------------------------------------------------

    /**
     * getAllPages() must throw ProviderException when the page directory has been removed
     * after initialization, causing listFiles() to return null.
     */
    @Test
    void testGetAllPagesThrowsWhenDirectoryDisappears() throws Exception {
        // Create the provider pointing at a subdirectory we can delete later
        final String tempDir = "./target/wikantik.disappearing.test.pages";
        final File dir = new File( tempDir );
        dir.mkdirs();

        final Properties tempProps = TestEngine.getTestProperties();
        tempProps.setProperty( PageManager.PROP_PAGEPROVIDER, "FileSystemProvider" );
        tempProps.setProperty( FileSystemProvider.PROP_PAGEDIR, tempDir );

        final Engine tempEngine = TestEngine.build(
            with( PageManager.PROP_PAGEPROVIDER, "FileSystemProvider" ),
            with( FileSystemProvider.PROP_PAGEDIR, tempDir )
        );
        final FileSystemProvider tempProvider = new FileSystemProvider();
        tempProvider.initialize( tempEngine, tempProps );

        // Place a file in the directory so the provider is happy
        tempProvider.putPageText( new WikiPage( tempEngine, "TestPage" ), "content" );

        // Now delete the directory entirely — listFiles() on a non-existent path returns null
        TestEngine.deleteAll( dir );
        Assertions.assertFalse( dir.exists(), "dir should be gone" );

        // getAllPages() must detect the null listing and throw
        Assertions.assertThrows( ProviderException.class, tempProvider::getAllPages,
            "getAllPages() should throw ProviderException when directory is gone" );

        tempEngine.stop();
    }

    // -------------------------------------------------------------------------
    // WikiFileFilter.accept()
    // -------------------------------------------------------------------------

    /**
     * WikiFileFilter must accept .txt files.
     */
    @Test
    void testWikiFileFilterAcceptsTxtExtension() {
        final AbstractFileProvider.WikiFileFilter filter = new AbstractFileProvider.WikiFileFilter();
        final File dummy = new File( PAGE_DIR );
        Assertions.assertTrue( filter.accept( dummy, "MyPage.txt" ), ".txt should be accepted" );
    }

    /**
     * WikiFileFilter must accept .md files.
     */
    @Test
    void testWikiFileFilterAcceptsMdExtension() {
        final AbstractFileProvider.WikiFileFilter filter = new AbstractFileProvider.WikiFileFilter();
        final File dummy = new File( PAGE_DIR );
        Assertions.assertTrue( filter.accept( dummy, "MyPage.md" ), ".md should be accepted" );
    }

    /**
     * WikiFileFilter must reject files with other extensions.
     */
    @Test
    void testWikiFileFilterRejectsOtherExtensions() {
        final AbstractFileProvider.WikiFileFilter filter = new AbstractFileProvider.WikiFileFilter();
        final File dummy = new File( PAGE_DIR );
        Assertions.assertFalse( filter.accept( dummy, "MyPage.properties" ), ".properties should be rejected" );
        Assertions.assertFalse( filter.accept( dummy, "MyPage.bak" ), ".bak should be rejected" );
        Assertions.assertFalse( filter.accept( dummy, "MyPage" ), "no extension should be rejected" );
        Assertions.assertFalse( filter.accept( dummy, "page.mdx" ), ".mdx should be rejected" );
    }

    /**
     * WikiFileFilter must reject hidden/dot files that happen to end with .md
     * only after the percent-escape; a file literally named ".md" (no base name)
     * should be rejected because it ends with .md but has no meaningful base name —
     * however the actual filter implementation accepts it since it ends with the
     * right extension. We confirm the exact boundary behaviour here.
     */
    @Test
    void testWikiFileFilterBoundaryEmptyBaseName() {
        final AbstractFileProvider.WikiFileFilter filter = new AbstractFileProvider.WikiFileFilter();
        final File dummy = new File( PAGE_DIR );
        // ".md" ends with MARKDOWN_EXT so the filter accepts it — verify that behaviour
        Assertions.assertTrue( filter.accept( dummy, ".md" ), "bare .md suffix is accepted by filter" );
        Assertions.assertTrue( filter.accept( dummy, ".txt" ), "bare .txt suffix is accepted by filter" );
    }

    // -------------------------------------------------------------------------
    // validateCustomPageProperties() — direct tests via the protected method
    //
    // FileSystemProvider.putPageText() silently swallows the IOException from
    // validateCustomPageProperties() (logs it without re-throwing), so we call
    // the protected method directly through our provider subclass instance to
    // cover the error-branch lines.
    // -------------------------------------------------------------------------

    /**
     * More than MAX_PROPLIMIT properties must throw IOException.
     */
    @Test
    void testValidateCustomPropertiesExceedsMaxCount() {
        final int savedLimit = AbstractFileProvider.MAX_PROPLIMIT;
        AbstractFileProvider.MAX_PROPLIMIT = 2;
        try {
            final Properties custom = new Properties();
            custom.setProperty( "@key1", "v1" );
            custom.setProperty( "@key2", "v2" );
            custom.setProperty( "@key3", "v3" ); // one over the limit

            Assertions.assertThrows( IOException.class,
                () -> provider.validateCustomPageProperties( custom ),
                "should throw IOException when custom property count exceeds limit" );
        } finally {
            AbstractFileProvider.MAX_PROPLIMIT = savedLimit;
        }
    }

    /**
     * A key that exceeds MAX_PROPKEYLENGTH must throw IOException.
     */
    @Test
    void testValidateCustomPropertiesKeyTooLong() {
        final int savedLimit = AbstractFileProvider.MAX_PROPKEYLENGTH;
        AbstractFileProvider.MAX_PROPKEYLENGTH = 5;
        try {
            final Properties custom = new Properties();
            custom.setProperty( "@toolongkey", "value" ); // "@toolongkey" is 11 chars > 5

            Assertions.assertThrows( IOException.class,
                () -> provider.validateCustomPageProperties( custom ),
                "should throw IOException when key exceeds max length" );
        } finally {
            AbstractFileProvider.MAX_PROPKEYLENGTH = savedLimit;
        }
    }

    /**
     * A value that exceeds MAX_PROPVALUELENGTH must throw IOException.
     */
    @Test
    void testValidateCustomPropertiesValueTooLong() {
        final int savedLimit = AbstractFileProvider.MAX_PROPVALUELENGTH;
        AbstractFileProvider.MAX_PROPVALUELENGTH = 5;
        try {
            final Properties custom = new Properties();
            custom.setProperty( "@key", "toolongvalue" ); // 12 chars > 5

            Assertions.assertThrows( IOException.class,
                () -> provider.validateCustomPageProperties( custom ),
                "should throw IOException when value exceeds max length" );
        } finally {
            AbstractFileProvider.MAX_PROPVALUELENGTH = savedLimit;
        }
    }

    /**
     * A key containing non-ASCII characters must throw IOException.
     */
    @Test
    void testValidateCustomPropertiesNonAsciiKey() {
        final Properties custom = new Properties();
        // "\u00e9" (é) is non-ASCII printable, rejected by isAsciiPrintable()
        custom.setProperty( "@cl\u00e9", "value" );

        Assertions.assertThrows( IOException.class,
            () -> provider.validateCustomPageProperties( custom ),
            "should throw IOException when key contains non-ASCII characters" );
    }

    /**
     * A value containing non-ASCII characters must throw IOException.
     */
    @Test
    void testValidateCustomPropertiesNonAsciiValue() {
        final Properties custom = new Properties();
        custom.setProperty( "@key", "val\u00fceval" ); // ü is non-ASCII

        Assertions.assertThrows( IOException.class,
            () -> provider.validateCustomPageProperties( custom ),
            "should throw IOException when value contains non-ASCII characters" );
    }

    /**
     * Valid ASCII properties within limits must not throw.
     */
    @Test
    void testValidateCustomPropertiesValidPropertiesPass() throws Exception {
        final Properties custom = new Properties();
        custom.setProperty( "@status", "active" );
        custom.setProperty( "@owner", "alice" );

        // Should complete without exception
        Assertions.assertDoesNotThrow( () -> provider.validateCustomPageProperties( custom ) );
    }

    /**
     * Null or empty custom properties must be silently ignored.
     */
    @Test
    void testValidateCustomPropertiesNullAndEmptyPass() throws Exception {
        Assertions.assertDoesNotThrow( () -> provider.validateCustomPageProperties( null ) );
        Assertions.assertDoesNotThrow( () -> provider.validateCustomPageProperties( new Properties() ) );
    }

    /**
     * Persisting valid "@"-prefixed custom attributes via putPageText should store and retrieve them.
     */
    @Test
    void testCustomPropertiesRoundTrip() throws Exception {
        final WikiPage page = new WikiPage( engine, "ValidCustomPropsPage" );
        page.setAttribute( "@status", "active" );
        page.setAttribute( "@owner", "alice" );

        provider.putPageText( page, "content" );

        final var retrieved = provider.getPageInfo( "ValidCustomPropsPage", -1 );
        Assertions.assertNotNull( retrieved );
        Assertions.assertEquals( "active", retrieved.getAttribute( "@status" ) );
        Assertions.assertEquals( "alice", retrieved.getAttribute( "@owner" ) );
    }
}
