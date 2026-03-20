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
package com.wikantik.content;

import com.wikantik.TestEngine;
import com.wikantik.api.core.Attachment;
import com.wikantik.api.core.Context;
import com.wikantik.api.core.Engine;
import com.wikantik.api.core.Page;
import com.wikantik.api.exceptions.WikiException;
import com.wikantik.api.providers.WikiProvider;
import com.wikantik.api.spi.Wiki;
import com.wikantik.attachment.AttachmentManager;
import com.wikantik.pages.PageManager;
import com.wikantik.references.ReferenceManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.Collection;

import static com.wikantik.TestEngine.with;


public class PageRenamerTest {

    TestEngine m_engine = TestEngine.build( with( Engine.PROP_MATCHPLURALS, "true" ) );

    @AfterEach
    public void tearDown() {
        m_engine.stop();
    }

    @Test
    public void testSimpleRename() throws Exception {
        // Count the number of existing references
        final int refCount = m_engine.getManager( ReferenceManager.class ).findCreated().size();

        m_engine.saveText("TestPage", "the big lazy dog thing" );

        final Page p = m_engine.getManager( PageManager.class ).getPage("TestPage");

        final Context context = Wiki.context().create(m_engine, p);

        m_engine.getManager( PageRenamer.class ).renamePage(context, "TestPage", "FooTest", false);

        final Page newpage = m_engine.getManager( PageManager.class ).getPage("FooTest");

        Assertions.assertNotNull( newpage, "no new page" );
        Assertions.assertNull( m_engine.getManager( PageManager.class ).getPage("TestPage"), "old page not gone" );

        // Refmgr
        final Collection< String > refs = m_engine.getManager( ReferenceManager.class ).findCreated();

        Assertions.assertTrue( refs.contains("FooTest"), "FooTest does not exist" );
        Assertions.assertFalse( refs.contains("TestPage"), "TestPage exists" );
        Assertions.assertEquals( refCount+1, refs.size(), "wrong list size" );
    }

    @Test
    public void testReferrerChange() throws Exception  {
        m_engine.saveText("TestPage", "foofoo" );
        m_engine.saveText("TestPage2", "[TestPage]()");

        final Page p = m_engine.getManager( PageManager.class ).getPage("TestPage");
        final Context context = Wiki.context().create(m_engine, p);
        m_engine.getManager( PageRenamer.class ).renamePage(context, "TestPage", "FooTest", true);

        final String data = m_engine.getManager( PageManager.class ).getPureText("TestPage2", WikiProvider.LATEST_VERSION);
        Assertions.assertEquals( "[FooTest]()", data.trim(), "no rename" );

        Collection< String > refs = m_engine.getManager( ReferenceManager.class ).findReferrers("TestPage");
        Assertions.assertTrue( refs.isEmpty(), "oldpage" );
        refs = m_engine.getManager( ReferenceManager.class ).findReferrers( "FooTest" );
        Assertions.assertEquals( 1, refs.size(), "new size" );
        Assertions.assertEquals( "TestPage2", refs.iterator().next(), "wrong ref" );
    }

    @Test
    public void testReferrerChangeMultiRename() throws Exception  {
        m_engine.saveText("TestPage", "foofoo" );
        m_engine.saveText("TestPage2", "[TestPage]()");

        final Page p = m_engine.getManager( PageManager.class ).getPage("TestPage");
        final Context context = Wiki.context().create(m_engine, p);
        m_engine.getManager( PageRenamer.class ).renamePage(context, "TestPage", "FooTest", true);
        m_engine.getManager( PageRenamer.class ).renamePage(context, "FooTest", "BarTest", true);

        final String data = m_engine.getManager( PageManager.class ).getPureText("TestPage2", WikiProvider.LATEST_VERSION);
        Assertions.assertEquals( "[BarTest]()", data.trim(), "no rename" );

        Collection< String > refs = m_engine.getManager( ReferenceManager.class ).findReferrers("TestPage");
        Assertions.assertTrue( refs.isEmpty(), "oldpage" );
        refs = m_engine.getManager( ReferenceManager.class ).findReferrers("FooPage");
        Assertions.assertTrue( refs.isEmpty(), "oldpage" );
        refs = m_engine.getManager( ReferenceManager.class ).findReferrers( "BarTest" );
        Assertions.assertEquals( 1, refs.size(), "new size" );
        Assertions.assertEquals( "TestPage2", refs.iterator().next(), "wrong ref" );
    }

