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
package com.wikantik.livevalues;

import com.wikantik.api.livevalues.LiveValueExpander;
import com.wikantik.api.livevalues.LiveValueExpander.Resolution;
import com.wikantik.api.livevalues.LiveValueSource;
import com.wikantik.api.livevalues.LiveValues;
import com.wikantik.api.pagegraph.StructuralIndexService;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Computes live values lazily, once per structural-index revision (never per request or per viewer), and expands
 * tokens against them. Expansion never throws: a source that fails on recompute is logged and its names resolve
 * unwired. When disabled, every token is served verbatim.
 */
public final class DefaultLiveValues implements LiveValues {

    private static final Logger LOG = LogManager.getLogger( DefaultLiveValues.class );

    private static final Resolution UNKNOWN = new Resolution.Unknown();
    private static final Resolution UNWIRED = new Resolution.Unwired();

    /** One consistent computation, immutable once built. */
    private record State( long revision, Map< String, Resolution > values, Set< String > failedNames,
                          Set< String > declared, Map< String, String > rendered, String generation ) { }

    private final StructuralIndexService index;
    private final boolean enabled;
    private final List< LiveValueSource > sources = new CopyOnWriteArrayList<>();
    private volatile State state;

    public DefaultLiveValues( final StructuralIndexService index, final boolean enabled ) {
        this.index = index;
        this.enabled = enabled;
    }

    @Override
    public void register( final LiveValueSource source ) {
        sources.add( source );
        state = null;
    }

    @Override
    public String expandText( final String text ) {
        if( !enabled || text == null || text.isEmpty() ) {
            return text;
        }
        final State s = current();
        return LiveValueExpander.expandText( text, name -> resolve( s, name ) );
    }

    @Override
    public String expandBody( final String markdown ) {
        if( !enabled || markdown == null || markdown.isEmpty() ) {
            return markdown;
        }
        final State s = current();
        return LiveValueExpander.expandBody( markdown, name -> resolve( s, name ) );
    }

    @Override
    public String generation() {
        return enabled ? current().generation() : "disabled";
    }

    @Override
    public Map< String, String > renderedValues() {
        return enabled ? current().rendered() : Map.of();
    }

    private static Resolution resolve( final State s, final String name ) {
        final Resolution v = s.values().get( name );
        if( v != null ) {
            return v;
        }
        if( s.failedNames().contains( name ) || ownedByFailedPrefix( s, name ) ) {
            return UNWIRED;
        }
        if( s.declared().contains( name ) || LiveValueExpander.BUILT_IN_NAMES.contains( name ) ) {
            return UNWIRED;
        }
        return UNKNOWN;
    }

    private static boolean ownedByFailedPrefix( final State s, final String name ) {
        for( final String f : s.failedNames() ) {
            if( f.endsWith( "." ) && name.startsWith( f ) ) {
                return true;
            }
        }
        return false;
    }

    private State current() {
        final long revision = index.snapshot().revision();
        final State s = state;
        if( s != null && s.revision() == revision ) {
            return s;
        }
        synchronized( this ) {
            final State again = state;
            if( again != null && again.revision() == revision ) {
                return again;
            }
            final State fresh = compute( revision );
            state = fresh;
            return fresh;
        }
    }

    private State compute( final long revision ) {
        final Map< String, Resolution > values = new HashMap<>();
        final Set< String > failed = new HashSet<>();
        final Set< String > declared = new HashSet<>();
        for( final LiveValueSource source : sources ) {
            final Set< String > names = safeDeclared( source );
            declared.addAll( names );
            try {
                final Map< String, Resolution > got = source.values();
                for( final Map.Entry< String, Resolution > e : got.entrySet() ) {
                    final Resolution r = e.getValue();
                    if( r != null && !( r instanceof Resolution.Text t && t.text() == null ) ) {
                        values.put( e.getKey(), r );
                    }
                }
            } catch( final RuntimeException e ) {
                LOG.warn( "Live value source {} failed on recompute; its names resolve unwired: {}",
                          source.getClass().getName(), e.getMessage(), e );
                failed.addAll( names );
            }
        }
        final Map< String, String > rendered = new TreeMap<>();
        values.forEach( ( k, v ) -> {
            if( v instanceof Resolution.Value n ) {
                rendered.put( k, String.format( Locale.US, "%,d", n.number() ) );
            } else if( v instanceof Resolution.Text t ) {
                rendered.put( k, t.text() );
            }
        } );
        return new State( revision, Map.copyOf( values ), Set.copyOf( failed ), Set.copyOf( declared ),
                          java.util.Collections.unmodifiableMap( rendered ), digest( rendered ) );
    }

    private static Set< String > safeDeclared( final LiveValueSource source ) {
        try {
            return source.declaredNames();
        } catch( final RuntimeException e ) {
            LOG.warn( "Live value source {} failed to declare its names: {}", source.getClass().getName(),
                      e.getMessage(), e );
            return Set.of();
        }
    }

    private static String digest( final Map< String, String > sorted ) {
        try {
            final MessageDigest md = MessageDigest.getInstance( "SHA-256" );
            final StringBuilder sb = new StringBuilder();
            new TreeMap<>( sorted ).forEach( ( k, v ) -> sb.append( k ).append( '=' ).append( v ).append( '\n' ) );
            return java.util.HexFormat.of().formatHex( md.digest( sb.toString().getBytes( StandardCharsets.UTF_8 ) ) );
        } catch( final NoSuchAlgorithmException e ) {
            throw new IllegalStateException( "SHA-256 unavailable", e );
        }
    }
}
