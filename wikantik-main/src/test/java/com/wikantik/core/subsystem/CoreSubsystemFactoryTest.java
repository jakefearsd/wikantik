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
package com.wikantik.core.subsystem;

import com.wikantik.WikiEngine;
import com.wikantik.api.managers.SystemPageRegistry;
import com.wikantik.blog.BlogManager;
import com.wikantik.cache.CachingManager;
import com.wikantik.content.RecentArticlesManager;
import com.wikantik.i18n.InternationalizationManager;
import com.wikantik.ui.CommandResolver;
import com.wikantik.ui.progress.ProgressManager;
import com.wikantik.url.URLConstructor;
import com.wikantik.variables.VariableManager;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Phase 2 subsystem-isolation test for {@link CoreSubsystemFactory}.
 *
 * <p>Confirms that {@link CoreSubsystem.Services} can be built from a
 * {@link CoreSubsystem.Deps} record without {@code WikiEngine}: typed
 * properties read from the wrapped {@link Properties}, the event bus is a
 * non-null delegating impl, and the leaf managers pass straight through.</p>
 */
final class CoreSubsystemFactoryTest {

    @Test
    void createWiresServicesFromDeps() {
        final Properties raw = new Properties();
        raw.setProperty( "answer", "42" );
        raw.setProperty( "feature.on", "true" );

        final SystemPageRegistry sys = mock( SystemPageRegistry.class );
        final RecentArticlesManager recent = mock( RecentArticlesManager.class );
        final BlogManager blog = mock( BlogManager.class );
        final SimpleMeterRegistry meters = new SimpleMeterRegistry();

        final CachingManager cachingManager = mock( CachingManager.class );
        final VariableManager variableManager = mock( VariableManager.class );
        final ProgressManager progressManager = mock( ProgressManager.class );
        final CommandResolver commandResolver = mock( CommandResolver.class );
        final URLConstructor urlConstructor = mock( URLConstructor.class );
        final InternationalizationManager i18n = mock( InternationalizationManager.class );
        final WikiEngine engine = mock( WikiEngine.class );
        when( engine.getManager( CachingManager.class ) ).thenReturn( cachingManager );
        when( engine.getManager( VariableManager.class ) ).thenReturn( variableManager );
        when( engine.getManager( ProgressManager.class ) ).thenReturn( progressManager );
        when( engine.getManager( CommandResolver.class ) ).thenReturn( commandResolver );
        when( engine.getManager( URLConstructor.class ) ).thenReturn( urlConstructor );
        when( engine.getManager( InternationalizationManager.class ) ).thenReturn( i18n );

        final CoreSubsystem.Services services = CoreSubsystemFactory.create(
            new CoreSubsystem.Deps( raw, null, meters, sys, recent, blog, engine ) );

        assertNotNull( services.properties() );
        assertEquals( "42", services.properties().get( "answer" ) );
        assertEquals( 42, services.properties().getInt( "answer", 0 ) );
        assertEquals( true, services.properties().getBoolean( "feature.on", false ) );
        assertEquals( "fallback", services.properties().get( "missing", "fallback" ) );
        assertSame( raw, services.properties().asProperties() );

        assertNotNull( services.eventBus() );
        assertSame( meters, services.meterRegistry() );
        assertSame( sys, services.systemPageRegistry() );
        assertSame( recent, services.recentArticlesManager() );
        assertSame( blog, services.blogManager() );
        assertSame( cachingManager, services.cachingManager() );
        assertSame( variableManager, services.variableManager() );
        assertSame( progressManager, services.progressManager() );
        assertSame( commandResolver, services.commandResolver() );
        assertSame( urlConstructor, services.urlConstructor() );
        assertSame( i18n, services.i18n() );
    }

    @Test
    void createWithoutMeterRegistryFallsBackToSimple() {
        final CachingManager cachingManager = mock( CachingManager.class );
        final WikiEngine engine = mock( WikiEngine.class );
        when( engine.getManager( CachingManager.class ) ).thenReturn( cachingManager );
        // other managers return null by default from the mock

        final CoreSubsystem.Services services = CoreSubsystemFactory.create(
            new CoreSubsystem.Deps(
                new Properties(), null, null,
                mock( SystemPageRegistry.class ),
                mock( RecentArticlesManager.class ),
                mock( BlogManager.class ),
                engine ) );

        assertNotNull( services.meterRegistry() );
        assertSame( cachingManager, services.cachingManager() );
    }

    @Test
    void createRejectsMissingDeps() {
        assertThrows( NullPointerException.class, () -> CoreSubsystemFactory.create( null ) );
        assertThrows( NullPointerException.class, () -> CoreSubsystemFactory.create(
            new CoreSubsystem.Deps( null, null, null, null, null, null, null ) ) );
        assertThrows( NullPointerException.class, () -> CoreSubsystemFactory.create(
            new CoreSubsystem.Deps( new Properties(), null, null,
                mock( SystemPageRegistry.class ),
                mock( RecentArticlesManager.class ),
                mock( BlogManager.class ),
                null ) ) );
    }
}