    @Test
    public void testReferrerChangeMultiRename2() throws Exception  {
        m_engine.saveText("TestPage", "foofoo" );
        m_engine.saveText("TestPage2", "[Test](TestPage)");

        final Page p = m_engine.getManager( PageManager.class ).getPage("TestPage");
        final Context context = Wiki.context().create(m_engine, p);
        m_engine.getManager( PageRenamer.class ).renamePage(context, "TestPage", "FooTest", true);
        m_engine.getManager( PageRenamer.class ).renamePage(context, "FooTest", "BarTest", true);

        final String data = m_engine.getManager( PageManager.class ).getPureText("TestPage2", WikiProvider.LATEST_VERSION);
        Assertions.assertEquals( "[Test](BarTest)", data.trim(), "no rename" );

        Collection< String > refs = m_engine.getManager( ReferenceManager.class ).findReferrers("TestPage");
        Assertions.assertTrue( refs.isEmpty(), "oldpage" );
        refs = m_engine.getManager( ReferenceManager.class ).findReferrers("FooPage");
        Assertions.assertTrue( refs.isEmpty(), "oldpage" );
        refs = m_engine.getManager( ReferenceManager.class ).findReferrers( "BarTest" );
        Assertions.assertEquals( 1, refs.size(), "new size" );
        Assertions.assertEquals( "TestPage2", refs.iterator().next(), "wrong ref" );
    }

    @Test
    public void testReferrerChangeCC() throws Exception {
        m_engine.saveText("TestPage", "foofoo" );
        m_engine.saveText("TestPage2", "[TestPage](TestPage)");

        final Page p = m_engine.getManager( PageManager.class ).getPage("TestPage");
        final Context context = Wiki.context().create(m_engine, p);
        m_engine.getManager( PageRenamer.class ).renamePage(context, "TestPage", "FooTest", true);

        final String data = m_engine.getManager( PageManager.class ).getPureText("TestPage2", WikiProvider.LATEST_VERSION);
        Assertions.assertEquals( "[FooTest](FooTest)", data.trim(), "no rename" );

        Collection< String > refs = m_engine.getManager( ReferenceManager.class ).findReferrers("TestPage");
        Assertions.assertTrue( refs.isEmpty(), "oldpage" );
        refs = m_engine.getManager( ReferenceManager.class ).findReferrers( "FooTest" );
        Assertions.assertEquals( 1, refs.size(), "new size" );
        Assertions.assertEquals( "TestPage2", refs.iterator().next(), "wrong ref" );
    }

    @Test
    public void testReferrerChangeAnchor()
        throws Exception
    {
        m_engine.saveText("TestPage", "foofoo" );
        m_engine.saveText("TestPage2", "[TestPage#heading1](TestPage#heading1)");

        final Page p = m_engine.getManager( PageManager.class ).getPage("TestPage");

        final Context context = Wiki.context().create(m_engine, p);

        m_engine.getManager( PageRenamer.class ).renamePage(context, "TestPage", "FooTest", true);

        final String data = m_engine.getManager( PageManager.class ).getPureText("TestPage2", WikiProvider.LATEST_VERSION);

        Assertions.assertEquals( "[FooTest#heading1](FooTest#heading1)", data.trim(), "no rename" );
        Collection< String > refs = m_engine.getManager( ReferenceManager.class ).findReferrers("TestPage");

        Assertions.assertTrue( refs.isEmpty(), "oldpage" );

        refs = m_engine.getManager( ReferenceManager.class ).findReferrers( "FooTest" );
        Assertions.assertEquals( 1, refs.size(), "new size" );
        Assertions.assertEquals( "TestPage2", refs.iterator().next(), "wrong ref" );
    }

    @Test
    public void testReferrerChangeMultilink()
        throws Exception
    {
        m_engine.saveText("TestPage", "foofoo" );
        m_engine.saveText("TestPage2", "[TestPage]() [TestPage]() [linktext](TestPage) TestPage [linktext](TestPage) [TestPage#Anchor]() [TestPage]() TestPage [TestPage]()");

        final Page p = m_engine.getManager( PageManager.class ).getPage("TestPage");

        final Context context = Wiki.context().create(m_engine, p);

        m_engine.getManager( PageRenamer.class ).renamePage(context, "TestPage", "FooTest", true);

        final String data = m_engine.getManager( PageManager.class ).getPureText("TestPage2", WikiProvider.LATEST_VERSION);

        Assertions.assertEquals( "[FooTest]() [FooTest]() [linktext](FooTest) FooTest [linktext](FooTest) [FooTest#Anchor]() [FooTest]() FooTest [FooTest]()",
                                 data.trim(),
                                 "no rename" );

        Collection< String > refs = m_engine.getManager( ReferenceManager.class ).findReferrers("TestPage");

        Assertions.assertTrue( refs.isEmpty(), "oldpage" );

        refs = m_engine.getManager( ReferenceManager.class ).findReferrers( "FooTest" );
        Assertions.assertEquals( 1, refs.size(), "new size" );
        Assertions.assertEquals( "TestPage2", refs.iterator().next(), "wrong ref" );
    }

