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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The post-SSO return path is attacker-reachable input ({@code /sso/login?return=…}), so only a
 * same-origin path inside the wiki's own context may survive; everything else falls back.
 */
class SsoReturnPathTest {

    @Test
    void acceptsAPagePathInTheRootContext() {
        assertEquals( Optional.of( "/wiki/SomePage" ), SsoReturnPath.sanitize( "/wiki/SomePage", "" ) );
    }

    @Test
    void keepsQueryAndFragment() {
        assertEquals( Optional.of( "/edit/SomePage?section=2#intro" ),
                SsoReturnPath.sanitize( "/edit/SomePage?section=2#intro", "" ) );
    }

    @Test
    void acceptsAPathInsideANamedContext() {
        assertEquals( Optional.of( "/JSPWiki/wiki/SomePage" ),
                SsoReturnPath.sanitize( "/JSPWiki/wiki/SomePage", "/JSPWiki" ) );
    }

    @Test
    void rejectsAPathOutsideANamedContext() {
        assertEquals( Optional.empty(), SsoReturnPath.sanitize( "/other/wiki/SomePage", "/JSPWiki" ) );
        assertEquals( Optional.empty(), SsoReturnPath.sanitize( "/JSPWikiEvil/x", "/JSPWiki" ) );
    }

    @ParameterizedTest
    @ValueSource( strings = {
            "", " ", "wiki/Relative", "https://evil.example/", "//evil.example/x", "/\\evil.example",
            "/wiki/A\\B", "/wiki/A\r\nSet-Cookie: x=1", "/wiki/A\tB",
            "/login", "/login?error=x", "/sso/login", "/sso/callback?code=1", "/logout",
            "/Login", "/SSO/login", "/wiki/../login", "/wiki/./x", "/.."
    } )
    void rejectsUnsafeOrLoopingTargets( final String raw ) {
        assertEquals( Optional.empty(), SsoReturnPath.sanitize( raw, "" ) );
    }

    @Test
    void dotsInsideNamesOrTheQueryAreFine() {
        assertEquals( Optional.of( "/wiki/Version2.7?from=../x" ), SsoReturnPath.sanitize( "/wiki/Version2.7?from=../x", "" ) );
        assertEquals( Optional.of( "/wiki/LoginTroubleshooting" ), SsoReturnPath.sanitize( "/wiki/LoginTroubleshooting", "" ) );
    }

    @Test
    void rejectsNullAndOverlongInput() {
        assertEquals( Optional.empty(), SsoReturnPath.sanitize( null, "" ) );
        assertEquals( Optional.empty(), SsoReturnPath.sanitize( "/wiki/" + "a".repeat( 2100 ), "" ) );
    }
}
