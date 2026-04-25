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
package com.wikantik.api.structure;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Who a page is for. {@link #HUMANS_AND_AGENTS} (the default) means the page
 * appears both in human-facing navigation and in agent-shaped projections;
 * {@link #AGENTS} flags pages that read poorly as articles but answer agent
 * workflows well (dense tool recipes, decision trees); {@link #HUMANS} is the
 * inverse — pages that should not be promoted to agent surfaces.
 */
public enum Audience {

    HUMANS              ( "humans" ),
    AGENTS              ( "agents" ),
    HUMANS_AND_AGENTS   ( "humans-and-agents" );

    private final String wireName;

    Audience( final String wireName ) {
        this.wireName = wireName;
    }

    public String wireName() {
        return wireName;
    }

    /**
     * Authors typically write {@code audience: [humans, agents]} as a list. Accept the
     * canonical wire-form ("humans-and-agents") and the list form, returning the
     * default when nothing meaningful was authored.
     */
    @SuppressWarnings( "unchecked" )
    public static Audience fromFrontmatter( final Object raw ) {
        if ( raw == null ) {
            return HUMANS_AND_AGENTS;
        }
        if ( raw instanceof List< ? > list ) {
            final Set< String > entries = new java.util.HashSet<>();
            for ( final Object o : list ) {
                if ( o != null ) entries.add( o.toString().trim().toLowerCase( Locale.ROOT ) );
            }
            final boolean h = entries.contains( "humans" );
            final boolean a = entries.contains( "agents" );
            if ( h && a ) return HUMANS_AND_AGENTS;
            if ( a ) return AGENTS;
            if ( h ) return HUMANS;
            return HUMANS_AND_AGENTS;
        }
        final String value = raw.toString().trim().toLowerCase( Locale.ROOT );
        for ( final Audience aud : values() ) {
            if ( aud.wireName.equals( value ) ) {
                return aud;
            }
        }
        return HUMANS_AND_AGENTS;
    }

    public static Optional< Audience > fromWire( final String raw ) {
        if ( raw == null ) return Optional.empty();
        final String v = raw.trim().toLowerCase( Locale.ROOT );
        for ( final Audience a : values() ) {
            if ( a.wireName.equals( v ) ) return Optional.of( a );
        }
        return Optional.empty();
    }
}
