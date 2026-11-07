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

import com.wikantik.collab.model.PublishResponse;
import com.wikantik.collab.model.PushResponse;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * A {@link CollabMetrics} that never throws (a delegate's {@link RuntimeException} is logged at WARN), plus the
 * classification of push and publish answers into their {@code result} labels (section 12.3).
 */
final class GuardedMetrics implements CollabMetrics {

    private static final Logger LOG = LogManager.getLogger( GuardedMetrics.class );

    private final CollabMetrics delegate;

    private GuardedMetrics( final CollabMetrics delegate ) {
        this.delegate = delegate;
    }

    /** {@code metrics} behind a guard; {@link CollabMetrics#NONE} and an already guarded one are returned as they are. */
    static CollabMetrics of( final CollabMetrics metrics ) {
        return metrics == NONE || metrics instanceof GuardedMetrics ? metrics : new GuardedMetrics( metrics );
    }

    /** 200 is {@link #ACCEPTED}, 409 {@code stale-base} {@link #REBASE}, anything else {@link #REJECTED}. */
    static String pushResult( final PushResponse r ) {
        if( r.status() == 200 ) {
            return ACCEPTED;
        }
        return r.status() == 409 && CollabSession.STALE.equals( r.reason() ) ? REBASE : REJECTED;
    }

    /** The {@code result} label of a publish answer (see the constants of {@link CollabMetrics}). */
    static String publishResult( final PublishResponse r ) {
        return switch( r.status() ) {
            case 200 -> r.unchanged() ? UNCHANGED : PUBLISHED;
            case 409 -> CONFLICT;
            case 503 -> STORE;
            case 500 -> FAILED;
            default -> REFUSED;
        };
    }

    private static void guarded( final String meter, final Runnable call ) {
        try {
            call.run();
        } catch( final RuntimeException e ) {
            LOG.warn( "collab: the {} meter was not updated: {}", meter, e.toString() );
        }
    }

    @Override
    public void push( final String result ) {
        guarded( "push", () -> delegate.push( result ) );
    }

    @Override
    public void pushLatency( final long nanos ) {
        guarded( "push latency", () -> delegate.pushLatency( nanos ) );
    }

    @Override
    public void publish( final String result ) {
        guarded( "publish", () -> delegate.publish( result ) );
    }

    @Override
    public void externalMerge() {
        guarded( "external merge", delegate::externalMerge );
    }

    @Override
    public void resync() {
        guarded( "resync", delegate::resync );
    }
}