    @Test
    public void testReferrerNoWikiName()
        throws Exception
    {
        m_engine.saveText("Test","foo");
        m_engine.saveText("TestPage2", "[Test](Test) [Test#anchor](Test#anchor) test Test [test](Test) [link](Test) [link](Test)");

        final Page p = m_engine.getManager( PageManager.class ).getPage("TestPage");

        final Context context = Wiki.context().create(m_engine, p);

        m_engine.getManager( PageRenamer.class ).renamePage(context, "Test", "TestPage", true);

        final String data = m_engine.getManager( PageManager.class ).getPureText("TestPage2", WikiProvider.LATEST_VERSION );

        Assertions.assertEquals( "[TestPage](TestPage) [TestPage#anchor](TestPage#anchor) test Test [test](TestPage) [link](TestPage) [link](TestPage)", data.trim(), "wrong data" );
    }

    @Test
    public void testAttachmentChange()
        throws Exception
    {
        m_engine.saveText("TestPage", "foofoo" );
        m_engine.saveText("TestPage2", "[TestPage/foo.txt](TestPage/foo.txt) [linktext](TestPage/bar.jpg)");

        m_engine.addAttachment("TestPage", "foo.txt", "testing".getBytes() );
        m_engine.addAttachment("TestPage", "bar.jpg", "pr0n".getBytes() );
        final Page p = m_engine.getManager( PageManager.class ).getPage("TestPage");

        final Context context = Wiki.context().create(m_engine, p);

        m_engine.getManager( PageRenamer.class ).renamePage(context, "TestPage", "FooTest", true);

        final String data = m_engine.getManager( PageManager.class ).getPureText("TestPage2", WikiProvider.LATEST_VERSION);

        Assertions.assertEquals( "[FooTest/foo.txt](FooTest/foo.txt) [linktext](FooTest/bar.jpg)", data.trim(), "no rename" );

        Attachment att = m_engine.getManager( AttachmentManager.class ).getAttachmentInfo("FooTest/foo.txt");
        Assertions.assertNotNull( att, "footext" );

        att = m_engine.getManager( AttachmentManager.class ).getAttachmentInfo("FooTest/bar.jpg");
        Assertions.assertNotNull( att, "barjpg" );

        att = m_engine.getManager( AttachmentManager.class ).getAttachmentInfo("TestPage/bar.jpg");
        Assertions.assertNull( att, "testpage/bar.jpg exists" );

        att = m_engine.getManager( AttachmentManager.class ).getAttachmentInfo("TestPage/foo.txt");
        Assertions.assertNull( att, "testpage/foo.txt exists" );

        Collection< String > refs = m_engine.getManager( ReferenceManager.class ).findReferrers("TestPage/bar.jpg");

        Assertions.assertTrue( refs.isEmpty(), "oldpage" );

        refs = m_engine.getManager( ReferenceManager.class ).findReferrers( "FooTest/bar.jpg" );
        Assertions.assertEquals( 1, refs.size(), "new size" );
        Assertions.assertEquals( "TestPage2", refs.iterator().next(), "wrong ref" );
    }

    @Test
    public void testSamePage() throws Exception
    {
        m_engine.saveText( "TestPage", "[TestPage]()");

        rename( "TestPage", "FooTest" );

        final Page p = m_engine.getManager( PageManager.class ).getPage( "FooTest" );

        Assertions.assertNotNull( p, "no page" );

        Assertions.assertEquals("[FooTest]()", m_engine.getManager( PageManager.class ).getText("FooTest").trim() );
    }

    @Test
    public void testBrokenLink1() throws Exception
    {
        m_engine.saveText( "TestPage", "hubbub");
        m_engine.saveText( "TestPage2", "[TestPage|]" );

        rename( "TestPage", "FooTest" );

        final Page p = m_engine.getManager( PageManager.class ).getPage( "FooTest" );

        Assertions.assertNotNull( p, "no page" );

        // Should be no change
        Assertions.assertEquals("[TestPage|]", m_engine.getManager( PageManager.class ).getText("TestPage2").trim() );
    }

