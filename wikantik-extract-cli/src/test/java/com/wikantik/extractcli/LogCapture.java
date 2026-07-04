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
package com.wikantik.extractcli;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.config.Property;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

/**
 * Minimal in-memory log4j2 appender, attached programmatically to a named
 * logger for the duration of a test and detached in {@code finally}. Several
 * of the CLIs in this module only surface their computed results (stats,
 * progress, warnings) via {@code LOG.info}/{@code LOG.warn} rather than a
 * return value, so asserting on captured log messages is the only way to
 * pin that behavior without touching production code.
 *
 * <p>Usage:</p>
 * <pre>{@code
 * final LogCapture cap = LogCapture.attach( ChunkerStatsCli.class );
 * try {
 *     ChunkerStatsCli.run( args );
 *     assertTrue( cap.messages().stream().anyMatch( m -> m.contains( "pages=3" ) ) );
 * } finally {
 *     cap.detach();
 * }
 * }</pre>
 */
final class LogCapture extends AbstractAppender {

    private final List< LogEvent > events = new CopyOnWriteArrayList<>();
    private final String loggerName;
    private final LoggerConfig loggerConfig;
    private final Level previousLevel;

    private LogCapture( final String loggerName, final LoggerConfig loggerConfig, final Level previousLevel ) {
        super( "LogCapture-" + loggerName, null, null, true, Property.EMPTY_ARRAY );
        this.loggerName = loggerName;
        this.loggerConfig = loggerConfig;
        this.previousLevel = previousLevel;
    }

    static LogCapture attach( final Class< ? > loggerClass ) {
        final String name = loggerClass.getName();
        final LoggerContext ctx = ( LoggerContext ) LogManager.getContext( false );
        final LoggerConfig config = ctx.getConfiguration().getLoggerConfig( name );
        final Level previous = config.getLevel();
        final LogCapture appender = new LogCapture( name, config, previous );
        appender.start();
        config.addAppender( appender, Level.ALL, null );
        config.setLevel( Level.ALL );
        ctx.updateLoggers();
        return appender;
    }

    void detach() {
        loggerConfig.removeAppender( getName() );
        loggerConfig.setLevel( previousLevel );
        final LoggerContext ctx = ( LoggerContext ) LogManager.getContext( false );
        ctx.updateLoggers();
        stop();
    }

    List< String > messages() {
        return events.stream().map( e -> e.getMessage().getFormattedMessage() ).collect( Collectors.toList() );
    }

    @Override
    public void append( final LogEvent event ) {
        events.add( event.toImmutable() );
    }
}
