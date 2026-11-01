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
import com.wikantik.api.core.Page;
import com.wikantik.api.managers.PageManager;
import com.wikantik.api.pages.PageSaveHelper;
import com.wikantik.api.pages.SaveOptions;
import com.wikantik.api.pages.VersionConflictException;
import com.wikantik.api.providers.PageProvider;
import com.wikantik.parser.markdown.BlockIds;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.AppenderRef;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.layout.PatternLayout;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

class BlockIdServiceTest {

    private static TestEngine engine;
    private static PageManager pm;

    @BeforeAll
    static void start() {
        // A versioning provider: the optimistic-lock tests need real version numbers.
        engine = TestEngine.build( TestEngine.getTestProperties( "/wikantik-vers-custom.properties" ) );
        pm = engine.getManager( PageManager.class );
    }

    @AfterAll
    static void stop() {
        engine.stop();
    }

    @AfterEach
    void clean() {
        engine.deleteQuietly( "BidPage", "BidDerived", "BidCrlf" );
    }

    private static String text( final String page ) {
        return pm.getPureText( page, PageProvider.LATEST_VERSION );
    }

    private static int version( final String page ) {
        return Math.max( pm.getPageWithoutMetadata( page, PageProvider.LATEST_VERSION ).getVersion(), 1 );
    }

    private PageSaveHelper helper() {
        return Mockito.spy( new PageSaveHelper( engine, pm ) );
    }

    @Test
    void assignsSavesWithChangeNoteAndAuthor() throws Exception {
        engine.saveText( "BidPage", "First para.\n\nSecond para.\n" );
        final PageSaveHelper helper = helper();
        final BlockIdService.Assigned a = new BlockIdService( pm, helper )
                .assign( "BidPage", 1, 1, BlockIds.fingerprint( "Second para." ), "alice" );

        assertTrue( a.id().matches( "[a-z0-9]{6}" ) );
        assertEquals( 2, a.version() );
        assertTrue( text( "BidPage" ).contains( "Second para. ^" + a.id() ), text( "BidPage" ) );
        final ArgumentCaptor< SaveOptions > opts = ArgumentCaptor.forClass( SaveOptions.class );
        Mockito.verify( helper ).saveText( Mockito.eq( "BidPage" ), Mockito.anyString(), opts.capture() );
        assertEquals( "Added block id ^" + a.id(), opts.getValue().changeNote() );
        assertEquals( "alice", opts.getValue().author() );
        assertEquals( 1, opts.getValue().expectedVersion() );
        assertEquals( "alice", pm.getPageWithoutMetadata( "BidPage", PageProvider.LATEST_VERSION ).getAuthor() );
    }

    @Test
    void anExistingIdIsReturnedWithoutSaving() throws Exception {
        engine.saveText( "BidPage", "Tagged para. ^keep1\n\nOther.\n" );
        final PageSaveHelper helper = helper();
        final BlockIdService.Assigned a = new BlockIdService( pm, helper ).assign( "BidPage", 1, 0, "", "alice" );
        assertEquals( "keep1", a.id() );
        assertEquals( 1, a.version() );
        Mockito.verify( helper, Mockito.never() ).saveText( Mockito.anyString(), Mockito.anyString(), Mockito.any() );
        assertEquals( 1, version( "BidPage" ) );
    }

    @Test
    void anOutOfRangeIndexIsNotLocated() throws Exception {
        engine.saveText( "BidPage", "Only para.\n" );
        for ( final int bad : new int[] { 5, -1 } ) {
            final BlockIdService.Refused r = assertThrows( BlockIdService.Refused.class,
                    () -> new BlockIdService( pm, helper() ).assign( "BidPage", 1, bad, "", "a" ) );
            assertEquals( BlockIdService.Reason.NOT_LOCATED, r.reason() );
        }
    }

    @Test
    void aMissingPageIsNotLocated() {
        final BlockIdService.Refused r = assertThrows( BlockIdService.Refused.class,
                () -> new BlockIdService( pm, helper() ).assign( "BidNoSuchPage", 1, 0, "", "a" ) );
        assertEquals( BlockIdService.Reason.NOT_LOCATED, r.reason() );
    }

