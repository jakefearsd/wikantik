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
package com.wikantik.connectors.http;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.URI;

import static org.junit.jupiter.api.Assertions.*;

class EgressGuardEdgeCasesTest {

    @AfterEach
    void restoreProperty() {
        System.clearProperty( EgressGuard.PROP_ALLOW_PRIVATE );
    }

    @Test
    void nullUriAndMissingHostAreBlocked() {
        assertThrows( EgressGuard.EgressBlockedException.class, () -> EgressGuard.check( null ) );
        assertThrows( EgressGuard.EgressBlockedException.class, () -> EgressGuard.check( URI.create( "http:///path" ) ) );
    }

    @Test
    void unresolvableHostIsBlocked() {
        final var e = assertThrows( EgressGuard.EgressBlockedException.class,
                () -> EgressGuard.check( URI.create( "http://no-such-host.invalid/x" ) ) );
        assertTrue( e.getMessage().contains( "does not resolve" ) );
    }

    @Test
    void allowPrivatePropertyOptsALoopbackTargetBackIn() {
        assertThrows( EgressGuard.EgressBlockedException.class,
                () -> EgressGuard.check( URI.create( "http://127.0.0.1/" ) ) );
        System.setProperty( EgressGuard.PROP_ALLOW_PRIVATE, "true" );
        assertDoesNotThrow( () -> EgressGuard.check( URI.create( "http://127.0.0.1/" ) ) );
        // The scheme rule is not relaxed by the opt-in.
        assertThrows( EgressGuard.EgressBlockedException.class,
                () -> EgressGuard.check( URI.create( "file:///etc/passwd" ) ) );
    }

    @Test
    void ipv6UniqueLocalAndMulticastAreBlockedButGlobalIsNot() throws Exception {
        assertTrue( EgressGuard.isBlockedAddress( InetAddress.getByName( "fd00::1" ) ) );
        assertTrue( EgressGuard.isBlockedAddress( InetAddress.getByName( "fc00::1" ) ) );
        assertTrue( EgressGuard.isBlockedAddress( InetAddress.getByName( "ff02::1" ) ) );
        assertTrue( EgressGuard.isBlockedAddress( InetAddress.getByName( "0.0.0.0" ) ) );
        assertFalse( EgressGuard.isBlockedAddress( InetAddress.getByName( "2606:4700:4700::1111" ) ) );
    }

    @Test
    void redirectStatusesAreRecognised() {
        for ( final int s : new int[] { 301, 302, 303, 307, 308 } ) {
            assertTrue( EgressGuard.isRedirect( s ), "status " + s );
        }
        assertFalse( EgressGuard.isRedirect( 200 ) );
        assertFalse( EgressGuard.isRedirect( 304 ) );
    }

    @Test
    void redirectWithoutOrWithMalformedLocationIsBlocked() {
        final URI cur = URI.create( "https://93.184.216.34/a" );
        assertThrows( EgressGuard.EgressBlockedException.class, () -> EgressGuard.followsSafely( cur, null ) );
        assertThrows( EgressGuard.EgressBlockedException.class, () -> EgressGuard.followsSafely( cur, "  " ) );
        assertThrows( EgressGuard.EgressBlockedException.class, () -> EgressGuard.followsSafely( cur, "http://bad host/" ) );
    }

    @Test
    void hostOfLowercasesAndHandlesMissingHost() {
        assertEquals( "example.com", EgressGuard.hostOf( URI.create( "https://EXAMPLE.com/x" ) ) );
        assertEquals( "?", EgressGuard.hostOf( null ) );
        assertEquals( "?", EgressGuard.hostOf( URI.create( "mailto:a@b" ) ) );
    }
}
