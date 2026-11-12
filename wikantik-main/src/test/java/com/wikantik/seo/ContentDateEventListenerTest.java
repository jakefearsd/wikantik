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
package com.wikantik.seo;

import com.wikantik.api.core.Page;
import com.wikantik.api.managers.PageManager;
import com.wikantik.api.providers.WikiProvider;
import com.wikantik.event.WikiPageEvent;
import com.wikantik.event.WikiPageRenameEvent;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.AppenderRef;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.config.Property;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link ContentDateEventListener} is the only thing that routes saves to the content-date service: a wiring
 * regression would silently freeze every page's date, so the routing is pinned here.
 */
class ContentDateEventListenerTest {

    private static final String PAGE = "DatedPage";
    private static final Instant SAVED_AT = Instant.parse( "2026-11-01T10:00:00Z" );

    private final List< String > warnings = new ArrayList<>();
    private AbstractAppender appender;

    @BeforeEach
    void captureWarnings() {
        appender = new AbstractAppender( "contentDateListenerTest", null, null, true, Property.EMPTY_ARRAY ) {
            @Override
            public void append( final LogEvent e ) {
                warnings.add( e.getMessage().getFormattedMessage() );
            }
        };
        appender.start();
        final LoggerContext ctx = ( LoggerContext ) LogManager.getContext( false );
        final Configuration config = ctx.getConfiguration();
        final String name = ContentDateEventListener.class.getName();
        final LoggerConfig lc = LoggerConfig.newBuilder().setAdditivity( false ).setLevel( Level.WARN )
                .setLoggerName( name ).setIncludeLocation( "false" ).setRefs( new AppenderRef[ 0 ] )
                .setConfig( config ).build();
        lc.addAppender( appender, Level.WARN, null );
        config.addLogger( name, lc );
        ctx.updateLoggers();
    }

    @AfterEach
    void releaseWarnings() {
        final LoggerContext ctx = ( LoggerContext ) LogManager.getContext( false );
        ctx.getConfiguration().removeLogger( ContentDateEventListener.class.getName() );
        ctx.updateLoggers();
        appender.stop();
    }

    @Test
    void aSaveReachesTheServiceWhichComparesTheBaselineAndTheCurrentTextOfThatPage() {
        final PageManager pm = mock( PageManager.class );
        final ContentDateRepository repo = mock( ContentDateRepository.class );
        final Page current = mock( Page.class );
        when( current.getName() ).thenReturn( PAGE );
        when( current.getVersion() ).thenReturn( 2 );
        when( current.getLastModified() ).thenReturn( Date.from( SAVED_AT ) );
        when( pm.getPage( PAGE ) ).thenReturn( current );
        when( repo.find( PAGE ) ).thenReturn( Optional.of( new ContentDate( PAGE, Instant.EPOCH, 1 ) ) );
        when( pm.getPureText( PAGE, 1 ) ).thenReturn( "one two three" );
        when( pm.getPureText( PAGE, WikiProvider.LATEST_VERSION ) ).thenReturn( "an entirely rewritten body of text" );
        final ContentDateEventListener listener =
                new ContentDateEventListener( new ContentDateService( pm, repo, new ContentChangeRule( 5, 1 ) ) );

        listener.actionPerformed( new WikiPageEvent( this, WikiPageEvent.POST_SAVE_END, PAGE ) );

        verify( pm ).getPureText( PAGE, 1 );
        verify( pm ).getPureText( PAGE, WikiProvider.LATEST_VERSION );
        verify( repo ).upsert( new ContentDate( PAGE, SAVED_AT, 2 ) );
    }

    @Test
    void theBarePostSaveIsIgnoredSoASaveIsHandledOnce() {
        final ContentDateService service = mock( ContentDateService.class );
        new ContentDateEventListener( service ).actionPerformed( new WikiPageEvent( this, WikiPageEvent.POST_SAVE, PAGE ) );
        verify( service, never() ).onPageSaved( anyString() );
    }

    @Test
    void deletesAndRenamesAreForwarded() {
        final ContentDateService service = mock( ContentDateService.class );
        final ContentDateEventListener listener = new ContentDateEventListener( service );

        listener.actionPerformed( new WikiPageEvent( this, WikiPageEvent.PAGE_DELETED, PAGE ) );
        listener.actionPerformed( new WikiPageRenameEvent( this, PAGE, "RenamedPage" ) );

        verify( service ).onPageDeleted( PAGE );
        verify( service ).onPageRenamed( PAGE, "RenamedPage" );
    }

    @Test
    void aFailureInTheServiceIsLoggedNotThrown() {
        final ContentDateService service = mock( ContentDateService.class );
        doThrow( new IllegalStateException( "date store down" ) ).when( service ).onPageSaved( any() );
        final ContentDateEventListener listener = new ContentDateEventListener( service );

        assertDoesNotThrow( () -> listener.actionPerformed( new WikiPageEvent( this, WikiPageEvent.POST_SAVE_END, PAGE ) ),
                "a content-date failure must never fail the save that triggered it" );
        assertTrue( warnings.stream().anyMatch( w -> w.contains( "content date event handling failed" )
                                                     && w.contains( "date store down" ) ), "logged: " + warnings );
    }
}
