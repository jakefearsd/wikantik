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
import com.wikantik.api.core.Page;
import com.wikantik.pages.PageManager;
import com.wikantik.util.FileUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

import static com.wikantik.TestEngine.with;


public class FileSystemProviderTest {

    FileSystemProvider m_provider;
    FileSystemProvider m_providerUTF8;
    Properties props = TestEngine.getTestProperties();

    Engine m_engine = TestEngine.build( with( PageManager.PROP_PAGEPROVIDER, "FileSystemProvider" ),
                                        with( FileSystemProvider.PROP_PAGEDIR, "./target/wikantik.test.pages" ) );

    @BeforeEach
    public void setUp() throws Exception {
        props.setProperty( PageManager.PROP_PAGEPROVIDER, "FileSystemProvider" );
        props.setProperty( FileSystemProvider.PROP_PAGEDIR, "./target/wikantik.test.pages" );

        m_provider = new FileSystemProvider();
        m_provider.initialize( m_engine, props );

        props.setProperty( Engine.PROP_ENCODING, StandardCharsets.UTF_8.name() );
        m_providerUTF8 = new FileSystemProvider();
        m_providerUTF8.initialize( m_engine, props );
    }

    @AfterEach
    public void tearDown() {
        TestEngine.deleteAll( new File( props.getProperty( FileSystemProvider.PROP_PAGEDIR ) ) );
    }

    @Test
    public void testScandinavianLetters() throws Exception {
        final WikiPage page = new WikiPage(m_engine, "\u00c5\u00e4Test");

        m_provider.putPageText( page, "test" );

        final File resultfile = new File(  props.getProperty( FileSystemProvider.PROP_PAGEDIR ) , "%C5%E4Test.md" );

        Assertions.assertTrue( resultfile.exists(), "No such file" );

        final String contents = FileUtil.readContents( new FileInputStream(resultfile), StandardCharsets.ISO_8859_1.name() );

        Assertions.assertEquals( contents, "test", "Wrong contents" );
    }

    @Test
    public void testScandinavianLettersUTF8() throws Exception {
        final WikiPage page = new WikiPage(m_engine, "\u00c5\u00e4Test");

        m_providerUTF8.putPageText( page, "test\u00d6" );

        final File resultfile = new File(  props.getProperty( FileSystemProvider.PROP_PAGEDIR ) , "%C3%85%C3%A4Test.md" );

        Assertions.assertTrue( resultfile.exists(), "No such file" );

        final String contents = FileUtil.readContents( new FileInputStream(resultfile),
                                                 StandardCharsets.UTF_8.name() );

        Assertions.assertEquals( contents, "test\u00d6", "Wrong contents" );
    }

    /**
     * This should never happen, but let's check that we're protected anyway.
     * @throws Exception
     */
    @Test
    public void testSlashesInPageNamesUTF8()
         throws Exception
    {
        final WikiPage page = new WikiPage(m_engine, "Test/Foobar");

        m_providerUTF8.putPageText( page, "test" );

        final File resultfile = new File(  props.getProperty( FileSystemProvider.PROP_PAGEDIR ) , "Test%2FFoobar.md" );

        Assertions.assertTrue( resultfile.exists(), "No such file" );

        final String contents = FileUtil.readContents( new FileInputStream(resultfile),
                                                 StandardCharsets.UTF_8.name() );

        Assertions.assertEquals( contents, "test", "Wrong contents" );
    }

    @Test
    public void testSlashesInPageNames()
         throws Exception
    {
        final WikiPage page = new WikiPage(m_engine, "Test/Foobar");

        m_provider.putPageText( page, "test" );

        final File resultfile = new File(  props.getProperty( FileSystemProvider.PROP_PAGEDIR ) , "Test%2FFoobar.md" );

        Assertions.assertTrue( resultfile.exists(), "No such file" );

        final String contents = FileUtil.readContents( new FileInputStream(resultfile),
                                                 StandardCharsets.ISO_8859_1.name() );

        Assertions.assertEquals( contents, "test", "Wrong contents" );
    }

