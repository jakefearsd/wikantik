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
package com.wikantik.rest;

import com.wikantik.api.core.Session;
import com.wikantik.api.querylog.ActorType;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RetrievalActorClassifierTest {

    private static HttpServletRequest reqWithAuth( final String authHeader ) {
        final HttpServletRequest req = mock( HttpServletRequest.class );
        when( req.getHeader( "Authorization" ) ).thenReturn( authHeader );
        return req;
    }

    private static Session session( final boolean authenticated ) {
        final Session s = mock( Session.class );
        when( s.isAuthenticated() ).thenReturn( authenticated );
        return s;
    }

    @Test
    void authorizationHeaderPresent_isAgent() {
        // Basic/Bearer = programmatic (curl/CLI/agent). Wins even over an authenticated session.
        assertEquals( ActorType.AGENT,
            RetrievalActorClassifier.classify( reqWithAuth( "Basic dXNlcjpwdw==" ), session( true ) ) );
    }

    @Test
    void authenticatedSessionNoAuthHeader_isHuman() {
        // The SPA authenticates by cookie, not Authorization header.
        assertEquals( ActorType.HUMAN,
            RetrievalActorClassifier.classify( reqWithAuth( null ), session( true ) ) );
    }

    @Test
    void anonymousSession_isUnknown() {
        assertEquals( ActorType.UNKNOWN,
            RetrievalActorClassifier.classify( reqWithAuth( null ), session( false ) ) );
    }

    @Test
    void nullSession_isUnknown() {
        assertEquals( ActorType.UNKNOWN,
            RetrievalActorClassifier.classify( reqWithAuth( null ), null ) );
    }
}
