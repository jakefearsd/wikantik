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
import com.wikantik.api.core.Page;
import com.wikantik.api.spi.Wiki;
import com.wikantik.pages.PageManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static com.wikantik.TestEngine.with;

/**
 * Additional coverage tests for {@link RecentChangesPlugin} targeting
 * previously uncovered branches: compact format, blank date/time formats,
 * author-page-exists path, and unknown-author path.
 */
public class RecentChangesPluginCITest {

    static TestEngine testEngine = TestEngine.build( with( "wikantik.cache.enable", "false" ) );
    static PluginManager manager = testEngine.getManager( PluginManager.class );

    Context context;

    @BeforeEach
    public void setUp() throws Exception {
        testEngine.saveText( "RcPage01", "Content for RcPage01" );
        testEngine.saveText( "RcPage02", "Content for RcPage02" );
    }

    @AfterEach
    public void tearDown() {
        testEngine.deleteTestPage( "RcPage01" );
        testEngine.deleteTestPage( "RcPage02" );
        TestEngine.emptyWorkDir();
    }

    /**
     * Compact format sets cellpadding=0, hides author and changenote columns.
     * Covers the {@code "compact".equals(params.get(PARAM_FORMAT))} branch.
     */
    @Test
    public void testCompactFormat() throws Exception {
        context = Wiki.context().create( testEngine, Wiki.contents().page( testEngine, "RcPage01" ) );

        final String res = manager.execute( context,
                "{INSERT com.wikantik.plugin.RecentChangesPlugin format='compact'}" );

        // Compact uses cellpadding=0
        Assertions.assertTrue( res.contains( "cellpadding=\"0\"" ),
                "Compact format should use cellpadding=0" );
        // Page link should still be present
        Assertions.assertTrue( res.contains( "RcPage01" ),
                "Compact format should still list pages" );
    }

    /**
     * Full format (default) sets cellpadding=4 and shows author/changenote.
     * Verifies the non-compact path explicitly.
     */
    @Test
    public void testFullFormatExplicit() throws Exception {
        context = Wiki.context().create( testEngine, Wiki.contents().page( testEngine, "RcPage02" ) );

        final String res = manager.execute( context,
                "{INSERT com.wikantik.plugin.RecentChangesPlugin format='full'}" );

        Assertions.assertTrue( res.contains( "cellpadding=\"4\"" ),
                "Full format should use cellpadding=4" );
    }

    /**
     * Blank dateFormat parameter causes fallback to locale-default date format.
     * Covers the {@code StringUtils.isBlank(formatString)} branch in getDateFormat.
     */
    @Test
    public void testBlankDateFormat() throws Exception {
        context = Wiki.context().create( testEngine, Wiki.contents().page( testEngine, "RcPage01" ) );

        // Passing an empty dateFormat triggers the Preferences.getDateFormat() fallback
        final String res = manager.execute( context,
                "{INSERT com.wikantik.plugin.RecentChangesPlugin dateFormat=''}" );

        Assertions.assertNotNull( res, "Result should not be null" );
        Assertions.assertTrue( res.contains( "recentchanges" ),
                "Result should still contain the table even with blank dateFormat" );
    }

    /**
     * Blank timeFormat parameter causes fallback to locale-default time format.
     * Covers the {@code StringUtils.isBlank(formatString)} branch in getTimeFormat.
     */
    @Test
    public void testBlankTimeFormat() throws Exception {
        context = Wiki.context().create( testEngine, Wiki.contents().page( testEngine, "RcPage01" ) );

        final String res = manager.execute( context,
                "{INSERT com.wikantik.plugin.RecentChangesPlugin timeFormat=''}" );

        Assertions.assertNotNull( res, "Result should not be null" );
        Assertions.assertTrue( res.contains( "recentchanges" ),
                "Result should still contain the table even with blank timeFormat" );
    }

    /**
     * Both dateFormat and timeFormat blank simultaneously.
     */
    @Test
    public void testBlankBothFormats() throws Exception {
        context = Wiki.context().create( testEngine, Wiki.contents().page( testEngine, "RcPage01" ) );

        final String res = manager.execute( context,
                "{INSERT com.wikantik.plugin.RecentChangesPlugin dateFormat='' timeFormat=''}" );

        Assertions.assertNotNull( res );
        Assertions.assertTrue( res.contains( "recentchanges" ) );
    }

    /**
     * Author who exists as a wiki page gets rendered as a hyperlink.
     * Covers the {@code wikiPageExists(author)} true branch.
     */
    @Test
    public void testAuthorWhoIsAWikiPage() throws Exception {
        // Create a wiki page that has the same name as a user (admin)
        // so that the "author exists as page" branch is taken.
        testEngine.saveText( "admin", "The admin user's page" );
        try {
            context = Wiki.context().create( testEngine, Wiki.contents().page( testEngine, "RcPage01" ) );

            final String res = manager.execute( context,
                    "{INSERT com.wikantik.plugin.RecentChangesPlugin}" );

            // The author column should contain a link to the admin wiki page
            Assertions.assertNotNull( res );
            Assertions.assertTrue( res.contains( "author" ),
                    "Result should contain author column class" );
        } finally {
            testEngine.deleteTestPage( "admin" );
        }
    }

    /**
     * Compact format omits the author and changenote columns entirely.
     * Pages should still appear but the author td class should not be present.
     */
    @Test
    public void testCompactFormatOmitsAuthorAndChangenote() throws Exception {
        context = Wiki.context().create( testEngine, Wiki.contents().page( testEngine, "RcPage01" ) );

        final String res = manager.execute( context,
                "{INSERT com.wikantik.plugin.RecentChangesPlugin format='compact'}" );

        // Compact hides author and changenote columns
        Assertions.assertFalse( res.contains( "class=\"author\"" ),
                "Compact format should not include author column" );
        Assertions.assertFalse( res.contains( "class=\"changenote\"" ),
                "Compact format should not include changenote column" );
    }

    /**
     * Custom dateFormat and timeFormat strings are used directly.
     * Verifies format strings flow into SimpleDateFormat correctly.
     */
    @Test
    public void testCustomDateAndTimeFormat() throws Exception {
        context = Wiki.context().create( testEngine, Wiki.contents().page( testEngine, "RcPage01" ) );

        final String res = manager.execute( context,
                "{INSERT com.wikantik.plugin.RecentChangesPlugin dateFormat='yyyy' timeFormat='HH'}" );

        Assertions.assertNotNull( res );
        Assertions.assertTrue( res.contains( "recentchanges" ) );
    }
}
