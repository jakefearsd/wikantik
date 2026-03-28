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
package com.wikantik.event;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.security.Principal;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class WikiSecurityEventTest {

    private static final Principal TEST_PRINCIPAL = () -> "TestUser";
    private static final Object TEST_SOURCE = new Object();

    static Stream<Arguments> eventTypes() {
        return Stream.of(
                Arguments.of( WikiSecurityEvent.LOGIN_AUTHENTICATED, "LOGIN_AUTHENTICATED", "login authenticated" ),
                Arguments.of( WikiSecurityEvent.LOGIN_ACCOUNT_EXPIRED, "LOGIN_ACCOUNT_EXPIRED", "login failed: expired account" ),
                Arguments.of( WikiSecurityEvent.LOGIN_CREDENTIAL_EXPIRED, "LOGIN_ACCOUNT_EXPIRED", "login failed: credential expired" ),
                Arguments.of( WikiSecurityEvent.LOGIN_FAILED, "LOGIN_FAILED", "login failed" ),
                Arguments.of( WikiSecurityEvent.LOGOUT, "LOGOUT", "user logged out" ),
                Arguments.of( WikiSecurityEvent.PRINCIPAL_ADD, "PRINCIPAL_ADD", "new principal added" ),
                Arguments.of( WikiSecurityEvent.SESSION_EXPIRED, "SESSION_EXPIRED", "session expired" ),
                Arguments.of( WikiSecurityEvent.GROUP_ADD, "GROUP_ADD", "new group added" ),
                Arguments.of( WikiSecurityEvent.GROUP_REMOVE, "GROUP_REMOVE", "group removed" ),
                Arguments.of( WikiSecurityEvent.GROUP_CLEAR_GROUPS, "GROUP_CLEAR_GROUPS", "all groups cleared" ),
                Arguments.of( WikiSecurityEvent.ACCESS_ALLOWED, "ACCESS_ALLOWED", "access allowed" ),
                Arguments.of( WikiSecurityEvent.ACCESS_DENIED, "ACCESS_DENIED", "access denied" ),
                Arguments.of( WikiSecurityEvent.PROFILE_NAME_CHANGED, "PROFILE_NAME_CHANGED", "user profile name changed" ),
                Arguments.of( WikiSecurityEvent.PROFILE_SAVE, "PROFILE_SAVE", "user profile saved" )
        );
    }

    @ParameterizedTest( name = "eventName({0}) = {1}" )
    @MethodSource( "eventTypes" )
    void testEventName( final int type, final String expectedName, final String expectedDesc ) {
        final WikiSecurityEvent event = new WikiSecurityEvent( TEST_SOURCE, type, TEST_PRINCIPAL, null );
        assertEquals( expectedName, event.eventName( type ) );
    }

    @ParameterizedTest( name = "getTypeDescription({0}) = {2}" )
    @MethodSource( "eventTypes" )
    void testGetTypeDescription( final int type, final String expectedName, final String expectedDesc ) {
        final WikiSecurityEvent event = new WikiSecurityEvent( TEST_SOURCE, type, TEST_PRINCIPAL, null );
        assertEquals( expectedDesc, event.getTypeDescription() );
    }

    @Test
    void testIsValidType() {
        assertTrue( WikiSecurityEvent.isValidType( WikiSecurityEvent.LOGIN_AUTHENTICATED ) );
        assertTrue( WikiSecurityEvent.isValidType( WikiSecurityEvent.ACCESS_DENIED ) );
        assertTrue( WikiSecurityEvent.isValidType( WikiSecurityEvent.PROFILE_SAVE ) );
        // Note: isValidType uses a range check in the parent class, so many ints are "valid"
    }

    @Test
    void testConstructorAndAccessors() {
        final Object target = "TestPage";
        final WikiSecurityEvent event = new WikiSecurityEvent( TEST_SOURCE, WikiSecurityEvent.ACCESS_ALLOWED, TEST_PRINCIPAL, target );

        assertEquals( WikiSecurityEvent.ACCESS_ALLOWED, event.getType() );
        assertEquals( TEST_PRINCIPAL, event.getPrincipal() );
        assertEquals( target, event.getTarget() );
        assertEquals( TEST_SOURCE, event.getSource() );
    }

    @Test
    void testToStringContainsPrincipalAndTarget() {
        final WikiSecurityEvent event = new WikiSecurityEvent( TEST_SOURCE, WikiSecurityEvent.ACCESS_DENIED, TEST_PRINCIPAL, "SecretPage" );
        final String str = event.toString();
        assertTrue( str.contains( "TestUser" ), "toString should include principal name" );
        assertTrue( str.contains( "SecretPage" ), "toString should include target" );
    }

    @Test
    void testToStringWithNullPrincipal() {
        final WikiSecurityEvent event = new WikiSecurityEvent( TEST_SOURCE, WikiSecurityEvent.SESSION_EXPIRED, null, null );
        // Should not throw NPE
        assertNotNull( event.toString() );
    }
}
