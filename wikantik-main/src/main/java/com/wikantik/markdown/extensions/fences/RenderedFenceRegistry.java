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
package com.wikantik.markdown.extensions.fences;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/** Lookup of {@link RenderedFenceRenderer}s by fence language. */
public final class RenderedFenceRegistry {

    private static final class Holder {
        static final RenderedFenceRegistry DEFAULTS = of( new MermaidFenceRenderer() );
    }

    private final Map< String, RenderedFenceRenderer > byLanguage;

    private RenderedFenceRegistry( final Map< String, RenderedFenceRenderer > byLanguage ) {
        this.byLanguage = byLanguage;
    }

    /** The production registry: Mermaid only. Cached singleton. */
    public static RenderedFenceRegistry defaults() {
        return Holder.DEFAULTS;
    }

    /** @throws IllegalArgumentException when two renderers share a language */
    public static RenderedFenceRegistry of( final RenderedFenceRenderer... renderers ) {
        final Map< String, RenderedFenceRenderer > m = new HashMap<>();
        for ( final RenderedFenceRenderer r : renderers ) {
            final String key = r.language().toLowerCase( Locale.ROOT );
            if ( m.put( key, r ) != null ) {
                throw new IllegalArgumentException( "Duplicate rendered-fence language: " + key );
            }
        }
        return new RenderedFenceRegistry( Map.copyOf( m ) );
    }

    public Optional< RenderedFenceRenderer > forLanguage( final String language ) {
        return language == null ? Optional.empty()
                : Optional.ofNullable( byLanguage.get( language.toLowerCase( Locale.ROOT ) ) );
    }

    /** Empty when the fence is an {@code example} or its language is unregistered. */
    public Optional< RenderedFenceRenderer > forInfo( final FenceInfo info ) {
        return info.example() ? Optional.empty() : forLanguage( info.language() );
    }
}
