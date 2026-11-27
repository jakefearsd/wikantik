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
import org.apache.logging.log4j.core.config.Property;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Captures the {@code SecurityLog} audit lines ({@code MCP_WRITE ...}) a tool writes while an action runs. */
final class SecurityLogCapture extends AbstractAppender {

    private static final String LOGGER = "SecurityLog";

    private final List< String > lines = new CopyOnWriteArrayList<>();

    private SecurityLogCapture() {
        super( "SecurityLogCapture", null, null, true, Property.EMPTY_ARRAY );
    }

    @Override
    public void append( final LogEvent event ) {
        final String message = event.getMessage().getFormattedMessage();
        if ( message.startsWith( "MCP_WRITE" ) ) {
            lines.add( message );
        }
    }

    /** The {@code MCP_WRITE} audit messages written to the security logger while {@code action} ran. */
    static List< String > during( final Runnable action ) {
        final SecurityLogCapture capture = new SecurityLogCapture();
        final LoggerContext ctx = ( LoggerContext ) org.apache.logging.log4j.LogManager.getContext( false );
        final LoggerConfig config = ctx.getConfiguration().getLoggerConfig( LOGGER );
        final Level previous = config.getLevel();
        capture.start();
        config.addAppender( capture, Level.ALL, null );
        config.setLevel( Level.ALL );
        ctx.updateLoggers();
        try {
            action.run();
        } finally {
            config.removeAppender( capture.getName() );
            config.setLevel( previous );
            ctx.updateLoggers();
            capture.stop();
        }
        return capture.lines;
    }
}
