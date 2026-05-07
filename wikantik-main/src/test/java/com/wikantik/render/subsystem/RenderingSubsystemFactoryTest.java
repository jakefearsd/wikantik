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

import com.wikantik.api.core.Engine;
import com.wikantik.diff.DifferenceManager;
import com.wikantik.filters.FilterManager;
import com.wikantik.plugin.PluginManager;
import com.wikantik.render.RenderingManager;
import org.junit.jupiter.api.Test;

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

        final Engine engine = mock( Engine.class );
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

        // Ckpt 1: the four SpamFilter helpers are null until Ckpt 4 wires
        // the decomposed components from the live SpamFilter instance.
        assertNull( services.spamRateLimiter(),     "spamRateLimiter populated by Ckpt 4" );
        assertNull( services.spamPatternMatcher(),  "spamPatternMatcher populated by Ckpt 4" );
        assertNull( services.spamExternalSignals(), "spamExternalSignals populated by Ckpt 4" );
        assertNull( services.spamPolicy(),          "spamPolicy populated by Ckpt 4" );
    }

    @Test
    void createRejectsMissingDeps() {
        assertThrows( NullPointerException.class, () -> RenderingSubsystemFactory.create( null ) );
        assertThrows( NullPointerException.class, () -> RenderingSubsystemFactory.create(
            new RenderingSubsystem.Deps( null, null, null, mock( Engine.class ) ) ) );
        assertThrows( NullPointerException.class, () -> RenderingSubsystemFactory.create(
            new RenderingSubsystem.Deps(
                mock( com.wikantik.core.subsystem.CoreSubsystem.Services.class ),
                null, null, null ) ) );
    }
}