    @Test
    public void testDotsInBeginning()
       throws Exception
    {
        final WikiPage page = new WikiPage(m_engine, ".Test");

        m_provider.putPageText( page, "test" );

        final File resultfile = new File(  props.getProperty( FileSystemProvider.PROP_PAGEDIR ) , "%2ETest.md" );

        Assertions.assertTrue( resultfile.exists(), "No such file" );

        final String contents = FileUtil.readContents( new FileInputStream(resultfile), StandardCharsets.ISO_8859_1.name() );

        Assertions.assertEquals( contents, "test", "Wrong contents" );
    }

    @Test
    public void testAuthor()
        throws Exception
    {
        try
        {
            final WikiPage page = new WikiPage(m_engine, "\u00c5\u00e4Test");
            page.setAuthor("Min\u00e4");

            m_provider.putPageText( page, "test" );

            final Page page2 = m_provider.getPageInfo( "\u00c5\u00e4Test", 1 );

            Assertions.assertEquals( "Min\u00e4", page2.getAuthor() );
        }
        finally
        {
            File resultfile = new File(  props.getProperty( FileSystemProvider.PROP_PAGEDIR ), "%C5%E4Test.md" );
            try {
                resultfile.delete();
            } catch( final Exception e) {}

            resultfile = new File(  props.getProperty( FileSystemProvider.PROP_PAGEDIR ), "%C5%E4Test.properties" );
            try {
                resultfile.delete();
            } catch( final Exception e) {}
        }
    }

    @Test
    public void testNonExistantDirectory() throws Exception {
        final String tmpdir =  props.getProperty( FileSystemProvider.PROP_PAGEDIR ) ;
        final String dirname = "non-existant-directory";

        final String newdir = tmpdir + File.separator + dirname;

        final Properties pr = new Properties();

        pr.setProperty( FileSystemProvider.PROP_PAGEDIR,
                           newdir );

        final FileSystemProvider test = new FileSystemProvider();

        test.initialize( m_engine, pr );

        final File f = new File( newdir );

        Assertions.assertTrue( f.exists(), "didn't create it" );
        Assertions.assertTrue( f.isDirectory(), "isn't a dir" );

        f.delete();
    }

    @Test
    public void testDirectoryIsFile()
        throws Exception
    {
        File tmpFile = null;

        try
        {
            tmpFile = FileUtil.newTmpFile("foobar"); // Content does not matter.

            final Properties pr = new Properties();

            pr.setProperty( FileSystemProvider.PROP_PAGEDIR, tmpFile.getAbsolutePath() );

            final FileSystemProvider test = new FileSystemProvider();

            try
            {
                test.initialize( m_engine, pr );

                Assertions.fail( "Wiki did not warn about wrong property." );
            }
            catch( final IOException e )
            {
                // This is okay.
            }
        }
        finally
        {
            if( tmpFile != null )
            {
                tmpFile.delete();
            }
        }
    }

    @Test
    public void testDelete()
        throws Exception
    {
        final String files = props.getProperty( FileSystemProvider.PROP_PAGEDIR );

        final WikiPage p = new WikiPage(m_engine,"Test");
        p.setAuthor("AnonymousCoward");

        m_provider.putPageText( p, "v1" );

        File f = new File( files, "Test"+FileSystemProvider.MARKDOWN_EXT );

        Assertions.assertTrue( f.exists(), "file does not exist" );

        f = new File( files, "Test.properties" );

        Assertions.assertTrue( f.exists(), "property file does not exist" );

        m_provider.deletePage( "Test" );

        f = new File( files, "Test"+FileSystemProvider.MARKDOWN_EXT );

        Assertions.assertFalse( f.exists(), "file exists" );

        f = new File( files, "Test.properties" );

        Assertions.assertFalse( f.exists(), "properties exist" );
    }

    @Test
    public void testCustomProperties() throws Exception {
        final String pageDir = props.getProperty( FileSystemProvider.PROP_PAGEDIR );
        final String pageName = "CustomPropertiesTest";
        final String fileName = pageName+FileSystemProvider.MARKDOWN_EXT;
        final File file = new File (pageDir,fileName);

        Assertions.assertFalse( file.exists() );
        final WikiPage testPage = new WikiPage(m_engine,pageName);
        testPage.setAuthor("TestAuthor");
        testPage.setAttribute("@test","Save Me");
        testPage.setAttribute("@test2","Save You");
        testPage.setAttribute("test3","Do not save");
        m_provider.putPageText( testPage, "This page has custom properties" );
        Assertions.assertTrue( file.exists(), "No such file" );
        final Page pageRetrieved = m_provider.getPageInfo( pageName, -1 );
        final String value = pageRetrieved.getAttribute("@test");
        final String value2 = pageRetrieved.getAttribute("@test2");
        final String value3 = pageRetrieved.getAttribute("test3");
        Assertions.assertNotNull(value);
        Assertions.assertNotNull(value2);
        Assertions.assertNull(value3);
        Assertions.assertEquals("Save Me",value);
        Assertions.assertEquals("Save You",value2);
        file.delete();
        Assertions.assertFalse( file.exists() );
    }

