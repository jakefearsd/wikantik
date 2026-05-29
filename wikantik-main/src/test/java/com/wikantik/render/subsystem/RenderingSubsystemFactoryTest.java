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
package com.wikantik.render.subsystem;

import com.wikantik.WikiEngine;
import com.wikantik.diff.DifferenceManager;
import com.wikantik.filters.FilterManager;
import com.wikantik.filters.SpamFilter;
import com.wikantik.plugin.PluginManager;
import com.wikantik.render.RenderingManager;
import com.wikantik.render.subsystem.spam.SpamExternalSignals;
import com.wikantik.render.subsystem.spam.SpamPatternMatcher;
import com.wikantik.render.subsystem.spam.SpamPolicy;
import com.wikantik.render.subsystem.spam.SpamRateLimiter;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Phase 6 subsystem-isolation test for {@link RenderingSubsystemFactory}.
 *
 * <p>Demonstrates that the Rendering subsystem can be assembled without
 * {@code WikiEngine} or {@code TestEngine}: a mocked {@link Engine}
 * stocked with the four manager-level objects is enough to produce a
 * populated {@link RenderingSubsystem.Services}. The four SpamFilter
 * helper slots stay {@code null} until Ckpt 4 — that's the intended
 * Ckpt 1 shape.</p>
 */
final class RenderingSubsystemFactoryTest {

    @Test
    void createWiresFourManagersFromEngineRegistry() {
        final RenderingManager  renderingManager  = mock( RenderingManager.class );
        final PluginManager     pluginManager     = mock( PluginManager.class );
        final FilterManager     filterManager     = mock( FilterManager.class );
        final DifferenceManager differenceManager = mock( DifferenceManager.class );

        final WikiEngine engine = mock( WikiEngine.class );
        when( engine.getManager( RenderingManager.class ) ).thenReturn( renderingManager );
        when( engine.getManager( PluginManager.class ) ).thenReturn( pluginManager );
        when( engine.getManager( FilterManager.class ) ).thenReturn( filterManager );
        when( engine.getManager( DifferenceManager.class ) ).thenReturn( differenceManager );

        final RenderingSubsystem.Services services = RenderingSubsystemFactory.create(
            new RenderingSubsystem.Deps(
                /*core=*/ mock( com.wikantik.core.subsystem.CoreSubsystem.Services.class ),
                /*auth=*/ null,
                /*page=*/ null,
                engine ) );

        assertSame( renderingManager, services.renderingManager() );
        assertSame( pluginManager, services.pluginManager() );
        assertSame( filterManager, services.filterManager() );
        assertSame( differenceManager, services.differenceManager() );

        // Ckpt 4: when the FilterManager has no SpamFilter registered the
        // four helper slots stay null — same shape as a missing manager.
        assertNull( services.spamRateLimiter(),     "no SpamFilter → null spamRateLimiter" );
        assertNull( services.spamPatternMatcher(),  "no SpamFilter → null spamPatternMatcher" );
        assertNull( services.spamExternalSignals(), "no SpamFilter → null spamExternalSignals" );
        assertNull( services.spamPolicy(),          "no SpamFilter → null spamPolicy" );
    }

    @Test
    void createWiresSpamHelpersFromRegisteredSpamFilter() {
        final RenderingManager  renderingManager  = mock( RenderingManager.class );
        final PluginManager     pluginManager     = mock( PluginManager.class );
        final FilterManager     filterManager     = mock( FilterManager.class );
        final DifferenceManager differenceManager = mock( DifferenceManager.class );

        final SpamRateLimiter     rateLimiter     = mock( SpamRateLimiter.class );
        final SpamPatternMatcher  patternMatcher  = mock( SpamPatternMatcher.class );
        final SpamExternalSignals externalSignals = mock( SpamExternalSignals.class );
        final SpamPolicy          policy          = mock( SpamPolicy.class );

        final SpamFilter spam = mock( SpamFilter.class );
        when( spam.getRateLimiter()     ).thenReturn( rateLimiter );
        when( spam.getPatternMatcher()  ).thenReturn( patternMatcher );
        when( spam.getExternalSignals() ).thenReturn( externalSignals );
        when( spam.getPolicy()          ).thenReturn( policy );
        when( filterManager.getFilterList() ).thenReturn( List.of( spam ) );

        final WikiEngine engine = mock( WikiEngine.class );
        when( engine.getManager( RenderingManager.class ) ).thenReturn( renderingManager );
        when( engine.getManager( PluginManager.class ) ).thenReturn( pluginManager );
        when( engine.getManager( FilterManager.class ) ).thenReturn( filterManager );
        when( engine.getManager( DifferenceManager.class ) ).thenReturn( differenceManager );

        final RenderingSubsystem.Services services = RenderingSubsystemFactory.create(
            new RenderingSubsystem.Deps(
                /*core=*/ mock( com.wikantik.core.subsystem.CoreSubsystem.Services.class ),
                /*auth=*/ null,
                /*page=*/ null,
                engine ) );

        assertSame( filterManager,   services.filterManager() );
        assertSame( rateLimiter,     services.spamRateLimiter() );
        assertSame( patternMatcher,  services.spamPatternMatcher() );
        assertSame( externalSignals, services.spamExternalSignals() );
        assertSame( policy,          services.spamPolicy() );
    }

    @Test
    void createRejectsMissingDeps() {
        // null deps record → NPE
        assertThrows( NullPointerException.class, () -> RenderingSubsystemFactory.create( null ) );
        // null engine → NPE (core/auth/page are optional — factory does not use them today)
        assertThrows( NullPointerException.class, () -> RenderingSubsystemFactory.create(
            new RenderingSubsystem.Deps(
                mock( com.wikantik.core.subsystem.CoreSubsystem.Services.class ),
                null, null, null ) ) );
    }
}
