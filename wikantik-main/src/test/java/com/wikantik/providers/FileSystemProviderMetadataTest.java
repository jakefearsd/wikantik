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

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Properties;

/**
 * The metadata side of {@link FileSystemProvider}: the {@code .properties} companion file written on save,
 * read back with the page, moved with it and deleted with it; and a refused save leaving the page alone.
 */
class FileSystemProviderMetadataTest {

    private static TestEngine engine;

    @BeforeAll
    static void startEngine() {
        engine = TestEngine.build();
    }

    @AfterAll
    static void stopEngine() {
        engine.stop();
    }

    private static FileSystemProvider provider( final File dir ) throws Exception {
        final Properties props = new Properties();
        props.putAll( engine.getWikiProperties() );
        props.setProperty( AbstractFileProvider.PROP_PAGEDIR, dir.getAbsolutePath() );
        final FileSystemProvider p = new FileSystemProvider();
        p.initialize( engine, props );
        return p;
    }

    private static Page page( final String name ) {
        return Wiki.contents().page( engine, name );
    }

    @Test
    void authorChangeNoteViewCountAndSyntaxRoundTripThroughTheCompanionFile( @TempDir final File dir ) throws Exception {
        final FileSystemProvider p = provider( dir );
        final Page pg = page( "Foo" );
        pg.setAuthor( "alice" );
        pg.setAttribute( Page.CHANGENOTE, "fixed typo" );
        pg.setAttribute( Page.VIEWCOUNT, "42" );
        pg.setAttribute( Page.MARKUP_SYNTAX, "markdown" );
        pg.setAttribute( "@flavour", "vanilla" );
        p.putPageText( pg, "hello" );

        final Page back = p.getPageInfo( "Foo", PageProvider.LATEST_VERSION );
        Assertions.assertEquals( "alice", back.getAuthor() );
        Assertions.assertEquals( "fixed typo", back.getAttribute( Page.CHANGENOTE ) );
        Assertions.assertEquals( "42", back.getAttribute( Page.VIEWCOUNT ) );
        Assertions.assertEquals( "markdown", back.getAttribute( Page.MARKUP_SYNTAX ) );
        Assertions.assertEquals( "vanilla", back.getAttribute( "@flavour" ) );
        Assertions.assertEquals( "hello", p.getPageText( "Foo", PageProvider.LATEST_VERSION ) );
    }

    @Test
    void optionalMetadataAbsentAtSaveIsAbsentOnRead( @TempDir final File dir ) throws Exception {
        final FileSystemProvider p = provider( dir );
        final Page pg = page( "Foo" );
        pg.setAuthor( "alice" );
        p.putPageText( pg, "hello" );

        final Page back = p.getPageInfo( "Foo", PageProvider.LATEST_VERSION );
        Assertions.assertNull( back.getAttribute( Page.CHANGENOTE ) );
        Assertions.assertNull( back.getAttribute( Page.VIEWCOUNT ) );
        final Properties stored = new Properties();
        try( var in = Files.newInputStream( new File( dir, "Foo.properties" ).toPath() ) ) {
            stored.load( in );
        }
        Assertions.assertEquals( "alice", stored.getProperty( Page.AUTHOR ) );
        Assertions.assertFalse( stored.containsKey( Page.CHANGENOTE ) );
        Assertions.assertFalse( stored.containsKey( Page.VIEWCOUNT ) );
    }

