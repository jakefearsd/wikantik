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
import com.wikantik.api.core.Page;
import com.wikantik.api.exceptions.ProviderException;
import com.wikantik.api.providers.PageProvider;
import com.wikantik.api.spi.Wiki;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Properties;
import java.util.stream.Collectors;

/**
 * Pins the write and read paths of {@link VersioningFileProvider}: version numbering, per-version
 * metadata, archive and delete behaviour, move, and the guarantee that a refused save changes nothing.
 * Each test drives a private provider over its own {@code @TempDir}.
 */
class VersioningFileProviderVersionHistoryTest {

    private static TestEngine engine;

    @BeforeAll
    static void startEngine() {
        engine = TestEngine.build();
    }

    @AfterAll
    static void stopEngine() {
        engine.stop();
    }

    private static VersioningFileProvider provider( final File pageDir, final String cacheSize ) throws Exception {
        final Properties props = new Properties();
        props.putAll( engine.getWikiProperties() );
        props.setProperty( AbstractFileProvider.PROP_PAGEDIR, pageDir.getAbsolutePath() );
        if( cacheSize != null ) {
            props.setProperty( VersioningFileProvider.PROP_CACHE_SIZE, cacheSize );
        }
        final VersioningFileProvider p = new VersioningFileProvider();
        p.initialize( engine, props );
        return p;
    }

    private static VersioningFileProvider provider( final File pageDir ) throws Exception {
        return provider( pageDir, null );
    }

    private static Page page( final String name, final String author ) {
        final Page p = Wiki.contents().page( engine, name );
        if( author != null ) {
            p.setAuthor( author );
        }
        return p;
    }

    private static List< Integer > versionsOf( final List< Page > history ) {
        return history.stream().map( Page::getVersion ).collect( Collectors.toList() );
    }

    private static void save( final VersioningFileProvider p, final String name, final String author, final String text )
            throws ProviderException {
        p.putPageText( page( name, author ), text );
    }

    // ---- version numbering and reads ----

    @ParameterizedTest
    @ValueSource( strings = { "-1", "0", "1", "100" } )
    void everyPropertyCacheStrategyKeepsAFullHistory( final String cacheSize, @TempDir final File dir ) throws Exception {
        final VersioningFileProvider p = provider( dir, cacheSize );
        save( p, "Foo", "a", "one" );
        save( p, "Foo", "b", "two" );
        save( p, "Foo", "c", "three" );

        Assertions.assertEquals( List.of( 3, 2, 1 ), versionsOf( p.getVersionHistory( "Foo" ) ) );
        Assertions.assertEquals( "c", p.getPageInfo( "Foo", PageProvider.LATEST_VERSION ).getAuthor() );
        Assertions.assertEquals( "one", p.getPageText( "Foo", 1 ) );
        Assertions.assertEquals( "two", p.getPageText( "Foo", 2 ) );
        Assertions.assertEquals( "three", p.getPageText( "Foo", 3 ) );
    }

    @Test
    void versionsAreNumberedFromOneAndTextIsReadBackPerVersion( @TempDir final File dir ) throws Exception {
        final VersioningFileProvider p = provider( dir );
        save( p, "Foo", "a", "one" );
        Assertions.assertEquals( 1, p.getPageInfo( "Foo", PageProvider.LATEST_VERSION ).getVersion() );
        Assertions.assertEquals( "one", p.getPageText( "Foo", 1 ) );
        save( p, "Foo", "a", "two" );
        save( p, "Foo", "a", "three" );

        Assertions.assertEquals( 3, p.getPageInfo( "Foo", PageProvider.LATEST_VERSION ).getVersion() );
        Assertions.assertEquals( "three", p.getPageText( "Foo", PageProvider.LATEST_VERSION ) );
        Assertions.assertEquals( "one", p.getPageText( "Foo", 1 ) );
        Assertions.assertEquals( "two", p.getPageText( "Foo", 2 ) );
        Assertions.assertEquals( "three", p.getPageText( "Foo", 3 ) );
    }

    @ParameterizedTest
    @ValueSource( ints = { -2, 0, 4, 99 } )
    void aVersionOutsideTheHistoryIsRefused( final int version, @TempDir final File dir ) throws Exception {
        final VersioningFileProvider p = provider( dir );
        save( p, "Foo", "a", "one" );
        save( p, "Foo", "a", "two" );
        save( p, "Foo", "a", "three" );

        Assertions.assertThrows( NoSuchVersionException.class, () -> p.getPageText( "Foo", version ) );
    }

