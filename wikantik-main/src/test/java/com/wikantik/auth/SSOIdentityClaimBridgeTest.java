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
package com.wikantik.auth;

import com.wikantik.TestEngine;
import com.wikantik.WikiEngine;
import com.wikantik.auth.sso.SSOLoginModule;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.Properties;

/**
 * Verifies that {@code wikantik.sso.identityClaim} is bridged into the
 * {@link SSOLoginModule} JAAS option so that the two configuration entry points
 * are consistent. The explicit JAAS option
 * ({@code wikantik.loginModule.options.sso.identityClaim}) must still win when
 * both are set.
 *
 * @since 3.1
 */
public class SSOIdentityClaimBridgeTest {

    /**
     * When only {@code wikantik.sso.identityClaim} is set, the value must be
     * bridged into the LoginModule's {@code sso.identityClaim} option.
     */
    @Test
    public void identityClaimPropertyBridgedIntoLoginModuleOptions() throws Exception {
        final Properties props = TestEngine.getTestProperties();
        props.setProperty( "wikantik.sso.enabled", "true" );
        props.setProperty( "wikantik.sso.identityClaim", "oid" );
        props.setProperty( "wikantik.baseURL", "http://localhost:8080" );

        final WikiEngine engine = new TestEngine( props );
        final DefaultAuthenticationManager mgr =
            (DefaultAuthenticationManager) engine.getManager( AuthenticationManager.class );

        Assertions.assertEquals( "oid", mgr.loginModuleOptions.get( SSOLoginModule.OPTION_IDENTITY_CLAIM ),
            "wikantik.sso.identityClaim=oid must be bridged into loginModuleOptions" );
    }

    /**
     * When both {@code wikantik.sso.identityClaim} and the explicit JAAS option
     * {@code wikantik.loginModule.options.sso.identityClaim} are set, the explicit
     * JAAS option must win (putIfAbsent semantics).
     */
    @Test
    public void explicitJaasOptionTakesPrecedenceOverBridgedProperty() throws Exception {
        final Properties props = TestEngine.getTestProperties();
        props.setProperty( "wikantik.sso.enabled", "true" );
        props.setProperty( "wikantik.sso.identityClaim", "oid" );
        props.setProperty( "wikantik.loginModule.options.sso.identityClaim", "explicit" );
        props.setProperty( "wikantik.baseURL", "http://localhost:8080" );

        final WikiEngine engine = new TestEngine( props );
        final DefaultAuthenticationManager mgr =
            (DefaultAuthenticationManager) engine.getManager( AuthenticationManager.class );

        Assertions.assertEquals( "explicit", mgr.loginModuleOptions.get( SSOLoginModule.OPTION_IDENTITY_CLAIM ),
            "Explicit JAAS option sso.identityClaim=explicit must win over the bridged property" );
    }
}
