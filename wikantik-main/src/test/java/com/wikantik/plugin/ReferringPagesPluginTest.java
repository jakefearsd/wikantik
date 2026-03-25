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

import com.wikantik.HttpMockFactory;
import com.wikantik.TestEngine;
import com.wikantik.api.core.Context;
import com.wikantik.api.exceptions.PluginException;
import com.wikantik.api.spi.Wiki;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.text.SimpleDateFormat;

import static com.wikantik.TestEngine.with;

class ReferringPagesPluginTest  {

    static TestEngine engine = TestEngine.build( with( "wikantik.breakTitleWithSpaces", "false" ),
                                                 with( "wikantik.cache.enable", "false" ) );
    static PluginManager manager = engine.getManager( PluginManager.class );
    Context context;

    @BeforeEach
    void setUp() throws Exception {
        engine.saveText( "TestPage", "Reference to [Foobar]()." );
        engine.saveText( "Foobar", "Reference to [TestPage]()." );
        engine.saveText( "Foobar2", "Reference to [TestPage]()." );
        engine.saveText( "Foobar3", "Reference to [TestPage]()." );
        engine.saveText( "Foobar4", "Reference to [TestPage]()." );
        engine.saveText( "Foobar5", "Reference to [TestPage]()." );
        engine.saveText( "Foobar6", "Reference to [TestPage]()." );
        engine.saveText( "Foobar7", "Reference to [TestPage]()." );

        context = Wiki.context().create( engine, HttpMockFactory.createHttpRequest(), Wiki.contents().page( engine, "TestPage" ) );
    }

    @AfterEach
    void tearDown() {
        engine.deleteTestPage( "TestPage" );
        engine.deleteTestPage( "Foobar" );
        engine.deleteTestPage( "Foobar2" );
        engine.deleteTestPage( "Foobar3" );
        engine.deleteTestPage( "Foobar4" );
        engine.deleteTestPage( "Foobar5" );
        engine.deleteTestPage( "Foobar6" );
        engine.deleteTestPage( "Foobar7" );
    }

    private String mkLink( final String page ) {
        return mkFullLink( page, page );
    }

    private String mkFullLink( final String page, final String link ) {
        return "<a href=\"/test/wiki/"+link+"\" class=\"wikipage\">"+page+"</a>";
    }

    @Test
    void testSingleReferral() throws Exception {
        final Context context2 = Wiki.context().create( engine, Wiki.contents().page(engine, "Foobar") );
        final String res = manager.execute( context2, "{INSERT com.wikantik.plugin.ReferringPagesPlugin WHERE max=5}");
        Assertions.assertEquals( "<ul>\n<li>" + mkLink( "TestPage" ) + "</li>\n</ul>\n", res );
    }

    @Test
    void testMaxReferences() throws Exception {
        final String res = manager.execute( context, "{INSERT com.wikantik.plugin.ReferringPagesPlugin WHERE max=5}");
        int count = 0;
        int index = -1;

        // Count the number of hyperlinks.  We could check their  correctness as well, though.
        while( ( index = res.indexOf( "<a", index + 1  ) ) != -1 ) {
            count++;
        }

        // there is one extra "<a" in the result
        Assertions.assertEquals( 5 + 1, count );

        final String expected = ">...and 2 more</a>";
        count =0;
        while( ( index = res.indexOf( expected,index + 1 ) ) != -1 ) {
            count++;
        }
        Assertions.assertEquals(1, count, "End");
    }

    @Test
    void testReferenceWidth() throws Exception {
        final Context context2 = Wiki.context().create( engine, Wiki.contents().page(engine, "Foobar") );
        final String res = manager.execute( context2, "{INSERT com.wikantik.plugin.ReferringPagesPlugin WHERE maxwidth=5}");
        Assertions.assertEquals( "<ul>\n<li>" + mkFullLink( "TestPage", "TestPage" ) + "</li>\n</ul>\n", res );
    }

