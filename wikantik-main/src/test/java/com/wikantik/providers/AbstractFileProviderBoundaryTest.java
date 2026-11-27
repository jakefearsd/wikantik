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
import com.wikantik.api.providers.PageProvider;
import com.wikantik.api.spi.Wiki;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collection;
import java.util.Date;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Boundary and encoding behaviour of {@link AbstractFileProvider} through its concrete
 * {@link FileSystemProvider}: the change-feed cutoff, page counts, the custom-property limits,
 * the extension cache and CRLF/Latin-1/UTF-8 text.
 */
class AbstractFileProviderBoundaryTest {

    private static TestEngine engine;

    @BeforeAll
    static void startEngine() {
        engine = TestEngine.build();
    }

    @AfterAll
    static void stopEngine() {
        engine.stop();
    }

    private static FileSystemProvider provider( final File dir, final String encoding ) throws Exception {
        final Properties props = new Properties();
        props.putAll( engine.getWikiProperties() );
        props.setProperty( AbstractFileProvider.PROP_PAGEDIR, dir.getAbsolutePath() );
        if( encoding != null ) {
            props.setProperty( com.wikantik.api.core.Engine.PROP_ENCODING, encoding );
        }
        final FileSystemProvider p = new FileSystemProvider();
        p.initialize( engine, props );
        return p;
    }

    private static Page page( final String name ) {
        return Wiki.contents().page( engine, name );
    }

    private static File file( final File dir, final String name, final String text, final long mtime ) throws IOException {
        final File f = new File( dir, name );
        Files.writeString( f.toPath(), text, StandardCharsets.ISO_8859_1 );
        Assertions.assertTrue( f.setLastModified( mtime ) );
        return f;
    }

    private static Set< String > names( final Collection< Page > pages ) {
        return pages.stream().map( Page::getName ).collect( Collectors.toSet() );
    }

    // ---- change feed ----

    @Test
    void getAllChangedSinceIncludesAPageModifiedExactlyAtTheCutoff( @TempDir final File dir ) throws Exception {
        final FileSystemProvider p = provider( dir, null );
        file( dir, "Before.txt", "x", 1_600_000_000_000L - 1000L );
        file( dir, "AtCutoff.txt", "x", 1_600_000_000_000L );
        file( dir, "After.txt", "x", 1_600_000_000_000L + 1000L );

        Assertions.assertEquals( Set.of( "AtCutoff", "After" ),
                names( p.getAllChangedSince( new Date( 1_600_000_000_000L ) ) ) );
    }

    @Test
    void getAllChangedSinceWithNoCutoffOrTheEpochListsEveryPage( @TempDir final File dir ) throws Exception {
        final FileSystemProvider p = provider( dir, null );
        file( dir, "Old.txt", "x", 1_000L );
        file( dir, "New.md", "x", 1_600_000_000_000L );

        Assertions.assertEquals( Set.of( "Old", "New" ), names( p.getAllChangedSince( null ) ) );
        Assertions.assertEquals( Set.of( "Old", "New" ), names( p.getAllChangedSince( new Date( 0L ) ) ) );
        Assertions.assertEquals( Set.of( "Old", "New" ), names( p.getAllChangedSince( new Date( 1L ) ) ) );
    }

    @Test
    void getAllChangedSinceOfAMissingPageDirectoryIsEmpty( @TempDir final File dir ) throws Exception {
        final File gone = new File( dir, "pages" );
        final FileSystemProvider p = provider( gone, null );
        Assertions.assertTrue( gone.delete() );

        final Collection< Page > changed = p.getAllChangedSince( new Date( 1L ) );

        Assertions.assertNotNull( changed );
        Assertions.assertTrue( changed.isEmpty() );
        Assertions.assertEquals( 0, p.getPageCount() );
    }

    @Test
    void getPageCountCountsPageFilesOfBothExtensionsAndNothingElse( @TempDir final File dir ) throws Exception {
        final FileSystemProvider p = provider( dir, null );
        Assertions.assertEquals( 0, p.getPageCount() );
        file( dir, "One.txt", "x", 1_000L );
        file( dir, "Two.md", "x", 1_000L );
        file( dir, "Two.properties", "author=a\n", 1_000L );
        file( dir, "notes.bak", "x", 1_000L );

        Assertions.assertEquals( 2, p.getPageCount() );
    }

