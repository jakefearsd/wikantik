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
package com.wikantik.core.registry;

import java.util.IdentityHashMap;
import java.util.Map;

/**
 * Generic, map-backed store for WikiEngine's late-bound services. Replaces the
 * 78 typed {@code mgr_*} fields and the two 78-entry static dispatch maps
 * (TYPED_FIELD_WRITERS/TYPED_FIELD_READERS) that used to live on
 * {@link com.wikantik.WikiEngine}.
 *
 * <p>Holds {@code Object} keyed by exact {@code Class<?>} (identity semantics,
 * matching the former IdentityHashMap dispatch). Deliberately references no
 * concrete service type — the coupling this extraction removes does not come
 * back here. Snapshot-rebuild policy stays in WikiEngine (SNAPSHOT_REBUILDERS).</p>
 */
public final class EngineServiceRegistry {

    private final Map<Class<?>, Object> services = new IdentityHashMap<>( 128 );
    private final Map<Class<?>, Boolean> everWritten = new IdentityHashMap<>( 128 );

    /** Stores {@code impl} under {@code type}, overwriting any prior value; marks the key known. */
    public <T> void put( final Class<T> type, final T impl ) {
        services.put( type, impl );
        everWritten.put( type, Boolean.TRUE );
    }

    /** Returns the instance registered under {@code type}, or {@code null} if none. */
    @SuppressWarnings( "unchecked" )
    public <T> T get( final Class<T> type ) {
        return ( T ) services.get( type );
    }

    /** Whether {@code type} has ever been written (even with a null value). */
    public boolean isKnownType( final Class<?> type ) {
        return everWritten.containsKey( type );
    }
}
