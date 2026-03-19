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
import com.wikantik.api.core.Context;
import com.wikantik.api.spi.Wiki;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static com.wikantik.TestEngine.with;

public class RecentChangesPluginTest {
    static TestEngine testEngine = TestEngine.build( with( "wikantik.cache.enable", "false" ) );
    static PluginManager manager = testEngine.getManager( PluginManager.class );

    Context context;

    @BeforeEach
    public void setUp() throws Exception {
        testEngine.saveText("TestPage01", "Some Text for testing 01");
        testEngine.saveText("TestPage02", "Some Text for testing 02");
        testEngine.saveText("TestPage03", "Some Text for testing 03");
        testEngine.saveText("TestPage04", "Some Text for testing 04");
    }

    @AfterEach
    public void tearDown() {
        testEngine.deleteTestPage("TestPage01");
        testEngine.deleteTestPage("TestPage02");
        testEngine.deleteTestPage("TestPage03");
        testEngine.deleteTestPage("TestPage04");

        TestEngine.emptyWorkDir();
    }

    /**
     * Plain test without parameters
     *
     * @throws Exception
     */
    @Test
    public void testSimple() throws Exception {
        context = Wiki.context().create(testEngine, Wiki.contents().page(testEngine, "TestPage01"));

        final String res = manager.execute(context, "{INSERT com.wikantik.plugin.RecentChangesPlugin}");

        // we don't want to compare the complete html returned, but check if
        // certain Strings are present and other Strings are not present
        Assertions.assertTrue(res.contains("<table class=\"recentchanges\" cellpadding=\"4\">"));
        Assertions.assertTrue(res.contains("<a href=\"/test/wiki/TestPage01\">Test Page 01</a>"));
        Assertions.assertTrue(res.contains("<a href=\"/test/wiki/TestPage02\">Test Page 02</a>"));
        Assertions.assertTrue(res.contains("<a href=\"/test/wiki/TestPage03\">Test Page 03</a>"));
    }

    /**
     * Test with the include parameter
     *
     * @throws Exception
     */
    @Test
    public void testParmInClude() throws Exception {
        context = Wiki.context().create(testEngine, Wiki.contents().page(testEngine, "TestPage02"));

        final String res = manager.execute( context, "{INSERT com.wikantik.plugin.RecentChangesPlugin include='TestPage02*'}" );

        Assertions.assertTrue(res.contains("<table class=\"recentchanges\" cellpadding=\"4\">"));
        Assertions.assertFalse(res.contains("<a href=\"/test/wiki/TestPage01\">Test Page 01</a>"));
        Assertions.assertTrue(res.contains("<a href=\"/test/wiki/TestPage02\">Test Page 02</a>"));
        Assertions.assertFalse(res.contains("<a href=\"/test/wiki/TestPage03\">Test Page 03</a>"));
    }

    /**
     * Test with the exclude parameter
     *
     * @throws Exception
     */
    @Test
    public void testParmExClude() throws Exception {
        context = Wiki.context().create(testEngine, Wiki.contents().page(testEngine, "TestPage03"));

        final String res = manager.execute( context, "{INSERT com.wikantik.plugin.RecentChangesPlugin exclude='TestPage03*'}" );

        Assertions.assertTrue(res.contains("<table class=\"recentchanges\" cellpadding=\"4\">"));
        Assertions.assertTrue(res.contains("<a href=\"/test/wiki/TestPage01\">Test Page 01</a>"));
        Assertions.assertTrue(res.contains("<a href=\"/test/wiki/TestPage02\">Test Page 02</a>"));
        Assertions.assertFalse(res.contains("<a href=\"/test/wiki/TestPage03\">Test Page 03</a>"));
    }

    /**
     * Test that system pages are excluded from recent changes
     *
     * @throws Exception
     */
    @Test
    public void testSystemPagesExcluded() throws Exception {
        // "About" is a system page discovered from About.txt on the test classpath
        testEngine.saveText( "About", "System page content" );
        try {
            context = Wiki.context().create( testEngine, Wiki.contents().page( testEngine, "TestPage01" ) );

            final String res = manager.execute( context, "{INSERT com.wikantik.plugin.RecentChangesPlugin}" );

            // User pages should be present
            Assertions.assertTrue( res.contains( "<a href=\"/test/wiki/TestPage01\">Test Page 01</a>" ) );
            // System page "About" should be excluded
            Assertions.assertFalse( res.contains( ">About</a>" ), "System page About should be excluded from recent changes" );
        } finally {
            testEngine.deleteTestPage( "About" );
        }
    }

    /**
     * Test an empty recent changes table
     *
     * @throws Exception
     */
    @Test
    public void testNoRecentChanges() throws Exception {
        context = Wiki.context().create(testEngine, Wiki.contents().page(testEngine, "TestPage04"));

        final String res = manager.execute( context, "{INSERT com.wikantik.plugin.RecentChangesPlugin since='-1'}" );

        Assertions.assertEquals( "<table class=\"recentchanges\" cellpadding=\"4\"></table>", res );
        Assertions.assertNotEquals( "<table class=\"recentchanges\" cellpadding=\"4\" />", res );
    }

}
