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
package com.wikantik.event;

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

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PageLifecycleListenerTest {

    private final List< String > calls = new ArrayList<>();
    private final List< String > warnings = new ArrayList<>();
    private AbstractAppender appender;
    private String loggerName;

    private class Recorder extends PageLifecycleListener {
        boolean failSave;
        boolean failRename;

        @Override protected void onSaved( final String page ) {
            calls.add( "saved:" + page );
            if ( failSave ) {
                throw new IllegalStateException( "boom" );
            }
        }
        @Override protected void onDeleted( final String page ) { calls.add( "deleted:" + page ); }
        @Override protected void onRenamed( final String from, final String to ) {
            calls.add( "renamed:" + from + ">" + to );
            if ( failRename ) {
                throw new IllegalStateException( "rename-boom" );
            }
        }
        @Override protected String label() { return "recorder"; }
    }

    @BeforeEach
    void captureWarnings() {
        appender = new AbstractAppender( "pageLifecycleListenerTest", null, null, true, Property.EMPTY_ARRAY ) {
            @Override
            public void append( final LogEvent e ) {
                warnings.add( e.getMessage().getFormattedMessage() );
            }
        };
        appender.start();
        final LoggerContext ctx = ( LoggerContext ) LogManager.getContext( false );
        final Configuration config = ctx.getConfiguration();
        loggerName = Recorder.class.getName();
        final LoggerConfig lc = LoggerConfig.newBuilder().setAdditivity( false ).setLevel( Level.WARN )
                .setLoggerName( loggerName ).setIncludeLocation( "false" ).setRefs( new AppenderRef[ 0 ] )
                .setConfig( config ).build();
        lc.addAppender( appender, Level.WARN, null );
        config.addLogger( loggerName, lc );
        ctx.updateLoggers();
    }

    @AfterEach
    void removeAppender() {
        final LoggerContext ctx = ( LoggerContext ) LogManager.getContext( false );
        ctx.getConfiguration().removeLogger( loggerName );
        ctx.updateLoggers();
        appender.stop();
    }

    @Test
    void aRenameReachesOnlyOnRenamed() {
        new Recorder().actionPerformed( new WikiPageRenameEvent( this, "Old", "New" ) );
        assertEquals( List.of( "renamed:Old>New" ), calls );
    }

    @Test
    void postSaveEndReachesOnSaved() {
        new Recorder().actionPerformed( new WikiPageEvent( this, WikiPageEvent.POST_SAVE_END, "P" ) );
        assertEquals( List.of( "saved:P" ), calls );
    }

    @Test
    void pageDeletedReachesOnDeleted() {
        new Recorder().actionPerformed( new WikiPageEvent( this, WikiPageEvent.PAGE_DELETED, "P" ) );
        assertEquals( List.of( "deleted:P" ), calls );
    }

    @Test
    void otherEventsReachNothing() {
        final Recorder r = new Recorder();
        r.actionPerformed( new WikiPageEvent( this, WikiPageEvent.POST_SAVE, "P" ) );
        r.actionPerformed( new WikiPageEvent( this, WikiPageEvent.PRE_SAVE, "P" ) );
        r.actionPerformed( new WikiEngineEvent( this, WikiEngineEvent.INITIALIZED ) );
        assertTrue( calls.isEmpty(), "calls: " + calls );
    }

    @Test
    void aThrowingHookIsLoggedOnceAndNotPropagated() {
        final Recorder r = new Recorder();
        r.failSave = true;
        assertDoesNotThrow( () -> r.actionPerformed( new WikiPageEvent( this, WikiPageEvent.POST_SAVE_END, "P" ) ) );
        assertEquals( 1, warnings.size(), "warnings: " + warnings );
        assertTrue( warnings.get( 0 ).startsWith( "recorder event handling failed for" )
                    && warnings.get( 0 ).contains( "boom" ), warnings.get( 0 ) );
    }

    @Test
    void aThrowingRenameHookIsLoggedOnceAndNotPropagated() {
        final Recorder r = new Recorder();
        r.failRename = true;
        assertDoesNotThrow( () -> r.actionPerformed( new WikiPageRenameEvent( this, "Old", "New" ) ) );
        assertEquals( List.of( "renamed:Old>New" ), calls );
        assertEquals( 1, warnings.size(), "warnings: " + warnings );
        assertTrue( warnings.get( 0 ).startsWith( "recorder event handling failed for" )
                    && warnings.get( 0 ).contains( "rename-boom" ), warnings.get( 0 ) );
    }

    @Test
    void listenToSkipsNullSources() {
        final Recorder r = new Recorder();
        final Object source = new Object();
        assertDoesNotThrow( () -> r.listenTo( null, source, null ) );
        WikiEventManager.fireEvent( source, new WikiPageEvent( source, WikiPageEvent.POST_SAVE_END, "P" ) );
        assertEquals( List.of( "saved:P" ), calls );
        WikiEventManager.removeWikiEventListener( source, r );
    }
}
