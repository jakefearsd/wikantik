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
package com.wikantik;

import com.wikantik.auth.subsystem.AuthSubsystem;
import com.wikantik.core.subsystem.CoreSubsystem;
import com.wikantik.knowledge.subsystem.KnowledgeSubsystem;
import com.wikantik.page.subsystem.PageSubsystem;
import com.wikantik.pagegraph.subsystem.PageGraphSubsystem;
import com.wikantik.persistence.subsystem.PersistenceSubsystem;
import com.wikantik.render.subsystem.RenderingSubsystem;
import com.wikantik.search.subsystem.SearchSubsystem;
import org.mockito.Mockito;

import java.lang.reflect.Constructor;
import java.lang.reflect.Parameter;

/**
 * Test helper for assembling a {@link WikiSubsystems} bundle whose every field
 * is a Mockito mock by default, with optional overrides for individual
 * subsystems.
 *
 * <p>This is the test-side stand-in for the {@code WikiSubsystems.forTesting(...)}
 * factory called for in Phase 9 Checkpoint 5 of the wikantik-main subsystem
 * decomposition. It lives in test sources because Mockito is a {@code <scope>test</scope>}
 * dependency of {@code wikantik-main} and adding Mockito to the production
 * classpath solely to expose this helper there would be the wrong
 * trade-off.</p>
 *
 * <p>Newer tests should prefer {@link #defaults()} over {@code TestEngine.setManager(...)}
 * patterns. The latter still works because {@code WikiEngine} retains the
 * legacy {@code managers} registry; both paths are supported during the
 * remainder of the decomposition.</p>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * // All-mocks bundle:
 * WikiSubsystems subs = WikiSubsystemsTestFactory.defaults();
 *
 * // Override a specific subsystem with hand-built services:
 * AuthSubsystem.Services authOverride = new AuthSubsystem.Services(...);
 * WikiSubsystems subs = WikiSubsystemsTestFactory.builder()
 *     .auth(authOverride)
 *     .build();
 * }</pre>
 */
public final class WikiSubsystemsTestFactory {

    private WikiSubsystemsTestFactory() {}

    /**
     * Returns a {@link WikiSubsystems} where every subsystem field is itself
     * a {@code Services} record whose components are Mockito mocks.
     */
    public static WikiSubsystems defaults() {
        return builder().build();
    }

    /** Starts a builder for partial overrides. */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Reflective record-builder: instantiates a Java {@code record} type whose
     * canonical constructor takes only reference-type components, by passing
     * a Mockito mock for each.
     *
     * <p>This collapses ~80 individual {@code Mockito.mock(X.class)} calls to a
     * single line per subsystem and keeps {@link #defaults()} resilient when
     * subsystem records grow new fields — new fields auto-mock without
     * touching this helper.</p>
     */
    public static <T> T mockRecord( final Class<T> recordClass ) {
        if ( !recordClass.isRecord() ) {
            throw new IllegalArgumentException( recordClass + " is not a record" );
        }
        final Constructor<?>[] ctors = recordClass.getDeclaredConstructors();
        Constructor<?> canonical = null;
        for ( final Constructor<?> c : ctors ) {
            if ( c.getParameterCount() == recordClass.getRecordComponents().length ) {
                canonical = c;
                break;
            }
        }
        if ( canonical == null ) {
            throw new IllegalStateException( "No canonical constructor for " + recordClass );
        }
        final Parameter[] params = canonical.getParameters();
        final Object[] args = new Object[ params.length ];
        for ( int i = 0; i < params.length; i++ ) {
            final Class<?> pt = params[ i ].getType();
            if ( pt.isPrimitive() ) {
                throw new IllegalStateException(
                    "Primitive components are not supported by mockRecord(): " + pt );
            }
            args[ i ] = Mockito.mock( pt );
        }
        try {
            canonical.setAccessible( true );
            @SuppressWarnings( "unchecked" )
            final T instance = ( T ) canonical.newInstance( args );
            return instance;
        } catch ( final ReflectiveOperationException e ) {
            throw new IllegalStateException(
                "Failed to instantiate mock record " + recordClass, e );
        }
    }

    /** Builder for assembling a {@link WikiSubsystems} with sparse overrides. */
    public static final class Builder {
        private CoreSubsystem.Services        core;
        private PersistenceSubsystem.Services persistence;
        private AuthSubsystem.Services        auth;
        private PageSubsystem.Services        page;
        private RenderingSubsystem.Services   rendering;
        private SearchSubsystem.Services      search;
        private KnowledgeSubsystem.Services   knowledge;
        private PageGraphSubsystem.Services   pageGraph;

        public Builder core( final CoreSubsystem.Services s ) { this.core = s; return this; }
        public Builder persistence( final PersistenceSubsystem.Services s ) { this.persistence = s; return this; }
        public Builder auth( final AuthSubsystem.Services s ) { this.auth = s; return this; }
        public Builder page( final PageSubsystem.Services s ) { this.page = s; return this; }
        public Builder rendering( final RenderingSubsystem.Services s ) { this.rendering = s; return this; }
        public Builder search( final SearchSubsystem.Services s ) { this.search = s; return this; }
        public Builder knowledge( final KnowledgeSubsystem.Services s ) { this.knowledge = s; return this; }
        public Builder pageGraph( final PageGraphSubsystem.Services s ) { this.pageGraph = s; return this; }

        public WikiSubsystems build() {
            return new WikiSubsystems(
                core        != null ? core        : mockRecord( CoreSubsystem.Services.class ),
                persistence != null ? persistence : mockRecord( PersistenceSubsystem.Services.class ),
                auth        != null ? auth        : mockRecord( AuthSubsystem.Services.class ),
                page        != null ? page        : mockRecord( PageSubsystem.Services.class ),
                rendering   != null ? rendering   : mockRecord( RenderingSubsystem.Services.class ),
                search      != null ? search      : mockRecord( SearchSubsystem.Services.class ),
                knowledge   != null ? knowledge   : mockRecord( KnowledgeSubsystem.Services.class ),
                pageGraph   != null ? pageGraph   : mockRecord( PageGraphSubsystem.Services.class )
            );
        }
    }
}