    @Test
    void aDerivedPageIsRefused() throws Exception {
        engine.saveText( "BidDerived", "---\nderived_from: report.pdf\n---\nExtracted para.\n" );
        final PageSaveHelper helper = helper();
        final BlockIdService.Refused r = assertThrows( BlockIdService.Refused.class,
                () -> new BlockIdService( pm, helper ).assign( "BidDerived", 1, 0, "", "a" ) );
        assertEquals( BlockIdService.Reason.DERIVED, r.reason() );
        Mockito.verify( helper, Mockito.never() ).saveText( Mockito.anyString(), Mockito.anyString(), Mockito.any() );
    }

    @Test
    void aFingerprintMismatchStillAssignsAndWarnsWithBothFingerprints() throws Exception {
        engine.saveText( "BidPage", "Actual text here.\n" );
        final List< LogEvent > events = capture();
        final BlockIdService.Assigned a = new BlockIdService( pm, helper() )
                .assign( "BidPage", 1, 0, "somethingelse", "a" );
        assertNotNull( a.id() );
        assertTrue( text( "BidPage" ).contains( "^" + a.id() ) );
        final String warn = events.stream().filter( e -> e.getLevel() == Level.WARN )
                .map( e -> e.getMessage().getFormattedMessage() ).filter( m -> m.contains( "fingerprint" ) )
                .findFirst().orElseThrow( () -> new AssertionError( "no fingerprint WARN logged" ) );
        assertTrue( warn.contains( "somethingelse" ) && warn.contains( "actualtexthere" ) && warn.contains( "BidPage" ), warn );
    }

    @Test
    void crlfPagesAreTaggedAtTheRightBlock() throws Exception {
        engine.saveText( "BidCrlf", "First para.\r\n\r\nSecond para.\r\n\r\n- item one\r\n- item two\r\n" );
        final BlockIdService.Assigned a = new BlockIdService( pm, helper() )
                .assign( "BidCrlf", 1, 1, BlockIds.fingerprint( "Second para." ), "a" );
        final String after = text( "BidCrlf" );
        assertTrue( after.contains( "Second para. ^" + a.id() + "\r\n" ), after );
        assertEquals( a.id(), BlockIds.list( after ).get( 1 ).id() );
        assertNull( BlockIds.list( after ).get( 0 ).id() );
    }

    @Test
    void aSecondAssignFromTheSameVersionIsStaleAndLeavesTheFirstSaveIntact() throws Exception {
        engine.saveText( "BidPage", "v1 first.\n\nv1 second.\n" );
        engine.saveText( "BidPage", "v2 first.\n\nv2 second.\n" );
        engine.saveText( "BidPage", "v3 first.\n\nv3 second.\n" );
        engine.saveText( "BidPage", "v4 first.\n\nv4 second.\n" );
        engine.saveText( "BidPage", "v5 first.\n\nv5 second.\n" );
        assertEquals( 5, version( "BidPage" ) );

        final BlockIdService.Assigned first = new BlockIdService( pm, new PageSaveHelper( engine, pm ) )
                .assign( "BidPage", 5, 0, "", "a" );
        assertEquals( 6, first.version() );
        final String afterFirst = text( "BidPage" );

        assertThrows( VersionConflictException.class,
                () -> new BlockIdService( pm, new PageSaveHelper( engine, pm ) ).assign( "BidPage", 5, 1, "", "b" ) );
        assertEquals( afterFirst, text( "BidPage" ) );
        assertEquals( 6, version( "BidPage" ) );
    }

    private static List< LogEvent > capture() {
        final List< LogEvent > captured = new CopyOnWriteArrayList<>();
        final LoggerContext ctx = (LoggerContext) LogManager.getContext( false );
        final Configuration config = ctx.getConfiguration();
        final AbstractAppender appender = new AbstractAppender( "BidCapture-" + System.nanoTime(), null,
                PatternLayout.createDefaultLayout(), true, null ) {
            @Override
            public void append( final LogEvent event ) {
                captured.add( event.toImmutable() );
            }
        };
        appender.start();
        config.addAppender( appender );
        final String name = BlockIdService.class.getName();
        LoggerConfig lc = config.getLoggerConfig( name );
        if ( !lc.getName().equals( name ) ) {
            lc = LoggerConfig.newBuilder().setAdditivity( false ).setLevel( Level.WARN ).setLoggerName( name )
                    .setIncludeLocation( "true" ).setRefs( new AppenderRef[ 0 ] ).setConfig( config ).build();
            config.addLogger( name, lc );
        }
        lc.setLevel( Level.WARN );
        lc.addAppender( appender, Level.WARN, null );
        ctx.updateLoggers();
        return captured;
    }
}