    @Test
    void syntaxIsInferredFromTheFileExtensionWhenNotStored( @TempDir final File dir ) throws Exception {
        final FileSystemProvider p = provider( dir );
        Files.writeString( new File( dir, "Wikiish.txt" ).toPath(), "t", StandardCharsets.ISO_8859_1 );
        Files.writeString( new File( dir, "Mdish.md" ).toPath(), "t", StandardCharsets.ISO_8859_1 );
        Files.writeString( new File( dir, "WikiWithProps.txt" ).toPath(), "t", StandardCharsets.ISO_8859_1 );
        Files.writeString( new File( dir, "WikiWithProps.properties" ).toPath(), "author=x\n", StandardCharsets.ISO_8859_1 );
        Files.writeString( new File( dir, "MdWithProps.md" ).toPath(), "t", StandardCharsets.ISO_8859_1 );
        Files.writeString( new File( dir, "MdWithProps.properties" ).toPath(), "author=x\n", StandardCharsets.ISO_8859_1 );

        Assertions.assertEquals( "wiki", p.getPageInfo( "Wikiish", PageProvider.LATEST_VERSION ).getAttribute( Page.MARKUP_SYNTAX ) );
        Assertions.assertEquals( "markdown", p.getPageInfo( "Mdish", PageProvider.LATEST_VERSION ).getAttribute( Page.MARKUP_SYNTAX ) );
        Assertions.assertEquals( "wiki", p.getPageInfo( "WikiWithProps", PageProvider.LATEST_VERSION ).getAttribute( Page.MARKUP_SYNTAX ) );
        Assertions.assertEquals( "markdown", p.getPageInfo( "MdWithProps", PageProvider.LATEST_VERSION ).getAttribute( Page.MARKUP_SYNTAX ) );
    }

    @Test
    void aSaveRefusedForItsCustomPropertiesLeavesTheStoredPageAndMetadataAlone( @TempDir final File dir ) throws Exception {
        final FileSystemProvider p = provider( dir );
        final Page good = page( "Foo" );
        good.setAuthor( "alice" );
        p.putPageText( good, "original" );

        final Page refused = page( "Foo" );
        refused.setAuthor( "mallory" );
        refused.setAttribute( "@bad", "café" );
        Assertions.assertThrows( ProviderException.class, () -> p.putPageText( refused, "replacement" ) );

        Assertions.assertEquals( "original", p.getPageText( "Foo", PageProvider.LATEST_VERSION ),
                "a save reported as failed must not have replaced the page text" );
        Assertions.assertEquals( "alice", p.getPageInfo( "Foo", PageProvider.LATEST_VERSION ).getAuthor() );
    }

    @Test
    void deletePageRemovesTheCompanionFileAndLeavesOtherPages( @TempDir final File dir ) throws Exception {
        final FileSystemProvider p = provider( dir );
        final Page foo = page( "Foo" );
        foo.setAuthor( "a" );
        p.putPageText( foo, "foo" );
        final Page bar = page( "Bar" );
        bar.setAuthor( "b" );
        p.putPageText( bar, "bar" );

        p.deletePage( "Foo" );

        Assertions.assertFalse( p.pageExists( "Foo" ) );
        Assertions.assertFalse( new File( dir, "Foo.properties" ).exists() );
        Assertions.assertTrue( new File( dir, "Bar.properties" ).exists() );
        Assertions.assertEquals( "bar", p.getPageText( "Bar", PageProvider.LATEST_VERSION ) );
    }

    @Test
    void movePageCarriesTheCompanionFile( @TempDir final File dir ) throws Exception {
        final FileSystemProvider p = provider( dir );
        final Page foo = page( "Foo" );
        foo.setAuthor( "alice" );
        p.putPageText( foo, "foo text" );

        p.movePage( "Foo", "Moved" );

        Assertions.assertFalse( p.pageExists( "Foo" ) );
        Assertions.assertFalse( new File( dir, "Foo.properties" ).exists() );
        Assertions.assertEquals( "foo text", p.getPageText( "Moved", PageProvider.LATEST_VERSION ) );
        Assertions.assertEquals( "alice", p.getPageInfo( "Moved", PageProvider.LATEST_VERSION ).getAuthor() );
    }

    @Test
    void movePageOfAPageWithNoCompanionFileMovesTheText( @TempDir final File dir ) throws Exception {
        final FileSystemProvider p = provider( dir );
        Files.writeString( new File( dir, "Plain.txt" ).toPath(), "plain", StandardCharsets.ISO_8859_1 );

        p.movePage( "Plain", "Moved" );

        Assertions.assertFalse( new File( dir, "Plain.txt" ).exists() );
        Assertions.assertEquals( "plain", p.getPageText( "Moved", PageProvider.LATEST_VERSION ) );
    }
}
