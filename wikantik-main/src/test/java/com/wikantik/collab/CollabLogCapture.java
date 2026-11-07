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
package com.wikantik.collab;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.AppenderRef;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.config.Property;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Captures the collab package's WARN and ERROR log events for the registry's tick log tests. */
final class CollabLogCapture {

    private static final String PACKAGE = "com.wikantik.collab";

    private CollabLogCapture() {
    }

    /** The WARN and ERROR events the collab package logged while {@code r} ran. */
    static List< LogEvent > warnings( final Runnable r ) {
        final List< LogEvent > got = Collections.synchronizedList( new ArrayList<>() );
        final AbstractAppender appender = new AbstractAppender( "collabLogCapture", null, null, true,
            Property.EMPTY_ARRAY ) {
            @Override
            public void append( final LogEvent e ) {
                got.add( e.toImmutable() );
            }
        };
        appender.start();
        final LoggerContext ctx = ( LoggerContext ) LogManager.getContext( false );
        final Configuration cfg = ctx.getConfiguration();
        final LoggerConfig lc = LoggerConfig.newBuilder().setAdditivity( false ).setLevel( Level.WARN )
            .setLoggerName( PACKAGE ).setIncludeLocation( "false" ).setRefs( new AppenderRef[ 0 ] ).setConfig( cfg )
            .build();
        lc.addAppender( appender, Level.WARN, null );
        cfg.addLogger( PACKAGE, lc );
        ctx.updateLoggers();
        try {
            r.run();
        } finally {
            cfg.removeLogger( PACKAGE );
            ctx.updateLoggers();
            appender.stop();
        }
        return List.copyOf( got );
    }

    /** How many of {@code events} at {@code level} report a failure of tick step {@code step}. */
    static long stepFailures( final List< LogEvent > events, final Level level, final String step ) {
        return events.stream().filter( e -> e.getLevel() == level )
            .filter( e -> e.getMessage().getFormattedMessage().contains( "tick step '" + step + "'" ) ).count();
    }
}