    @Test
    public void testMarkdownPageCreation() throws Exception {
        final String pageDir = props.getProperty( FileSystemProvider.PROP_PAGEDIR );
        final String pageName = "MarkdownTest";
        final WikiPage page = new WikiPage( m_engine, pageName );
        page.setAttribute( Page.MARKUP_SYNTAX, "markdown" );
        page.setAuthor( "TestAuthor" );

        m_provider.putPageText( page, "# Markdown Test\n\nThis is **markdown**." );

        // Verify file was created with .md extension
        final File mdFile = new File( pageDir, pageName + AbstractFileProvider.MARKDOWN_EXT );
        Assertions.assertTrue( mdFile.exists(), "Markdown file should exist" );

        // Verify .txt file was not created
        final File txtFile = new File( pageDir, pageName + AbstractFileProvider.FILE_EXT );
        Assertions.assertFalse( txtFile.exists(), "Wiki file should not exist" );

        // Cleanup
        mdFile.delete();
        new File( pageDir, pageName + ".properties" ).delete();
    }

    @Test
    public void testMarkdownPageRetrieval() throws Exception {
        final String pageDir = props.getProperty( FileSystemProvider.PROP_PAGEDIR );
        final String pageName = "MarkdownRetrievalTest";
        final WikiPage page = new WikiPage( m_engine, pageName );
        page.setAttribute( Page.MARKUP_SYNTAX, "markdown" );
        page.setAuthor( "TestAuthor" );

        final String content = "# Markdown Test\n\nThis is **markdown**.";
        m_provider.putPageText( page, content );

        // Retrieve page info and verify markup syntax
        final Page retrievedPage = m_provider.getPageInfo( pageName, -1 );
        Assertions.assertNotNull( retrievedPage, "Page should be retrieved" );
        Assertions.assertEquals( "markdown", retrievedPage.getAttribute( Page.MARKUP_SYNTAX ), "Markup syntax should be markdown" );
        Assertions.assertEquals( "TestAuthor", retrievedPage.getAuthor(), "Author should be preserved" );

        // Retrieve page text
        final String retrievedContent = m_provider.getPageText( pageName, -1 );
        Assertions.assertEquals( content, retrievedContent, "Content should match" );

        // Cleanup
        new File( pageDir, pageName + AbstractFileProvider.MARKDOWN_EXT ).delete();
        new File( pageDir, pageName + ".properties" ).delete();
    }

    @Test
    public void testMarkdownPrecedenceOverWiki() throws Exception {
        final String pageDir = props.getProperty( FileSystemProvider.PROP_PAGEDIR );
        final String pageName = "PrecedenceTest";

        // Manually create both .txt and .md files to test precedence
        final File txtFile = new File( pageDir, pageName + AbstractFileProvider.FILE_EXT );
        final File mdFile = new File( pageDir, pageName + AbstractFileProvider.MARKDOWN_EXT );

        FileUtil.copyContents( new java.io.ByteArrayInputStream( "Wiki content".getBytes() ),
                               new java.io.FileOutputStream( txtFile ) );
        FileUtil.copyContents( new java.io.ByteArrayInputStream( "# Markdown content".getBytes() ),
                               new java.io.FileOutputStream( mdFile ) );

        // Verify both files exist
        Assertions.assertTrue( txtFile.exists(), "Wiki file should exist" );
        Assertions.assertTrue( mdFile.exists(), "Markdown file should exist" );

        // Retrieve page and verify markdown takes precedence
        final String content = m_provider.getPageText( pageName, -1 );
        Assertions.assertEquals( "# Markdown content", content, "Markdown content should take precedence" );

        final Page retrievedPage = m_provider.getPageInfo( pageName, -1 );
        Assertions.assertEquals( "markdown", retrievedPage.getAttribute( Page.MARKUP_SYNTAX ), "Should use markdown syntax" );

        // Cleanup
        txtFile.delete();
        mdFile.delete();
        final File propsFile = new File( pageDir, pageName + ".properties" );
        if( propsFile.exists() ) {
            propsFile.delete();
        }
    }