    @Test
    void versionOneOfAPageSavedOutsideTheVersioningProviderIsItsFile( @TempDir final File dir ) throws Exception {
        final VersioningFileProvider p = provider( dir );
        Files.writeString( new File( dir, "Legacy.txt" ).toPath(), "legacy text", StandardCharsets.ISO_8859_1 );

        Assertions.assertEquals( "legacy text", p.getPageText( "Legacy", 1 ) );
        Assertions.assertEquals( 1, p.getPageInfo( "Legacy", 1 ).getVersion() );
        Assertions.assertEquals( 1, p.getPageInfo( "Legacy", PageProvider.LATEST_VERSION ).getVersion() );
        Assertions.assertThrows( NoSuchVersionException.class, () -> p.getPageText( "Legacy", 2 ) );
    }

    @Test
    void pageExistsForAVersionKnowsTheWholeHistoryAndNothingBeyondIt( @TempDir final File dir ) throws Exception {
        final VersioningFileProvider p = provider( dir );
        save( p, "Foo", "a", "one" );
        save( p, "Foo", "a", "two" );
        save( p, "Foo", "a", "three" );

        for( final int v : new int[] { 1, 2, 3, PageProvider.LATEST_VERSION } ) {
            Assertions.assertTrue( p.pageExists( "Foo", v ), "version " + v );
        }
        for( final int v : new int[] { 0, 4, 99 } ) {
            Assertions.assertFalse( p.pageExists( "Foo", v ), "version " + v );
        }
        Assertions.assertFalse( p.pageExists( "Nope", 1 ) );
        Assertions.assertFalse( p.pageExists( "Nope", PageProvider.LATEST_VERSION ) );
    }

    @Test
    void getPageInfoOfAnArchivedVersionCarriesItsOwnTimestampAuthorAndNote( @TempDir final File dir ) throws Exception {
        final VersioningFileProvider p = provider( dir );
        final Page first = page( "Foo", "alice" );
        first.setAttribute( Page.CHANGENOTE, "first note" );
        first.setAttribute( Page.MARKUP_SYNTAX, "markdown" );
        p.putPageText( first, "one" );
        final long stamp = 1_600_000_000_000L;
        final File current = new File( dir, "Foo.md" );
        Assertions.assertTrue( current.setLastModified( stamp ), "fixture mtime" );

        final Page second = page( "Foo", "bob" );
        second.setAttribute( Page.CHANGENOTE, "second note" );
        second.setAttribute( Page.MARKUP_SYNTAX, "wiki" );
        p.putPageText( second, "two" );

        final Page v1 = p.getPageInfo( "Foo", 1 );
        Assertions.assertEquals( stamp, v1.getLastModified().getTime(), "archive keeps the saved file's timestamp" );
        Assertions.assertEquals( "alice", v1.getAuthor() );
        Assertions.assertEquals( "first note", v1.getAttribute( Page.CHANGENOTE ) );
        Assertions.assertEquals( "markdown", v1.getAttribute( Page.MARKUP_SYNTAX ) );
        final Page v2 = p.getPageInfo( "Foo", 2 );
        Assertions.assertEquals( "bob", v2.getAuthor() );
        Assertions.assertEquals( "second note", v2.getAttribute( Page.CHANGENOTE ) );
        Assertions.assertEquals( "wiki", v2.getAttribute( Page.MARKUP_SYNTAX ) );
        Assertions.assertNull( p.getPageInfo( "Foo", 3 ), "no such version yet" );
        Assertions.assertNull( p.getPageInfo( "Missing", 1 ) );
    }

    @Test
    void customAtPropertiesAreStoredAndReturnedWithThePage( @TempDir final File dir ) throws Exception {
        final VersioningFileProvider p = provider( dir );
        final Page pg = page( "Foo", "a" );
        pg.setAttribute( "@flavour", "vanilla" );
        p.putPageText( pg, "one" );

        Assertions.assertEquals( "vanilla", p.getPageInfo( "Foo", PageProvider.LATEST_VERSION ).getAttribute( "@flavour" ) );
    }

    @Test
    void firstVersionKeepsTheAuthorOfAHeritageFileSystemProviderPage( @TempDir final File dir ) throws Exception {
        final VersioningFileProvider p = provider( dir );
        Files.writeString( new File( dir, "Old.txt" ).toPath(), "legacy", StandardCharsets.ISO_8859_1 );
        Files.writeString( new File( dir, "Old.properties" ).toPath(), "author=heritage\n", StandardCharsets.ISO_8859_1 );

        save( p, "Old", "newcomer", "modern" );

        Assertions.assertEquals( "heritage", p.getPageInfo( "Old", 1 ).getAuthor() );
        Assertions.assertEquals( "newcomer", p.getPageInfo( "Old", 2 ).getAuthor() );
        Assertions.assertEquals( "legacy", p.getPageText( "Old", 1 ) );
        Assertions.assertEquals( "modern", p.getPageText( "Old", 2 ) );
    }

