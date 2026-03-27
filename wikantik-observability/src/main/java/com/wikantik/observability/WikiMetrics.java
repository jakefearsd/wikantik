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
package com.wikantik.observability;

import com.wikantik.api.core.Engine;
import com.wikantik.event.WikiEvent;
import com.wikantik.event.WikiEventListener;
import com.wikantik.event.WikiEventManager;
import com.wikantik.event.WikiPageEvent;
import com.wikantik.event.WikiSecurityEvent;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Registers wiki-specific Micrometer meters and listens for WikiEvents to increment them.
 *
 * <p>Metrics registered:</p>
 * <ul>
 *   <li>{@code wikantik.page.views} — counter, incremented on PAGE_REQUESTED</li>
 *   <li>{@code wikantik.page.edits} — counter, incremented on POST_SAVE</li>
 *   <li>{@code wikantik.page.deletes} — counter, incremented on PAGE_DELETED</li>
 *   <li>{@code wikantik.auth.logins} — counter, tagged by result (success/failure)</li>
 * </ul>
 */
public class WikiMetrics implements WikiEventListener {

    private static final Logger LOG = LogManager.getLogger( WikiMetrics.class );

    private final Counter pageViews;
    private final Counter pageEdits;
    private final Counter pageDeletes;
    private final Counter loginSuccess;
    private final Counter loginFailure;

    public WikiMetrics( final MeterRegistry registry, final Engine engine ) {
        pageViews = Counter.builder( "wikantik.page.views" )
                .description( "Total page view requests" )
                .register( registry );

        pageEdits = Counter.builder( "wikantik.page.edits" )
                .description( "Total page saves" )
                .register( registry );

        pageDeletes = Counter.builder( "wikantik.page.deletes" )
                .description( "Total page deletions" )
                .register( registry );

        loginSuccess = Counter.builder( "wikantik.auth.logins" )
                .description( "Total login attempts" )
                .tag( "result", "success" )
                .register( registry );

        loginFailure = Counter.builder( "wikantik.auth.logins" )
                .description( "Total login attempts" )
                .tag( "result", "failure" )
                .register( registry );

        // Register this listener on the engine so we receive all WikiEvents
        WikiEventManager.addWikiEventListener( engine, this );
        LOG.info( "Wiki metrics registered and listening for events" );
    }

    @Override
    public void actionPerformed( final WikiEvent event ) {
        if ( event instanceof WikiPageEvent ) {
            switch ( event.getType() ) {
                case WikiPageEvent.PAGE_REQUESTED -> pageViews.increment();
                case WikiPageEvent.POST_SAVE -> pageEdits.increment();
                case WikiPageEvent.PAGE_DELETED -> pageDeletes.increment();
                default -> { /* ignore other page events */ }
            }
        } else if ( event instanceof WikiSecurityEvent ) {
            switch ( event.getType() ) {
                case WikiSecurityEvent.LOGIN_AUTHENTICATED -> loginSuccess.increment();
                case WikiSecurityEvent.LOGIN_FAILED -> loginFailure.increment();
                default -> { /* ignore other security events */ }
            }
        }
    }

}
