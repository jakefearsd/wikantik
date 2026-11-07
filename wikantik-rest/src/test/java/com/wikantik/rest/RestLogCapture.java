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
package com.wikantik.rest;

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

/** Captures one class's log events at every level (DEBUG and up) while a block runs. */
final class RestLogCapture {

    private RestLogCapture() {
    }

    /**
     * The events {@code type}'s logger emitted at DEBUG or above while {@code r} ran. A logger config of exactly that
     * name, added for the duration (non-additive, so nothing reaches the console), replaces any other test's level.
     */
    static List< LogEvent > events( final Class< ? > type, final Runnable r ) {
        final List< LogEvent > got = Collections.synchronizedList( new ArrayList<>() );
        final AbstractAppender appender = new AbstractAppender( "restLogCapture", null, null, true,
            Property.EMPTY_ARRAY ) {
            @Override
            public void append( final LogEvent e ) {
                got.add( e.toImmutable() );
            }
        };
        appender.start();
        final LoggerContext ctx = ( LoggerContext ) LogManager.getContext( false );
        final Configuration cfg = ctx.getConfiguration();
        final String name = type.getName();
        final LoggerConfig previous = cfg.getLoggers().get( name );
        if( previous != null ) {
            cfg.removeLogger( name );
        }
        final LoggerConfig lc = LoggerConfig.newBuilder().setAdditivity( false ).setLevel( Level.DEBUG )
            .setLoggerName( name ).setIncludeLocation( "false" ).setRefs( new AppenderRef[ 0 ] ).setConfig( cfg )
            .build();
        lc.addAppender( appender, Level.DEBUG, null );
        cfg.addLogger( name, lc );
        ctx.updateLoggers();
        try {
            r.run();
        } finally {
            cfg.removeLogger( name );
            if( previous != null ) {
                cfg.addLogger( name, previous );
            }
            ctx.updateLoggers();
            appender.stop();
        }
        return List.copyOf( got );
    }
}
