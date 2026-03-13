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
package org.apache.wiki.plugin;

import org.apache.wiki.TestEngine;
import org.apache.wiki.WikiContext;
import org.apache.wiki.api.core.Page;
import org.apache.wiki.api.spi.Wiki;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AliasPluginTest {

    private TestEngine engine;

    @BeforeEach
    void setUp() throws Exception {
        engine = TestEngine.build();
    }

    @AfterEach
    void tearDown() {
        engine.stop();
    }

    @Test
    void testAliasSetsPageAttribute() throws Exception {
        engine.saveText( "AliasTestPage", "[{ALIAS TargetPage}]" );

        final Page page = engine.getManager( org.apache.wiki.pages.PageManager.class ).getPage( "AliasTestPage" );
        final WikiContext context = new WikiContext( engine, page );

        // Rendering the page should set the alias attribute
        engine.getManager( org.apache.wiki.render.RenderingManager.class ).getHTML( context, engine.getManager( org.apache.wiki.pages.PageManager.class ).getPureText( page ) );

        assertEquals( "TargetPage", page.getAttribute( Page.ALIAS ) );
    }

    @Test
    void testAliasViaPluginManager() throws Exception {
        final PluginManager pm = engine.getManager( PluginManager.class );
        final Page page = Wiki.contents().page( engine, "TestAliasPage" );
        final WikiContext context = new WikiContext( engine, page );

        final String result = pm.execute( context, "{ALIAS 401k}" );

        assertEquals( "", result );
        assertEquals( "401k", page.getAttribute( Page.ALIAS ) );
    }
}
