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
package com.wikantik.auth.sso;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.pac4j.core.profile.CommonProfile;

import javax.security.auth.Subject;
import javax.security.auth.callback.CallbackHandler;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

class SSOLoginModuleEdgeCasesTest {

    private SSOLoginModule moduleWithClaim( final String loginClaim ) {
        final SSOLoginModule module = new SSOLoginModule();
        module.initialize( new Subject(), Mockito.mock( CallbackHandler.class ), new HashMap<>(),
                Map.of( SSOLoginModule.OPTION_CLAIM_LOGIN_NAME, loginClaim ) );
        return module;
    }

    @Test
    void resolvesFirstElementOfMultiValuedClaim() {
        final CommonProfile profile = new CommonProfile();
        profile.addAttribute( "sub", List.of( "alice", "alice-alt" ) );

        Assertions.assertEquals( "alice", moduleWithClaim( "sub" ).resolveLoginName( profile ),
            "A list-valued claim must resolve to its first element, not its toString()." );
    }

    @Test
    void firstScalarUnwrapsCollections() {
        Assertions.assertEquals( "a@b.com", SSOLoginModule.firstScalar( List.of( "a@b.com" ) ) );
        Assertions.assertEquals( "scalar", SSOLoginModule.firstScalar( "scalar" ) );
        Assertions.assertNull( SSOLoginModule.firstScalar( List.of() ) );
        Assertions.assertNull( SSOLoginModule.firstScalar( null ) );
    }
}