    @Test
    void aSaveWithoutAnAuthorGetsTheHeritageAuthorOrUnknown( @TempDir final File dir ) throws Exception {
        final VersioningFileProvider p = provider( dir );
        Files.writeString( new File( dir, "Old.txt" ).toPath(), "legacy", StandardCharsets.ISO_8859_1 );
        Files.writeString( new File( dir, "Old.properties" ).toPath(), "author=heritage\n", StandardCharsets.ISO_8859_1 );

        final Page anonymousOnOld = page( "Old", null );
        p.putPageText( anonymousOnOld, "modern" );
        Assertions.assertEquals( "heritage", anonymousOnOld.getAuthor() );
        Assertions.assertEquals( "heritage", p.getPageInfo( "Old", 2 ).getAuthor() );

        final Page anonymousOnNew = page( "Fresh", null );
        p.putPageText( anonymousOnNew, "text" );
        Assertions.assertEquals( "unknown", anonymousOnNew.getAuthor() );
        Assertions.assertEquals( "unknown", p.getPageInfo( "Fresh", PageProvider.LATEST_VERSION ).getAuthor() );
    }

    @Test
    void anUnreadablePropertiesFileDegradesToAnEmptyHistoryInsteadOfFailing( @TempDir final File dir ) throws Exception {
        final VersioningFileProvider p = provider( dir );
        Files.writeString( new File( dir, "Foo.txt" ).toPath(), "text", StandardCharsets.ISO_8859_1 );
        // A directory where the properties file should be: it exists, but cannot be read as a file.
        Assertions.assertTrue( new File( new File( new File( dir, "OLD" ), "Foo" ), "page.properties" ).mkdirs() );

        final Page info = p.getPageInfo( "Foo", PageProvider.LATEST_VERSION );
        Assertions.assertNotNull( info );
        Assertions.assertEquals( 1, info.getVersion() );
        Assertions.assertEquals( "text", p.getPageText( "Foo", PageProvider.LATEST_VERSION ) );
    }

    // ---- a refused save must change nothing ----

    @Test
    void aSaveRefusedForItsCustomPropertiesLeavesTextAndHistoryUntouched( @TempDir final File dir ) throws Exception {
        final VersioningFileProvider p = provider( dir );
        save( p, "Foo", "a", "one" );
        save( p, "Foo", "a", "two" );

        final Page refused = page( "Foo", "a" );
        refused.setAttribute( "@bad", "café" ); // not ASCII: refused by the property validation
        Assertions.assertThrows( ProviderException.class, () -> p.putPageText( refused, "three" ) );

        Assertions.assertEquals( "two", p.getPageText( "Foo", PageProvider.LATEST_VERSION ),
                "a refused save must not replace the current text" );
        Assertions.assertEquals( List.of( 2, 1 ), versionsOf( p.getVersionHistory( "Foo" ) ) );

        save( p, "Foo", "a", "four" );
        Assertions.assertEquals( "one", p.getPageText( "Foo", 1 ) );
        Assertions.assertEquals( "two", p.getPageText( "Foo", 2 ), "version 2 must survive the next good save" );
        Assertions.assertEquals( "four", p.getPageText( "Foo", 3 ) );
    }

    // ---- delete ----

    @Test
    void deletePageRemovesTextHistoryAndVersionDirectoryButNotOtherPages( @TempDir final File dir ) throws Exception {
        final VersioningFileProvider p = provider( dir );
        save( p, "Foo", "a", "one" );
        save( p, "Foo", "a", "two" );
        save( p, "Bar", "a", "bar" );

        p.deletePage( "Foo" );

        Assertions.assertFalse( p.pageExists( "Foo" ) );
        Assertions.assertFalse( new File( dir, "Foo.md" ).exists() );
        Assertions.assertFalse( new File( new File( dir, "OLD" ), "Foo" ).exists(), "version directory is gone" );
        Assertions.assertTrue( p.getVersionHistory( "Foo" ).isEmpty() );
        Assertions.assertTrue( p.pageExists( "Bar" ) );
        Assertions.assertEquals( "bar", p.getPageText( "Bar", PageProvider.LATEST_VERSION ) );
        p.deletePage( "NeverExisted" ); // not an error
    }

    @Test
    void aDeletedPageStartsItsVersionsAgainFromOne( @TempDir final File dir ) throws Exception {
        final VersioningFileProvider p = provider( dir );
        save( p, "Foo", "a", "one" );
        save( p, "Foo", "a", "two" );
        p.deletePage( "Foo" );

        save( p, "Foo", "b", "reborn" );

        Assertions.assertEquals( List.of( 1 ), versionsOf( p.getVersionHistory( "Foo" ) ) );
        Assertions.assertEquals( "reborn", p.getPageText( "Foo", PageProvider.LATEST_VERSION ) );
        Assertions.assertEquals( "b", p.getPageInfo( "Foo", PageProvider.LATEST_VERSION ).getAuthor() );
    }