    @Test
    public void testBrokenLink2() throws Exception
    {
        m_engine.saveText( "TestPage", "hubbub");
        m_engine.saveText( "TestPage2", "[|TestPage]" );

        final Page p;
        rename( "TestPage", "FooTest" );

        p = m_engine.getManager( PageManager.class ).getPage( "FooTest" );

        Assertions.assertNotNull( p, "no page" );

        Assertions.assertEquals("[|TestPage]", m_engine.getManager( PageManager.class ).getText("TestPage2").trim() );
    }

    private void rename( final String src, final String dst ) throws WikiException
    {
        final Page p = m_engine.getManager( PageManager.class ).getPage(src);

        final Context context = Wiki.context().create(m_engine, p);

        m_engine.getManager( PageRenamer.class ).renamePage(context, src, dst, true);
    }

    @Test
    public void testBug25() throws Exception
    {
        final String src = "[Cdauth/attach.txt](Cdauth/attach.txt) [link](Cdauth/attach.txt) [cdauth](Cdauth/attach.txt)"+
                     "[CDauth/attach.txt](CDauth/attach.txt) [link](CDauth/attach.txt) [cdauth](CDauth/attach.txt)"+
                     "[cdauth/attach.txt](cdauth/attach.txt) [link](cdauth/attach.txt) [cdauth](cdauth/attach.txt)";

        final String dst = "[CdauthNew/attach.txt](CdauthNew/attach.txt) [link](CdauthNew/attach.txt) [cdauth](CdauthNew/attach.txt)"+
                     "[CDauth/attach.txt](CDauth/attach.txt) [link](CDauth/attach.txt) [cdauth](CDauth/attach.txt)"+
                     "[cdauth/attach.txt](CdauthNew/attach.txt) [link](CdauthNew/attach.txt) [cdauth](CdauthNew/attach.txt)";

        m_engine.saveText( "Cdauth", "xxx" );
        m_engine.saveText( "TestPage", src );

        m_engine.addAttachment( "Cdauth", "attach.txt", "Puppua".getBytes() );

        rename( "Cdauth", "CdauthNew" );

        Assertions.assertEquals( dst, m_engine.getManager( PageManager.class ).getText("TestPage").trim() );
    }

    @Test
    public void testBug21() throws Exception
    {
        final String src = "[Link to TestPage2](TestPage2)";

        m_engine.saveText( "TestPage", src );
        m_engine.saveText( "TestPage2", "foo" );

        rename ("TestPage2", "Test");

        Assertions.assertEquals( "[Link to Test](Test)", m_engine.getManager( PageManager.class ).getText( "TestPage" ).trim() );
    }

    @Test
    public void testExtendedLinks() throws Exception
    {
        final String src = "[Link to TestPage2](TestPage2)";

        m_engine.saveText( "TestPage", src );
        m_engine.saveText( "TestPage2", "foo" );

        rename ("TestPage2", "Test");

        Assertions.assertEquals( "[Link to Test](Test)", m_engine.getManager( PageManager.class ).getText( "TestPage" ).trim() );
    }

    @Test
    public void testBug85_case1() throws Exception
    {
        // renaming a non-existing page
        // This Assertions.fails under 2.5.116, cfr. with https://bugs.wikantik.com/show_bug.cgi?id=85
        // m_engine.saveText( "TestPage", "blablahblahbla" );
        try
        {
            rename("TestPage123", "Main8887");
            rename("Main8887", "TestPage123");
        }
        catch ( final NullPointerException npe)
        {
            npe.printStackTrace();
            System.out.println("NPE: Bug 85 caught?");
            Assertions.fail( npe );
        }
        catch( final WikiException e )
        {
            // Expected
        }
    }

    @Test
    public void testBug85_case2() throws Exception
    {
        try
        {
            // renaming a non-existing page, but we call m_engine.saveText() before renaming
            // this does not Assertions.fail under 2.5.116
            m_engine.saveText( "TestPage1234", "blablahblahbla" );
            rename("TestPage1234", "Main8887");
            rename("Main8887", "TestPage1234");
        }
        catch ( final NullPointerException npe)
        {
            npe.printStackTrace();
            System.out.println("NPE: Bug 85 caught?");
            Assertions.fail( npe );
        }
    }

