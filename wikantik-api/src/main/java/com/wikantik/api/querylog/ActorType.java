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
package com.wikantik.api.querylog;

/**
 * Who issued a retrieval query, inferred at the endpoint from the surface + auth:
 * {@code AGENT} = programmatic (MCP/tools, or an {@code Authorization}-header'd API call),
 * {@code HUMAN} = an authenticated session with no Authorization header (the SPA),
 * {@code UNKNOWN} = anonymous / unclassifiable.
 */
public enum ActorType {

    HUMAN( "human" ),
    AGENT( "agent" ),
    UNKNOWN( "unknown" );

    private final String wire;

    ActorType( final String wire ) {
        this.wire = wire;
    }

    /** Stable lowercase token stored in {@code retrieval_query_log.actor_type}. */
    public String wire() {
        return wire;
    }

    /** Parse a stored wire token; any unrecognised or null value degrades to {@link #UNKNOWN}. */
    public static ActorType fromWire( final String s ) {
        if ( s != null ) {
            for ( final ActorType a : values() ) {
                if ( a.wire.equals( s ) ) {
                    return a;
                }
            }
        }
        return UNKNOWN;
    }
}
