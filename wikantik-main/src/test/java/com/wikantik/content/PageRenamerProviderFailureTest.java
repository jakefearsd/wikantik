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
import com.wikantik.api.core.Context;
import com.wikantik.api.exceptions.WikiException;
import com.wikantik.api.managers.PageManager;
import com.wikantik.api.providers.PageProvider;
import com.wikantik.api.spi.Wiki;
import com.wikantik.providers.TextFailingFileSystemProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A rename whose page text cannot be read changes nothing: before, the text was read after the move with
 * {@code getPureText}, which answers "" on a provider error, and the renamed page was re-saved empty. A referrer whose
 * text cannot be read is left as it is; the rename itself goes ahead.
 */
class PageRenamerProviderFailureTest {

    private static final String SOURCE = "RenameFailSource";
    private static final String TARGET = "RenameFailTarget";
    private static final String REFERRER = "RenameFailReferrer";
    private static final String TEXT = "---\ncanonical_id: 01JC0RENAMEFA1L0000000000\ncluster: finance\ntags: [a]\n"
            + "summary: Page whose text must survive\n---\n[{ALLOW view All}]\nBody with [[" + REFERRER + "]].\n";

    private TestEngine engine;

    @BeforeEach
    void start() throws Exception {
        TextFailingFileSystemProvider.FAILING.clear();
        final Properties props = TestEngine.getTestProperties();
        props.setProperty( "wikantik.pageProvider", TextFailingFileSystemProvider.class.getName() );
        props.setProperty( "wikantik.cache.enable", "false" );
        props.setProperty( "wikantik.ontology.enabled", "false" );
        engine = new TestEngine( props );
        engine.saveText( SOURCE, TEXT );
        engine.saveText( REFERRER, "See [[" + SOURCE + "]] for details.\n" );
    }

    @AfterEach
    void stop() {
        TextFailingFileSystemProvider.FAILING.clear();
        engine.deleteQuietly( SOURCE, TARGET, REFERRER );
        engine.stop();
    }

    private PageManager pages() {
        return engine.getManager( PageManager.class );
    }

    private Context context() {
        return Wiki.context().create( engine, pages().getPage( SOURCE ) );
    }

    @Test
    void aRenameWhosePageTextCannotBeReadChangesNothing() throws Exception {
        final String stored = pages().getPageText( SOURCE, PageProvider.LATEST_VERSION );
        final Context context = context();
        TextFailingFileSystemProvider.FAILING.add( SOURCE );
        TextFailingFileSystemProvider.FAILING.add( TARGET );

        final WikiException e = assertThrows( WikiException.class,
                () -> engine.getManager( PageRenamer.class ).renamePage( context, SOURCE, TARGET, true ) );
        assertTrue( e.getMessage().contains( SOURCE ), e.getMessage() );

        TextFailingFileSystemProvider.FAILING.clear();
        assertTrue( pages().pageExists( SOURCE ), "the source was not moved" );
        assertFalse( pages().pageExists( TARGET ), "no target was created" );
        assertEquals( stored, pages().getPageText( SOURCE, PageProvider.LATEST_VERSION ), "the source text is untouched" );
        assertEquals( "See [[" + SOURCE + "]] for details.\n", pages().getPageText( REFERRER, PageProvider.LATEST_VERSION ).replace( "\r\n", "\n" ),
                "no referrer was rewritten" );
    }

    @Test
    void aReferrerWhoseTextCannotBeReadIsLeftAloneAndTheRenameGoesAhead() throws Exception {
        final String stored = pages().getPageText( SOURCE, PageProvider.LATEST_VERSION );
        final String referrer = pages().getPageText( REFERRER, PageProvider.LATEST_VERSION );
        final Context context = context();
        TextFailingFileSystemProvider.FAILING.add( REFERRER );

        assertEquals( TARGET, engine.getManager( PageRenamer.class ).renamePage( context, SOURCE, TARGET, true ) );

        TextFailingFileSystemProvider.FAILING.clear();
        assertEquals( stored, pages().getPageText( TARGET, PageProvider.LATEST_VERSION ), "the renamed page keeps its text" );
        assertEquals( referrer, pages().getPageText( REFERRER, PageProvider.LATEST_VERSION ), "the unreadable referrer is unchanged" );
    }
}
