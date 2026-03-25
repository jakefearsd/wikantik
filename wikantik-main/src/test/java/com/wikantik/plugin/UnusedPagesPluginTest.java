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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static com.wikantik.TestEngine.with;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class UnusedPagesPluginTest {

    static TestEngine testEngine = TestEngine.build( with( "wikantik.cache.enable", "false" ) );
    static PluginManager manager = testEngine.getManager( PluginManager.class );
    Context context;

    @BeforeEach
    public void setUp() throws Exception {
        // OrphanA and OrphanB are not referenced by any page — UnusedPagesPlugin must list them.
        testEngine.saveText( "OrphanA", "Orphaned page A." );
        testEngine.saveText( "OrphanB", "Orphaned page B." );
        context = Wiki.context().create( testEngine, Wiki.contents().page( testEngine, "OrphanA" ) );
    }

    @AfterEach
    public void tearDown() {
        testEngine.deleteTestPage( "OrphanA" );
        testEngine.deleteTestPage( "OrphanB" );
        TestEngine.emptyWorkDir();
    }

    @Test
    public void testMarkdownOutputIsListNotBackslashSeparated() throws Exception {
        final String html = manager.execute( context, "{INSERT com.wikantik.plugin.UnusedPagesPlugin" );

        // Must contain HTML list markup — not a run of names joined by backslash
        assertTrue( html.contains( "<li>" ) || html.contains( "<ul>" ),
                "Plugin output must be a list, got: " + html );
        assertFalse( html.contains( "\\" ),
                "Plugin output must not contain backslashes (wiki line-break artifact), got: " + html );
    }

}
