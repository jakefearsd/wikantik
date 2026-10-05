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
package com.wikantik.auth.authorize;

import com.wikantik.TestEngine;
import com.wikantik.auth.WikiPrincipal;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.AppenderRef;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.layout.PatternLayout;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Group members are matched by login name only, so a member stored as a full or wiki name silently
 * stops matching after an upgrade. {@link DefaultGroupManager#initialize} reports such members once
 * at startup: a WARN for ordinary groups and an ERROR for the {@code Admin} group.
 */
class DefaultGroupManagerStartupCheckTest {

    private TestEngine engine;
    private List< LogEvent > captured;
    private AbstractAppender appender;
    private LoggerConfig loggerConfig;
    private Level priorLevel;
    private boolean addedLoggerConfig;

    @BeforeEach
    void setUp() {
        engine = TestEngine.build();
        captured = new CopyOnWriteArrayList<>();
        final LoggerContext ctx = (LoggerContext) LogManager.getContext( false );
        final Configuration config = ctx.getConfiguration();
        appender = new AbstractAppender( "GroupStartupCheck-" + System.nanoTime(), null,
                PatternLayout.createDefaultLayout(), true, null ) {
            @Override
            public void append( final LogEvent event ) {
                captured.add( event.toImmutable() );
            }
        };
        appender.start();
        config.addAppender( appender );
        final String loggerName = GroupMemberLoginCheck.class.getName();
        loggerConfig = config.getLoggerConfig( loggerName );
        addedLoggerConfig = !loggerConfig.getName().equals( loggerName );
        if ( addedLoggerConfig ) {
            loggerConfig = LoggerConfig.newBuilder().setAdditivity( false ).setLevel( Level.WARN )
                    .setLoggerName( loggerName ).setIncludeLocation( "true" )
                    .setRefs( new AppenderRef[ 0 ] ).setConfig( config ).build();
            config.addLogger( loggerName, loggerConfig );
        }
        priorLevel = loggerConfig.getLevel();
        loggerConfig.setLevel( Level.WARN );
        loggerConfig.addAppender( appender, Level.WARN, null );
        ctx.updateLoggers();
    }

    @AfterEach
    void tearDown() {
        final LoggerContext ctx = (LoggerContext) LogManager.getContext( false );
        loggerConfig.removeAppender( appender.getName() );
        if ( addedLoggerConfig ) {
            // Leave the logging configuration exactly as this test found it.
            ctx.getConfiguration().removeLogger( loggerConfig.getName() );
        } else {
            loggerConfig.setLevel( priorLevel );
        }
        ctx.getConfiguration().getAppenders().remove( appender.getName() );
        ctx.updateLoggers();
        appender.stop();
        engine.stop();
    }

    private void initializeWith( final Group... groups ) throws Exception {
        final GroupDatabase db = mock( GroupDatabase.class );
        when( db.groups() ).thenReturn( groups );
        final DefaultGroupManager mgr = new DefaultGroupManager();
        final Field f = DefaultGroupManager.class.getDeclaredField( "groupDatabase" );
        f.setAccessible( true );
        f.set( mgr, db );
        assertDoesNotThrow( () -> mgr.initialize( engine, engine.getWikiProperties() ),
                "the check reports problems; it never fails startup" );
    }

    private static Group group( final String name, final String... members ) {
        final Group g = new Group( name, "TestWiki" );
        for ( final String m : members ) {
            g.add( new WikiPrincipal( m ) );
        }
        return g;
    }

    private List< LogEvent > at( final Level level ) {
        return captured.stream().filter( e -> e.getLevel() == level ).toList();
    }

    @Test
    void nonLoginMembersAreReportedOnceWithAdminAsError() throws Exception {
        initializeWith( group( "Admin", "admin", "Administrator" ), group( "Editors", "janne", "Janne Jalkanen" ) );

        final List< LogEvent > warns = at( Level.WARN );
        assertEquals( 1, warns.size(), "one WARN for all ordinary groups: " + captured );
        final String warn = warns.get( 0 ).getMessage().getFormattedMessage();
        assertTrue( warn.contains( "Editors" ) && warn.contains( "Janne Jalkanen" ), warn );
        assertFalse( warn.contains( "janne," ), "a login-name member is not reported: " + warn );
        assertTrue( warn.contains( "Security.md" ), warn );

        final List< LogEvent > errors = at( Level.ERROR );
        assertEquals( 1, errors.size(), "one ERROR for the Admin group: " + captured );
        final String error = errors.get( 0 ).getMessage().getFormattedMessage();
        assertTrue( error.contains( "Administrator" ) && error.contains( "Security.md" ), error );
        assertFalse( error.contains( "Janne Jalkanen" ), error );
    }

    @Test
    void loginShapedGroupsLogNothing() throws Exception {
        initializeWith( group( "Admin", "admin" ), group( "Editors", "janne" ) );

        assertTrue( at( Level.WARN ).isEmpty() && at( Level.ERROR ).isEmpty(), "unexpected: " + captured );
    }

    @Test
    void aFailingLookupIsLoggedAndNeverFailsStartupOrReportsTheMember() throws Exception {
        final com.wikantik.auth.UserManager users = mock( com.wikantik.auth.UserManager.class );
        final com.wikantik.auth.user.UserDatabase db = mock( com.wikantik.auth.user.UserDatabase.class );
        when( users.getUserDatabase() ).thenReturn( db );
        when( db.findByLoginName( org.mockito.ArgumentMatchers.anyString() ) )
                .thenThrow( new IllegalStateException( "user store unavailable" ) );

        assertDoesNotThrow( () -> GroupMemberLoginCheck.report( new Group[] { group( "Admin", "admin" ) }, users ) );

        final List< LogEvent > warns = at( Level.WARN );
        assertEquals( 1, warns.size(), "one WARN for the failed lookup: " + captured );
        assertTrue( warns.get( 0 ).getMessage().getFormattedMessage().contains( "Could not check" ),
                warns.get( 0 ).getMessage().getFormattedMessage() );
        assertTrue( at( Level.ERROR ).isEmpty(), "a member whose lookup failed is not reported as bad: " + captured );
    }

    @Test
    void noUserDatabaseSkipsTheCheck() {
        assertDoesNotThrow( () -> GroupMemberLoginCheck.report( new Group[] { group( "Admin", "Administrator" ) }, null ) );
        assertTrue( at( Level.WARN ).isEmpty() && at( Level.ERROR ).isEmpty(), "unexpected: " + captured );
    }
}