    @Test
    void testInclude() throws Exception {
        final String res = manager.execute( context, "{ReferringPagesPlugin include='*7'}" );

        Assertions.assertTrue( res.contains( "Foobar7" ), "7" );
        Assertions.assertEquals( res.indexOf( "Foobar6" ), -1, "6" );
        Assertions.assertEquals( res.indexOf( "Foobar5" ), -1, "5" );
        Assertions.assertEquals( res.indexOf( "Foobar4" ), -1, "4" );
        Assertions.assertEquals( res.indexOf( "Foobar3" ), -1, "3" );
        Assertions.assertEquals( res.indexOf( "Foobar2" ), -1, "2" );
    }

    @Test
    void testExclude() throws Exception {
        final String res = manager.execute( context, "{ReferringPagesPlugin exclude='*'}" );
        Assertions.assertEquals( "<p>...nobody</p>\n", res );
    }

    @Test
    void testExclude2() throws Exception {
        final String res = manager.execute( context, "{ReferringPagesPlugin exclude='*7'}" );
        Assertions.assertEquals( res.indexOf( "Foobar7" ), -1 );
    }

    @Test
    void testExclude3() throws Exception {
        final String res = manager.execute( context, "{ReferringPagesPlugin exclude='*7,*5,*4'}" );

        Assertions.assertEquals( res.indexOf( "Foobar7" ), -1, "7" );
        Assertions.assertTrue( res.contains( "Foobar6" ), "6" );
        Assertions.assertEquals( res.indexOf( "Foobar5" ), -1, "5" );
        Assertions.assertFalse( res.contains( "Foobar4" ), "4" );
        Assertions.assertTrue( res.contains( "Foobar3" ), "3" );
        Assertions.assertTrue( res.contains( "Foobar2" ), "2" );
    }

    @Test
    void testCount() throws Exception {
        String result = manager.execute(context, "{ReferringPagesPlugin show=count}");
        Assertions.assertEquals("7",result);

        result = manager.execute(context, "{ReferringPagesPlugin,exclude='*7',show=count}");
        Assertions.assertEquals("6",result);

        result = manager.execute(context, "{ReferringPagesPlugin,exclude='*7',show=count,showLastModified=true}");
        final String numberResult=result.substring(0,result.indexOf(" "));
        Assertions.assertEquals("6",numberResult);

        final String dateString = result.substring(result.indexOf("(")+1,result.indexOf(")"));
        // the date should be parseable:
        final SimpleDateFormat df = new SimpleDateFormat( "HH:mm:ss dd-MMM-yyyy zzz", HttpMockFactory.createHttpRequest().getLocale() );
        df.parse(dateString);

        // test if the proper exception is thrown:
        final String expectedExceptionString = "showLastModified=true is only valid if show=count is also specified";
        String exceptionString = null;
        try {
            manager.execute(context, "{ReferringPagesPlugin,showLastModified=true}");
        } catch ( final PluginException pe ) {
            exceptionString = pe.getMessage();
        }

        Assertions.assertEquals( expectedExceptionString, exceptionString );
    }

    @Test
    void testColumns() throws Exception {
        final String columnsWithLists = manager.execute( context, "{ReferringPagesPlugin columns=2 before='#' after='\\n'}" );
        Assertions.assertTrue( columnsWithLists.startsWith( "<div style=\"columns:2;-moz-columns:2;-webkit-columns:2;\">" ), "columnsWithLists: " + columnsWithLists );

        final String columnsWithoutLists = manager.execute( context, "{ReferringPagesPlugin columns=2}" );
        Assertions.assertTrue( columnsWithoutLists.startsWith( "<div style=\"columns:2;-moz-columns:2;-webkit-columns:2;\"><ul>\n<li><a href=\"/test/wiki/Foobar\" class=\"wikipage\">Foobar</a>" ), "columnsWithoutLists: " + columnsWithoutLists );
    }

}