    @Test
    void deletingTheLatestVersionPromotesThePreviousOne( @TempDir final File dir ) throws Exception {
        final VersioningFileProvider p = provider( dir );
        save( p, "Foo", "a", "one" );
        save( p, "Foo", "b", "two" );
        save( p, "Foo", "c", "three" );

        p.deleteVersion( "Foo", 3 );

        Assertions.assertEquals( "two", p.getPageText( "Foo", PageProvider.LATEST_VERSION ) );
        Assertions.assertEquals( List.of( 2, 1 ), versionsOf( p.getVersionHistory( "Foo" ) ) );
        Assertions.assertEquals( "b", p.getPageInfo( "Foo", PageProvider.LATEST_VERSION ).getAuthor() );

        p.deleteVersion( "Foo", PageProvider.LATEST_VERSION );
        Assertions.assertEquals( "one", p.getPageText( "Foo", PageProvider.LATEST_VERSION ) );
        Assertions.assertEquals( List.of( 1 ), versionsOf( p.getVersionHistory( "Foo" ) ) );
    }

    @Test
    void deletingAnArchivedVersionLeavesTheOthersAndRefusesAMissingOne( @TempDir final File dir ) throws Exception {
        final VersioningFileProvider p = provider( dir );
        save( p, "Foo", "a", "one" );
        save( p, "Foo", "a", "two" );
        save( p, "Foo", "a", "three" );

        p.deleteVersion( "Foo", 1 );

        Assertions.assertFalse( p.pageExists( "Foo", 1 ) );
        Assertions.assertEquals( "two", p.getPageText( "Foo", 2 ) );
        Assertions.assertEquals( "three", p.getPageText( "Foo", PageProvider.LATEST_VERSION ) );
        Assertions.assertThrows( NoSuchVersionException.class, () -> p.deleteVersion( "Foo", 1 ) );
        Assertions.assertThrows( NoSuchVersionException.class, () -> p.deleteVersion( "Foo", 7 ) );
    }

    // ---- move ----

    @Test
    void movePageCarriesTextAndWholeHistoryToTheNewName( @TempDir final File dir ) throws Exception {
        final VersioningFileProvider p = provider( dir );
        save( p, "From", "a", "one" );
        save( p, "From", "b", "two" );
        save( p, "From", "c", "three" );

        p.movePage( "From", "To" );

        Assertions.assertFalse( p.pageExists( "From" ) );
        Assertions.assertTrue( p.getVersionHistory( "From" ).isEmpty() );
        Assertions.assertFalse( new File( new File( dir, "OLD" ), "From" ).exists() );
        Assertions.assertEquals( "three", p.getPageText( "To", PageProvider.LATEST_VERSION ) );
        Assertions.assertEquals( "one", p.getPageText( "To", 1 ) );
        Assertions.assertEquals( List.of( 3, 2, 1 ), versionsOf( p.getVersionHistory( "To" ) ) );
        Assertions.assertEquals( "c", p.getPageInfo( "To", PageProvider.LATEST_VERSION ).getAuthor() );
    }

    @Test
    void movePageOfAPageWithoutHistoryMovesTheTextOnly( @TempDir final File dir ) throws Exception {
        final VersioningFileProvider p = provider( dir );
        Files.writeString( new File( dir, "Plain.txt" ).toPath(), "plain", StandardCharsets.ISO_8859_1 );

        p.movePage( "Plain", "Moved" );

        Assertions.assertFalse( p.pageExists( "Plain" ) );
        Assertions.assertEquals( "plain", p.getPageText( "Moved", PageProvider.LATEST_VERSION ) );
    }

    @Test
    void aPageMovedAwayCanBeSavedAgainUnderTheOldName( @TempDir final File dir ) throws Exception {
        final VersioningFileProvider p = provider( dir );
        save( p, "A", "a", "one" );
        save( p, "A", "a", "two" );
        p.movePage( "A", "B" );

        save( p, "A", "z", "new A" );

        Assertions.assertEquals( List.of( 1 ), versionsOf( p.getVersionHistory( "A" ) ) );
        Assertions.assertEquals( "new A", p.getPageText( "A", PageProvider.LATEST_VERSION ) );
        Assertions.assertEquals( List.of( 2, 1 ), versionsOf( p.getVersionHistory( "B" ) ) );
        Assertions.assertEquals( "two", p.getPageText( "B", PageProvider.LATEST_VERSION ) );
    }
}
