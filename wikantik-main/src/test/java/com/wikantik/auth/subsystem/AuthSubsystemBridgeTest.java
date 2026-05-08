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

import com.wikantik.WikiEngine;
import com.wikantik.auth.AuthenticationManager;
import com.wikantik.auth.AuthorizationManager;
import com.wikantik.auth.UserManager;
import com.wikantik.auth.acl.AclManager;
import com.wikantik.auth.authorize.GroupManager;
import org.junit.jupiter.api.Test;

import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Phase 11 Checkpoint 5 bridge-delegation test for {@link AuthSubsystemBridge}.
 *
 * <p>Confirms that {@link AuthSubsystemBridge#rebuildFromManagers} delegates to
 * {@link AuthSubsystemFactory#create} and produces an {@link AuthSubsystem.Services}
 * record wired from the engine's manager registry.</p>
 */
final class AuthSubsystemBridgeTest {

    @Test
    void rebuildFromManagers_delegatesToFactory_wiresFourCoreManagers() {
        final AuthenticationManager auth  = mock( AuthenticationManager.class );
        final UserManager           users = mock( UserManager.class );
        final GroupManager          groups = mock( GroupManager.class );
        final AclManager            acl   = mock( AclManager.class );

        final WikiEngine engine = mock( WikiEngine.class );
        when( engine.getWikiProperties() ).thenReturn( new Properties() );
        when( engine.getManager( AuthenticationManager.class ) ).thenReturn( auth );
        when( engine.getManager( AuthorizationManager.class ) ).thenReturn( null );
        when( engine.getManager( UserManager.class ) ).thenReturn( users );
        when( engine.getManager( GroupManager.class ) ).thenReturn( groups );
        when( engine.getManager( AclManager.class ) ).thenReturn( acl );

        final AuthSubsystem.Services services = AuthSubsystemBridge.rebuildFromManagers( engine );

        assertNotNull( services );
        assertSame( auth,   services.authentication() );
        assertSame( users,  services.users() );
        assertSame( groups, services.groups() );
        assertSame( acl,    services.aclManager() );
        assertNull( services.authorization() );
        assertNull( services.webAuthorizer() );
    }

    @Test
    void rebuildFromManagers_toleratesUnregisteredManagers() {
        final WikiEngine engine = mock( WikiEngine.class );
        when( engine.getWikiProperties() ).thenReturn( new Properties() );
        // No managers registered — all return null

        final AuthSubsystem.Services services = AuthSubsystemBridge.rebuildFromManagers( engine );

        assertNotNull( services );
        assertNull( services.authentication() );
        assertNull( services.users() );
    }
}
