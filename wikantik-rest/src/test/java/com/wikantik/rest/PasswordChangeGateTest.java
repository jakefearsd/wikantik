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

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PasswordChangeGateTest {

    @ParameterizedTest
    @ValueSource( booleans = { true, false } )
    void cachingWritesTheFlagToTheExistingHttpSession( final boolean flag ) {
        final HttpServletRequest request = mock( HttpServletRequest.class );
        final HttpSession session = mock( HttpSession.class );
        when( request.getSession( false ) ).thenReturn( session );

        PasswordChangeGate.cache( request, flag );

        verify( session ).setAttribute( PasswordChangeGate.SESSION_ATTRIBUTE, flag );
    }

    @Test
    void cachingNeverCreatesASession() {
        final HttpServletRequest request = mock( HttpServletRequest.class );

        assertDoesNotThrow( () -> PasswordChangeGate.cache( request, true ) );

        verify( request, never() ).getSession();
        verify( request, never() ).getSession( true );
    }

    @ParameterizedTest
    @ValueSource( booleans = { true, false } )
    void aCachedFlagIsAnsweredWithoutConsultingTheUserStore( final boolean flag ) {
        final HttpServletRequest request = mock( HttpServletRequest.class );
        final HttpSession session = mock( HttpSession.class );
        when( request.getSession( false ) ).thenReturn( session );
        when( session.getAttribute( PasswordChangeGate.SESSION_ATTRIBUTE ) ).thenReturn( flag );

        // a null engine would throw if the store were consulted
        assertEquals( flag, PasswordChangeGate.mustChangePassword( null, request, "alice" ) );
    }
}
