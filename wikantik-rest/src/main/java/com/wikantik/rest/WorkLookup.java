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

import com.wikantik.work.Actor;
import com.wikantik.work.Agent;
import com.wikantik.work.Queue;
import com.wikantik.work.WorkService;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.LongFunction;
import java.util.function.Supplier;

/**
 * The names of agents (by id) and queues (by key), which are not confidential to anyone who belongs to a project.
 * A null answer means unknown: the caller falls back to the stored text or the key.
 */
final class WorkLookup {

    private static final Logger LOG = LogManager.getLogger( WorkLookup.class );

    static final WorkLookup NONE = new WorkLookup( id -> null, key -> null );

    private final LongFunction< String > agents;
    private final Function< String, String > queues;

    WorkLookup( final LongFunction< String > agents, final Function< String, String > queues ) {
        this.agents = agents;
        this.queues = queues;
    }

    /** A lookup that reads each registry list on first use only, once. */
    static WorkLookup lazy( final Supplier< Map< Long, String > > agents, final Supplier< Map< String, String > > queues ) {
        final AtomicReference< Map< Long, String > > a = new AtomicReference<>();
        final AtomicReference< Map< String, String > > q = new AtomicReference<>();
        return new WorkLookup( id -> a.updateAndGet( m -> m == null ? agents.get() : m ).get( id ),
            key -> q.updateAndGet( m -> m == null ? queues.get() : m ).get( key ) );
    }

    /**
     * The registry's names for {@code viewer}, read the first time one is needed (most responses name none). A failed
     * read is logged and the response falls back to stored names and queue keys.
     */
    static WorkLookup forViewer( final WorkService svc, final Actor viewer ) {
        return lazy( () -> {
            final Map< Long, String > out = new HashMap<>();
            try {
                for ( final Agent a : svc.registry().listAgents( viewer ) ) {
                    out.put( a.id(), a.name() );
                }
            } catch ( final SQLException e ) {
                LOG.warn( "Could not read the agent names for a work response: {}", e.getMessage(), e );
            }
            return out;
        }, () -> {
            final Map< String, String > out = new HashMap<>();
            try {
                for ( final Queue q : svc.registry().listQueues( viewer ) ) {
                    out.put( q.key(), q.name() );
                }
            } catch ( final SQLException e ) {
                LOG.warn( "Could not read the queue names for a work response: {}", e.getMessage(), e );
            }
            return out;
        } );
    }

    String agent( final long id ) {
        return agents.apply( id );
    }

    String queue( final String key ) {
        return queues.apply( key );
    }
}