    @Test
    void getPageInfoCarriesTheFileModificationTime( @TempDir final File dir ) throws Exception {
        final FileSystemProvider p = provider( dir, null );
        file( dir, "Foo.txt", "x", 1_600_000_000_000L );

        Assertions.assertEquals( 1_600_000_000_000L,
                p.getPageInfo( "Foo", PageProvider.LATEST_VERSION ).getLastModified().getTime() );
        Assertions.assertNull( p.getPageInfo( "Absent", PageProvider.LATEST_VERSION ) );
    }

    // ---- extension handling ----

    @Test
    void markdownWinsOverTextWhenBothFilesExist( @TempDir final File dir ) throws Exception {
        final FileSystemProvider p = provider( dir, null );
        file( dir, "Twin.txt", "txt text", 1_000L );
        file( dir, "Twin.md", "md text", 1_000L );

        Assertions.assertEquals( "md text", p.getPageText( "Twin", PageProvider.LATEST_VERSION ) );
        Assertions.assertEquals( "md text", p.getPageText( "Twin", PageProvider.LATEST_VERSION ), "second read, extension cached" );
    }

    @Test
    void aPageThatAppearsAfterAMissLaterReadsNormally( @TempDir final File dir ) throws Exception {
        final FileSystemProvider p = provider( dir, null );
        Assertions.assertFalse( p.pageExists( "Late" ) );
        Assertions.assertNull( p.getPageText( "Late", PageProvider.LATEST_VERSION ) );

        file( dir, "Late.txt", "arrived", 1_000L );

        Assertions.assertTrue( p.pageExists( "Late" ) );
        Assertions.assertEquals( "arrived", p.getPageText( "Late", PageProvider.LATEST_VERSION ) );
    }

    @Test
    void aNewPageIsStoredAsMarkdownAndATextPageKeepsItsExtensionOnResave( @TempDir final File dir ) throws Exception {
        final FileSystemProvider p = provider( dir, null );
        p.putPageText( page( "Fresh" ), "fresh" );
        Assertions.assertTrue( new File( dir, "Fresh.md" ).exists() );
        Assertions.assertFalse( new File( dir, "Fresh.txt" ).exists() );

        file( dir, "Legacy.txt", "old", 1_000L );
        p.getPageInfo( "Legacy", PageProvider.LATEST_VERSION );
        p.putPageText( page( "Legacy" ), "new" );
        Assertions.assertTrue( new File( dir, "Legacy.txt" ).exists() );
        Assertions.assertFalse( new File( dir, "Legacy.md" ).exists() );
        Assertions.assertEquals( "new", p.getPageText( "Legacy", PageProvider.LATEST_VERSION ) );
    }

    @Test
    void savingAsMarkdownMigratesATextPageAndKeepsOneCopy( @TempDir final File dir ) throws Exception {
        final FileSystemProvider p = provider( dir, null );
        file( dir, "Legacy.txt", "old", 1_000L );
        final Page pg = page( "Legacy" );
        pg.setAttribute( Page.MARKUP_SYNTAX, "markdown" );

        p.putPageText( pg, "# new" );

        Assertions.assertFalse( new File( dir, "Legacy.txt" ).exists() );
        Assertions.assertEquals( "# new", Files.readString( new File( dir, "Legacy.md" ).toPath(), StandardCharsets.ISO_8859_1 ) );
        Assertions.assertEquals( "# new", p.getPageText( "Legacy", PageProvider.LATEST_VERSION ) );
    }

    @Test
    void deleteVersionOfTheLatestVersionDeletesTheFileAndAForgottenExtension( @TempDir final File dir ) throws Exception {
        final FileSystemProvider p = provider( dir, null );
        p.putPageText( page( "Foo" ), "text" );

        p.deleteVersion( "Foo", 1 );
        Assertions.assertTrue( p.pageExists( "Foo" ), "a specific version number is not the latest request" );
        p.deleteVersion( "Foo", PageProvider.LATEST_VERSION );

        Assertions.assertFalse( p.pageExists( "Foo" ) );
        p.putPageText( page( "Foo" ), "again" );
        Assertions.assertEquals( "again", p.getPageText( "Foo", PageProvider.LATEST_VERSION ) );
    }

    // ---- text encoding and line endings ----

    @Test
    void crlfLineEndingsSurviveASaveAndReadByteForByte( @TempDir final File dir ) throws Exception {
        final FileSystemProvider p = provider( dir, "UTF-8" );
        final String text = "---\r\ntitle: x\r\n---\r\nline one\r\nline two\r\n";

        p.putPageText( page( "Crlf" ), text );

        Assertions.assertArrayEquals( text.getBytes( StandardCharsets.UTF_8 ),
                Files.readAllBytes( new File( dir, "Crlf.md" ).toPath() ) );
        Assertions.assertEquals( text, p.getPageText( "Crlf", PageProvider.LATEST_VERSION ) );
    }