    @Test
    public void testBug85_case3() throws Exception
    {
        try
        {
            // renaming an existing page
            // this does not Assertions.fail under 2.5.116
            // m_engine.saveText( "Main", "blablahblahbla" );
            rename("Main", "Main8887");
            rename("Main8887", "Main");
        }
        catch ( final NullPointerException npe)
        {
            npe.printStackTrace();
            System.out.println("NPE: Bug 85 caught?");
            Assertions.fail( npe );
        }
        catch( final WikiException e )
        {
            // Expected
        }
    }

    @Test
    public void testBug85_case4() throws Exception
    {
        try
        {
            // renaming an existing page, and we call m_engine.saveText() before renaming
            // this does not Assertions.fail under 2.5.116
            m_engine.saveText( "Main", "blablahblahbla" );
            rename("Main", "Main8887");
            rename("Main8887", "Main");
        }
        catch ( final NullPointerException npe)
        {
            npe.printStackTrace();
            System.out.println("NPE: Bug 85 caught?");
            Assertions.fail( npe );
        }
    }

    @Test
    public void testRenameOfEscapedLinks() throws Exception
    {
        final String src = "[Link to TestPage2] (TestPage2)";

        m_engine.saveText( "TestPage", src );
        m_engine.saveText( "TestPage2", "foo" );

        rename ("TestPage2", "Test");

        // Space between ] and ( means this is not a valid markdown link,
        // so the renamer should not modify it
        Assertions.assertEquals( "[Link to TestPage2] (TestPage2)", m_engine.getManager( PageManager.class ).getText( "TestPage" ).trim() );
    }

    @Test
    public void testRenameOfEscapedLinks2() throws Exception
    {
        final String src = "[Link to TestPage2}{TestPage2}";

        m_engine.saveText( "TestPage", src );
        m_engine.saveText( "TestPage2", "foo" );

        rename ("TestPage2", "Test");

        // Curly braces instead of parentheses means this is not a valid markdown link,
        // so the renamer should not modify it
        Assertions.assertEquals( "[Link to TestPage2}{TestPage2}", m_engine.getManager( PageManager.class ).getText( "TestPage" ).trim() );
    }

    /**
     * Test for a referrer containing blanks
     *
     * @throws Exception
     */
    @Test
    public void testReferrerChangeWithBlanks() throws Exception
    {
        m_engine.saveText( "TestPageReferred", "bla bla bla som content" );
        m_engine.saveText( "TestPageReferring", "[Test Page Referred](TestPageReferred)" );

        rename( "TestPageReferred", "TestPageReferredNew" );

        final String data = m_engine.getManager( PageManager.class ).getPureText( "TestPageReferring", WikiProvider.LATEST_VERSION );
        Assertions.assertEquals( "[Test Page Referred](TestPageReferredNew)", data.trim(), "page not renamed" );

        Collection< String > refs = m_engine.getManager( ReferenceManager.class ).findReferrers( "TestPageReferred" );
        Assertions.assertTrue( refs.isEmpty(), "oldpage" );

        refs = m_engine.getManager( ReferenceManager.class ).findReferrers( "TestPageReferredNew" );
        Assertions.assertEquals( 1, refs.size(), "new size" );
        Assertions.assertEquals( "TestPageReferring", refs.iterator().next(), "wrong ref" );
    }

    /** https://issues.apache.org/jira/browse/JSPWIKI-398 */
    @Test
    public void testReferrerChangeWithBlanks2() throws Exception
    {
        m_engine.saveText( "RenameTest", "[link one](LinkOne) [link two](LinkTwo)" );
        m_engine.saveText( "LinkOne", "Leonard" );
        m_engine.saveText( "LinkTwo", "Cohen" );

        rename( "LinkOne", "LinkUno" );

        final String data = m_engine.getManager( PageManager.class ).getPureText( "RenameTest", WikiProvider.LATEST_VERSION );
        Assertions.assertEquals( "[link one](LinkUno) [link two](LinkTwo)", data.trim(), "page not renamed" );

        Collection< String > refs = m_engine.getManager( ReferenceManager.class ).findReferrers( "LinkOne" );
        Assertions.assertTrue( refs.isEmpty(), "oldpage" );

        refs = m_engine.getManager( ReferenceManager.class ).findReferrers( "LinkUno" );
        Assertions.assertEquals( 1, refs.size(), "new size" );
        Assertions.assertEquals( "RenameTest", refs.iterator().next() , "wrong ref");
    }

}
