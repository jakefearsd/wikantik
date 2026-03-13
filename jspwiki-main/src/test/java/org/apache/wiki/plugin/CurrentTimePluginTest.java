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
import org.apache.wiki.api.core.Context;
import org.apache.wiki.api.exceptions.PluginException;
import org.apache.wiki.api.spi.Wiki;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

import static org.apache.wiki.TestEngine.with;
import static org.junit.jupiter.api.Assertions.*;

public class CurrentTimePluginTest {

    static TestEngine testEngine = TestEngine.build( with( "jspwiki.cache.enable", "false" ) );
    static PluginManager manager = testEngine.getManager( PluginManager.class );

    Context context;

    @BeforeEach
    public void setUp() throws Exception {
        context = Wiki.context().create( testEngine, Wiki.contents().page( testEngine, "TestPage" ) );
    }

    @Test
    public void testDefaultFormat() throws Exception {
        final String res = manager.execute( context, "{INSERT CurrentTimePlugin}" );
        assertNotNull( res );
        assertFalse( res.isEmpty() );
    }

    @Test
    public void testCustomFormat() throws Exception {
        final String res = manager.execute( context, "{INSERT CurrentTimePlugin format='yyyy-MM-dd'}" );
        final String today = LocalDate.now().format( DateTimeFormatter.ofPattern( "yyyy-MM-dd" ) );
        assertEquals( today, res );
    }

    @Test
    public void testTimezoneFormat() throws Exception {
        final String res = manager.execute( context, "{INSERT CurrentTimePlugin format=zzzz}" );
        assertNotNull( res );
        assertFalse( res.isEmpty() );
    }

    @Test
    public void testBadFormatThrows() {
        assertThrows( PluginException.class, () ->
                manager.execute( context, "{INSERT CurrentTimePlugin format='INVALID_FORMAT_STRING'}" ) );
    }
}
