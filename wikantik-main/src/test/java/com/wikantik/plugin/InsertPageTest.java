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
package com.wikantik.plugin;

import com.wikantik.TestEngine;
import com.wikantik.HttpMockFactory;
import com.wikantik.WikiSessionTest;
import com.wikantik.api.core.Context;
import com.wikantik.api.core.Page;
import com.wikantik.api.core.Session;
import com.wikantik.api.managers.PageManager;
import com.wikantik.api.spi.Wiki;
import com.wikantik.auth.AuthenticationManager;
import com.wikantik.auth.permissions.PermissionFilter;
import com.wikantik.auth.Users;
import com.wikantik.render.RenderingManager;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.mockito.Mockito;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;


public class InsertPageTest {

    static protected TestEngine testEngine = TestEngine.build();

    @AfterEach
    public void tearDown() throws Exception
    {
        testEngine.deleteTestPage( "ThisPage" );
        testEngine.deleteTestPage( "ThisPage2" );
        testEngine.deleteTestPage( "Test_Page" );
        testEngine.deleteTestPage( "TestPage" );
        testEngine.deleteTestPage( "Test Page" );
    }

    @Test
    public void insertingARestrictedPageDoesNotChangeHostAcl() throws Exception {
        testEngine.saveText( "InsHostPlain", "intro [{InsertPage page='InsSecret'}]" );
        testEngine.saveText( "InsSecret", "[{ALLOW view Admin}]\n\nclassified" );
        final PermissionFilter pf = new PermissionFilter( testEngine );
        final Session guest = WikiSessionTest.anonymousSession( testEngine );
        Assertions.assertTrue( pf.canAccessQuietly( guest, "InsHostPlain", "view" ) );

        final String html = renderHostAsAdmin( "InsHostPlain" );

        Assertions.assertTrue( html.contains( "classified" ), html );
        Assertions.assertTrue( pf.canAccessQuietly( guest, "InsHostPlain", "view" ),
                "InsertPage must not write the inserted page's ACL onto the host" );
        testEngine.deleteTestPage( "InsHostPlain" );
        testEngine.deleteTestPage( "InsSecret" );
    }

    @Test
    public void insertingAnOpenPageCannotWidenHostAcl() throws Exception {
        testEngine.saveText( "InsHostLocked", "[{ALLOW view Admin}]\n\n[{InsertPage page='InsOpen'}]" );
        testEngine.saveText( "InsOpen", "[{ALLOW view All}]\n\nopen text" );
        final PermissionFilter pf = new PermissionFilter( testEngine );
        final Session guest = WikiSessionTest.anonymousSession( testEngine );
        Assertions.assertFalse( pf.canAccessQuietly( guest, "InsHostLocked", "view" ) );

        renderHostAsAdmin( "InsHostLocked" );

        Assertions.assertFalse( pf.canAccessQuietly( guest, "InsHostLocked", "view" ),
                "an inserted page's ACL must not widen the host's ACL" );
        testEngine.deleteTestPage( "InsHostLocked" );
        testEngine.deleteTestPage( "InsOpen" );
    }

    private String renderHostAsAdmin( final String name ) throws Exception {
        final HttpServletRequest request = HttpMockFactory.createHttpRequest();
        final HttpSession http = Mockito.mock( HttpSession.class );
        Mockito.doReturn( "acl-admin-" + System.nanoTime() ).when( http ).getId();
        Mockito.doReturn( http ).when( request ).getSession();
        final Session admin = Wiki.session().find( testEngine, request );
        testEngine.getManager( AuthenticationManager.class ).login( admin, request, Users.ADMIN, Users.ADMIN_PASS );
        Assertions.assertTrue( admin.isAuthenticated() );
        final Page host = testEngine.getManager( PageManager.class ).getPage( name );
        final Context ctx = Wiki.context().create( testEngine, request, host );
        return testEngine.getManager( RenderingManager.class )
                .textToHTML( ctx, testEngine.getManager( PageManager.class ).getPureText( name, -1 ) );
    }

    @Test
    public void testRecursive() throws Exception
    {
        final String src = "[{InsertPage page='ThisPage'}] [{ALLOW view Anonymous}]";

        testEngine.saveText("ThisPage",src);

        // Just check that it contains a proper error message; don't bother do HTML checking.
        final String res = testEngine.getManager( RenderingManager.class ).getHTML("ThisPage");
        Assertions.assertTrue( res.contains( "Circular reference" ) );
    }

    @Test
    public void testRecursive2() throws Exception
    {
        final String src  = "[{InsertPage page='ThisPage2'}]";
        final String src2 = "[{InsertPage page='ThisPage'}]";

        testEngine.saveText("ThisPage",src);
        testEngine.saveText("ThisPage2",src2);

        // Just check that it contains a proper error message; don't bother do HTML checking.
        Assertions.assertTrue( testEngine.getManager( RenderingManager.class ).getHTML( "ThisPage" ).contains( "Circular reference" ) );
    }

    @Test
    public void testMultiInvocation() throws Exception {
        final String src  = "[{InsertPage page='ThisPage2'}] [{InsertPage page='ThisPage2'}]";
        final String src2 = "foo[{ALLOW view Anonymous}]";

        testEngine.saveText("ThisPage",src);
        testEngine.saveText("ThisPage2",src2);

        Assertions.assertEquals( -1, testEngine.getManager(RenderingManager.class).getHTML("ThisPage").indexOf("Circular reference"), "got circ ref" );
        final String result = testEngine.getManager( RenderingManager.class ).getHTML("ThisPage");
        Assertions.assertTrue( result.contains( "inserted-page" ), "found != 2" );
        Assertions.assertFalse( result.contains( "Circular reference" ), "should not have circular ref" );
    }

    @Test
    public void testUnderscore() throws Exception {
        final String src  = "[{InsertPage page='Test_Page'}]";
        final String src2 = "foo[{ALLOW view Anonymous}]";

        testEngine.saveText("ThisPage",src);
        testEngine.saveText("Test_Page",src2);

        Assertions.assertEquals( -1, testEngine.getManager(RenderingManager.class).getHTML("ThisPage").indexOf("Circular reference"), "got circ ref" );
        Assertions.assertTrue( testEngine.getManager( RenderingManager.class ).getHTML("ThisPage").contains( "inserted-page" ), "found != 1" );
    }

    /**
     * a link containing a blank should work if there is a page with exact the
     * same name ('Test Page')
     */
    @Test
    public void testWithBlanks1() throws Exception {
        testEngine.saveText( "ThisPage", "[{InsertPage page='Test Page'}]" );
        testEngine.saveText( "Test Page", "foo[{ALLOW view Anonymous}]" );

        final String result = testEngine.getManager( RenderingManager.class ).getHTML( "ThisPage" );
        Assertions.assertTrue( result.contains( "inserted-page" ), "found != 1" );
        Assertions.assertTrue( result.contains( "foo" ), "should contain foo" );
    }

    /**
     * same as testWithBlanks1, but it should still work if the page does not have the blank in it ( 'Test Page' should work if the
     * included page is called 'TestPage')
     */
    @Test
    public void testWithBlanks2() throws Exception {
        testEngine.saveText( "ThisPage", "[{InsertPage page='Test Page'}]" );
        testEngine.saveText( "TestPage", "foo[{ALLOW view Anonymous}]" );

        final String result = testEngine.getManager( RenderingManager.class ).getHTML( "ThisPage" );
        Assertions.assertTrue( result.contains( "inserted-page" ), "found != 1" );
        Assertions.assertTrue( result.contains( "foo" ), "should contain foo" );
    }

}