    @Test
    public void testGetAllPagesWithMixedExtensions() throws Exception {
        final String pageDir = props.getProperty( FileSystemProvider.PROP_PAGEDIR );

        // Create wiki syntax pages
        for( int i = 1; i <= 3; i++ ) {
            final WikiPage page = new WikiPage( m_engine, "WikiPage" + i );
            page.setAttribute( Page.MARKUP_SYNTAX, "jspwiki" );
            m_provider.putPageText( page, "Wiki content " + i );
        }

        // Create markdown pages
        for( int i = 1; i <= 3; i++ ) {
            final WikiPage page = new WikiPage( m_engine, "MarkdownPage" + i );
            page.setAttribute( Page.MARKUP_SYNTAX, "markdown" );
            m_provider.putPageText( page, "# Markdown content " + i );
        }

        // Get all pages
        final var allPages = m_provider.getAllPages();
        Assertions.assertEquals( 6, allPages.size(), "Should have 6 pages total" );

        // Verify markup syntax is correctly set for each page
        int wikiCount = 0;
        int markdownCount = 0;
        for( final Page page : allPages ) {
            final String syntax = page.getAttribute( Page.MARKUP_SYNTAX );
            if( "jspwiki".equals( syntax ) ) {
                wikiCount++;
            } else if( "markdown".equals( syntax ) ) {
                markdownCount++;
            }
        }

        Assertions.assertEquals( 3, wikiCount, "Should have 3 wiki pages" );
        Assertions.assertEquals( 3, markdownCount, "Should have 3 markdown pages" );

        // Cleanup
        for( int i = 1; i <= 3; i++ ) {
            new File( pageDir, "WikiPage" + i + AbstractFileProvider.FILE_EXT ).delete();
            new File( pageDir, "WikiPage" + i + ".properties" ).delete();
            new File( pageDir, "MarkdownPage" + i + AbstractFileProvider.MARKDOWN_EXT ).delete();
            new File( pageDir, "MarkdownPage" + i + ".properties" ).delete();
        }
    }

    @Test
    public void testExternalMarkdownFileDetection() throws Exception {
        final String pageDir = props.getProperty( FileSystemProvider.PROP_PAGEDIR );
        final String pageName = "ExternalMarkdown";

        // Manually create a .md file (simulating external creation)
        final File mdFile = new File( pageDir, pageName + AbstractFileProvider.MARKDOWN_EXT );
        FileUtil.copyContents( new java.io.ByteArrayInputStream( "# External Markdown".getBytes() ),
                               new java.io.FileOutputStream( mdFile ) );

        // Retrieve page info without properties file
        final Page page = m_provider.getPageInfo( pageName, -1 );
        Assertions.assertNotNull( page, "Page should be retrieved" );
        Assertions.assertEquals( "markdown", page.getAttribute( Page.MARKUP_SYNTAX ),
                                 "Should infer markdown syntax from .md extension" );

        // Verify content can be read
        final String content = m_provider.getPageText( pageName, -1 );
        Assertions.assertEquals( "# External Markdown", content, "Content should match" );

        // Cleanup
        mdFile.delete();
    }

    @Test
    public void testMarkdownPageDeletion() throws Exception {
        final String pageDir = props.getProperty( FileSystemProvider.PROP_PAGEDIR );
        final String pageName = "MarkdownDeleteTest";
        final WikiPage page = new WikiPage( m_engine, pageName );
        page.setAttribute( Page.MARKUP_SYNTAX, "markdown" );

        m_provider.putPageText( page, "# To be deleted" );

        final File mdFile = new File( pageDir, pageName + AbstractFileProvider.MARKDOWN_EXT );
        final File propsFile = new File( pageDir, pageName + ".properties" );

        Assertions.assertTrue( mdFile.exists(), "Markdown file should exist" );
        Assertions.assertTrue( propsFile.exists(), "Properties file should exist" );

        m_provider.deletePage( pageName );

        Assertions.assertFalse( mdFile.exists(), "Markdown file should be deleted" );
        Assertions.assertFalse( propsFile.exists(), "Properties file should be deleted" );
    }

}
