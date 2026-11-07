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
package com.wikantik.collab.adapter;

import com.wikantik.TestEngine;
import com.wikantik.api.managers.PageManager;
import com.wikantik.api.pages.PageSaveHelper;
import com.wikantik.api.pages.SaveOptions;
import com.wikantik.api.pages.VersionConflictException;
import com.wikantik.collab.model.PageState;
import com.wikantik.collab.text.CollabText;
import com.wikantik.collab.model.PublishOutcome;
import com.wikantik.collab.model.PublishRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.spy;

class PageSaveHelperPageWriterTest {

    private TestEngine engine;
    private PageSaveHelperPageWriter writer;
    private PageSaveHelper helper;

    @BeforeEach
    void setUp() {
        // The versioning provider: the default test provider reports every version as -1.
        engine = TestEngine.build( TestEngine.getTestProperties( "/wikantik-vers-custom.properties" ) );
        final PageManager pm = engine.getManager( PageManager.class );
        helper = new PageSaveHelper( engine, pm );
        writer = new PageSaveHelperPageWriter( pm, helper );
    }

    @AfterEach
    void tearDown() {
        engine.deleteTestPage( "CollabW" );
        engine.stop();
    }

    private static PublishRequest req( final String body, final Map< String, Object > meta, final int expected ) {
        return new PublishRequest( "CollabW", body, meta, expected, "alice", "note" );
    }

    @Test
    void latestOfMissingPageIsEmpty() {
        assertEquals( Optional.empty(), writer.latest( "CollabW" ) );
        assertEquals( Optional.empty(), writer.version( "CollabW", 1 ) );
    }

    @Test
    void newPagePublishWritesVersionOneAndLfBody() throws Exception {
        final PublishOutcome out = writer.publish( req( "line one\nline two\n", Map.of( "type", "note" ), 0 ) );
        assertEquals( 1, out.version() );
        assertFalse( out.unchanged() );
        assertFalse( out.savedBody().contains( "\r" ) );
        assertTrue( out.savedBody().startsWith( "line one\nline two" ) );
        assertEquals( "note", out.savedMeta().get( "type" ) );
        final PageState latest = writer.latest( "CollabW" ).orElseThrow();
        assertEquals( 1, latest.version() );
        assertEquals( out.savedBody(), latest.body() );
        assertEquals( "note", latest.meta().get( "type" ) );
        assertFalse( latest.contentHash().isBlank() );
    }

    @Test
    void secondPublishReturnsTheWrittenVersionAndVersionLookupWorks() throws Exception {
        writer.publish( req( "one\n", Map.of(), 0 ) );
        final PublishOutcome out = writer.publish( req( "two\n", Map.of(), 1 ) );
        assertEquals( 2, out.version() );
        assertTrue( writer.version( "CollabW", 1 ).orElseThrow().body().startsWith( "one" ) );
        assertEquals( 2, writer.version( "CollabW", 2 ).orElseThrow().version() );
    }

    @Test
    void identicalTextIsUnchanged() throws Exception {
        final PublishOutcome first = writer.publish( req( "same\n", Map.of(), 0 ) );
        final PublishOutcome again = writer.publish( req( first.savedBody(), first.savedMeta(), 1 ) );
        assertTrue( again.unchanged() );
        assertEquals( 1, again.version() );
    }

    @Test
    void staleExpectedVersionIsAConflict() throws Exception {
        writer.publish( req( "one\n", Map.of(), 0 ) );
        helper.saveText( "CollabW", "external", SaveOptions.builder().author( "bob" ).build() );
        assertThrows( VersionConflictException.class, () -> writer.publish( req( "mine\n", Map.of(), 1 ) ) );
    }

    @Test
    void newPageOverAnExistingOneIsAConflict() throws Exception {
        writer.publish( req( "one\n", Map.of(), 0 ) );
        assertThrows( VersionConflictException.class, () -> writer.publish( req( "again\n", Map.of(), 0 ) ) );
        assertEquals( 1, writer.latest( "CollabW" ).orElseThrow().version() );
    }

    @Test
    void pageCreatedJustBeforeTheSaveWinsOverANewPagePublish() throws Exception {
        final PageManager real = engine.getManager( PageManager.class );
        final PageManager spied = spy( real );
        final PageSaveHelper spiedHelper = new PageSaveHelper( engine, spied );
        final AtomicBoolean intruded = new AtomicBoolean();
        // Another user creates the page after the writer has been invoked, just before the guarded save runs.
        doAnswer( inv -> {
            if( intruded.compareAndSet( false, true ) ) {
                helper.saveText( "CollabW", "other user's text", SaveOptions.builder().author( "bob" ).build() );
            }
            return inv.callRealMethod();
        } ).when( spied ).saveText( any(), any(), any( com.wikantik.api.pages.SaveGuard.class ) );
        final PageSaveHelperPageWriter racing = new PageSaveHelperPageWriter( spied, spiedHelper );

        assertThrows( VersionConflictException.class, () -> racing.publish( req( "mine\n", Map.of(), 0 ) ) );
        assertTrue( intruded.get() );
        assertTrue( engine.getManager( PageManager.class ).getPureText( "CollabW", -1 ).contains( "other user's text" ) );
    }

    @Test
    void contentHashIsOverTheLfBodyEvenWithFrontmatterAndCrlfStorage() throws Exception {
        helper.saveText( "CollabW", "---\r\ntype: note\r\n---\r\nline one\r\nline two\r\n",
                SaveOptions.builder().author( "bob" ).build() );
        final PageState latest = writer.latest( "CollabW" ).orElseThrow();
        assertFalse( latest.body().contains( "\r" ) );
        assertEquals( CollabText.contentHash( latest.body() ), latest.contentHash() );
        final PublishOutcome out = writer.publish( req( latest.body() + "more\n", latest.meta(), 1 ) );
        final PageState after = writer.latest( "CollabW" ).orElseThrow();
        assertEquals( CollabText.contentHash( out.savedBody() ), after.contentHash() );
    }
}
