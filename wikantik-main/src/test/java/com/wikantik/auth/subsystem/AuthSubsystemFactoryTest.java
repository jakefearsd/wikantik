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
package com.wikantik.auth.subsystem;

import com.wikantik.api.core.Engine;
import com.wikantik.auth.AuthenticationManager;
import com.wikantik.auth.AuthorizationManager;
import com.wikantik.auth.UserManager;
import com.wikantik.auth.authorize.GroupManager;
import com.wikantik.blog.BlogManager;
import com.wikantik.content.RecentArticlesManager;
import com.wikantik.api.managers.SystemPageRegistry;
import com.wikantik.core.subsystem.CoreSubsystem;
import com.wikantik.core.subsystem.CoreSubsystemFactory;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Phase 4 subsystem-isolation test for {@link AuthSubsystemFactory}.
 *
 * <p>Demonstrates that the Auth subsystem can be assembled without
 * {@code WikiEngine} or {@code TestEngine}: a mocked {@link Engine}
 * stocked with the four core auth managers + a {@link CoreSubsystem.Services}
 * built from the real factory is enough to produce a populated
 * {@link AuthSubsystem.Services}.</p>
 */
final class AuthSubsystemFactoryTest {

    @Test
    void createWiresFourCoreManagersFromEngineRegistry() {
        final AuthenticationManager authn = mock( AuthenticationManager.class );
        final AuthorizationManager  authz = mock( AuthorizationManager.class );
        final UserManager           users = mock( UserManager.class );
        final GroupManager          groups = mock( GroupManager.class );

        final Engine engine = mock( Engine.class );
        when( engine.getManager( AuthenticationManager.class ) ).thenReturn( authn );
        when( engine.getManager( AuthorizationManager.class ) ).thenReturn( authz );
        when( engine.getManager( UserManager.class ) ).thenReturn( users );
        when( engine.getManager( GroupManager.class ) ).thenReturn( groups );

        final CoreSubsystem.Services core = CoreSubsystemFactory.create( new CoreSubsystem.Deps(
            new Properties(), null, new SimpleMeterRegistry(),
            mock( SystemPageRegistry.class ),
            mock( RecentArticlesManager.class ),
            mock( BlogManager.class ) ) );

        final AuthSubsystem.Services services = AuthSubsystemFactory.create(
            new AuthSubsystem.Deps( core, /*persistence=*/ null, /*servletContext=*/ null, engine ) );

        assertSame( authn, services.authentication() );
        assertSame( authz, services.authorization() );
        assertSame( users, services.users() );
        assertSame( groups, services.groups() );
        assertNotNull( services, "services" );
        // webAuthorizer is null when the authorizer isn't configured (mocked
        // AuthorizationManager.getAuthorizer() returns null) — no assertion.
        // apiKeys is null when no datasource is configured — same.
        // securityVerifier is null until Ckpt 3.
    }

    @Test
    void createRejectsMissingDeps() {
        final CoreSubsystem.Services core = CoreSubsystemFactory.create( new CoreSubsystem.Deps(
            new Properties(), null, new SimpleMeterRegistry(),
            mock( SystemPageRegistry.class ),
            mock( RecentArticlesManager.class ),
            mock( BlogManager.class ) ) );

        assertThrows( NullPointerException.class, () -> AuthSubsystemFactory.create( null ) );
        assertThrows( NullPointerException.class, () -> AuthSubsystemFactory.create(
            new AuthSubsystem.Deps( null, null, null, mock( Engine.class ) ) ) );
        assertThrows( NullPointerException.class, () -> AuthSubsystemFactory.create(
            new AuthSubsystem.Deps( core, null, null, null ) ) );
    }
}