    @Test
    void utf8TextRoundTripsAndIsWrittenAsUtf8( @TempDir final File dir ) throws Exception {
        final FileSystemProvider p = provider( dir, "UTF-8" );
        final String text = "café — 日本語";

        p.putPageText( page( "Utf" ), text );

        Assertions.assertArrayEquals( text.getBytes( StandardCharsets.UTF_8 ),
                Files.readAllBytes( new File( dir, "Utf.md" ).toPath() ) );
        Assertions.assertEquals( text, p.getPageText( "Utf", PageProvider.LATEST_VERSION ) );
    }

    @Test
    void latin1IsTheDefaultEncoding( @TempDir final File dir ) throws Exception {
        final FileSystemProvider p = provider( dir, null );

        p.putPageText( page( "Latin" ), "café" );

        Assertions.assertArrayEquals( new byte[] { 'c', 'a', 'f', ( byte ) 0xE9 },
                Files.readAllBytes( new File( dir, "Latin.md" ).toPath() ) );
        Assertions.assertEquals( "café", p.getPageText( "Latin", PageProvider.LATEST_VERSION ) );
    }

    @Test
    void anEmptyPageIsSavedAndReadBackAsEmptyNotAsMissing( @TempDir final File dir ) throws Exception {
        final FileSystemProvider p = provider( dir, null );

        p.putPageText( page( "Empty" ), "" );

        Assertions.assertTrue( p.pageExists( "Empty" ) );
        Assertions.assertEquals( "", p.getPageText( "Empty", PageProvider.LATEST_VERSION ) );
    }

    @Test
    void namesWithSlashesSpacesAndLeadingDotsAreMangledAndRecovered( @TempDir final File dir ) throws Exception {
        final FileSystemProvider p = provider( dir, "UTF-8" );
        for( final String name : new String[] { "A/B", ".hidden", "with space", "résumé" } ) {
            p.putPageText( page( name ), "body of " + name );
        }

        Assertions.assertEquals( Set.of( "A/B", ".hidden", "with space", "résumé" ), names( p.getAllPages() ) );
        Assertions.assertEquals( "body of A/B", p.getPageText( "A/B", PageProvider.LATEST_VERSION ) );
        Assertions.assertEquals( "body of .hidden", p.getPageText( ".hidden", PageProvider.LATEST_VERSION ) );
        Assertions.assertTrue( new File( dir, "A%2FB.md" ).exists() );
        Assertions.assertTrue( new File( dir, "%2Ehidden.md" ).exists() );
    }

    // ---- custom property limits ----

    private static Page withCustom( final String key, final String value ) {
        final Page pg = page( "Props" );
        pg.setAttribute( key, value );
        return pg;
    }

    @Test
    void customPropertyKeyAndValueLengthsAreAcceptedAtTheLimitAndRefusedOverIt( @TempDir final File dir ) throws Exception {
        final FileSystemProvider p = provider( dir, null );
        p.setCustomPropertyLimitsForTesting( 3, 6, 8 );

        p.putPageText( withCustom( "@abcde", "12345678" ), "ok" );
        Assertions.assertThrows( com.wikantik.api.exceptions.ProviderException.class,
                () -> p.putPageText( withCustom( "@abcdef", "1" ), "key too long" ) );
        Assertions.assertThrows( com.wikantik.api.exceptions.ProviderException.class,
                () -> p.putPageText( withCustom( "@a", "123456789" ), "value too long" ) );
    }

    @Test
    void theNumberOfCustomPropertiesIsAcceptedAtTheLimitAndRefusedOverIt( @TempDir final File dir ) throws Exception {
        final FileSystemProvider p = provider( dir, null );
        p.setCustomPropertyLimitsForTesting( 2, 20, 20 );
        final Page atLimit = page( "Props" );
        atLimit.setAttribute( "@a", "1" );
        atLimit.setAttribute( "@b", "2" );
        p.putPageText( atLimit, "ok" );

        final Page over = page( "Props" );
        over.setAttribute( "@a", "1" );
        over.setAttribute( "@b", "2" );
        over.setAttribute( "@c", "3" );
        Assertions.assertThrows( com.wikantik.api.exceptions.ProviderException.class, () -> p.putPageText( over, "too many" ) );
        Assertions.assertEquals( "ok", p.getPageText( "Props", PageProvider.LATEST_VERSION ) );
    }
}
