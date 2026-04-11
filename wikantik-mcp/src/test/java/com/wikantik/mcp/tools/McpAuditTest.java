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
package com.wikantik.mcp.tools;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

class McpAuditTest {

    @Test
    void logWriteEmitsMcpWriteLineOnSecurityLogger() {
        final CapturingAppender appender = CapturingAppender.attach( "SecurityLog" );
        try {
            McpAudit.logWrite( "import_content", "updated", "SomePage", "alice" );
        } finally {
            appender.detach();
        }

        assertEquals( 1, appender.events.size() );
        final String message = appender.events.get( 0 ).getMessage().getFormattedMessage();
        assertTrue( message.contains( "MCP_WRITE" ), message );
        assertTrue( message.contains( "tool=import_content" ), message );
        assertTrue( message.contains( "action=updated" ), message );
        assertTrue( message.contains( "target=SomePage" ), message );
        assertTrue( message.contains( "author=alice" ), message );
    }

    @Test
    void logWriteHandlesNullAuthor() {
        final CapturingAppender appender = CapturingAppender.attach( "SecurityLog" );
        try {
            McpAudit.logWrite( "delete_page", "deleted", "OldPage", null );
        } finally {
            appender.detach();
        }

        final String message = appender.events.get( 0 ).getMessage().getFormattedMessage();
        assertTrue( message.contains( "author=unknown" ), message );
    }

    /**
     * Minimal log4j appender that captures events in memory. Attached programmatically
     * to a named logger for the duration of a test and detached in finally.
     */
    private static final class CapturingAppender extends AbstractAppender {
        final List< LogEvent > events = new CopyOnWriteArrayList<>();
        private final String loggerName;
        private final LoggerConfig loggerConfig;
        private final Level previousLevel;

        private CapturingAppender( final String loggerName, final LoggerConfig loggerConfig, final Level previousLevel ) {
            super( "CapturingAppender-" + loggerName, null, null, true, org.apache.logging.log4j.core.config.Property.EMPTY_ARRAY );
            this.loggerName = loggerName;
            this.loggerConfig = loggerConfig;
            this.previousLevel = previousLevel;
        }

        static CapturingAppender attach( final String loggerName ) {
            final LoggerContext ctx = ( LoggerContext ) org.apache.logging.log4j.LogManager.getContext( false );
            final LoggerConfig config = ctx.getConfiguration().getLoggerConfig( loggerName );
            final Level previous = config.getLevel();
            final CapturingAppender appender = new CapturingAppender( loggerName, config, previous );
            appender.start();
            config.addAppender( appender, Level.ALL, null );
            config.setLevel( Level.ALL );
            ctx.updateLoggers();
            return appender;
        }

        void detach() {
            loggerConfig.removeAppender( getName() );
            loggerConfig.setLevel( previousLevel );
            final LoggerContext ctx = ( LoggerContext ) org.apache.logging.log4j.LogManager.getContext( false );
            ctx.updateLoggers();
            stop();
        }

        @Override
        public void append( final LogEvent event ) {
            events.add( event.toImmutable() );
        }
    }
}
