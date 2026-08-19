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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetAddress;
import java.net.URI;

import org.junit.jupiter.api.Test;

class EgressGuardTest {

    @Test
    void blocksLoopback() {
        assertThrows( EgressGuard.EgressBlockedException.class,
                () -> EgressGuard.check( URI.create( "http://127.0.0.1/x" ) ) );
        assertThrows( EgressGuard.EgressBlockedException.class,
                () -> EgressGuard.check( URI.create( "http://localhost/x" ) ) );
    }

    @Test
    void blocksCloudMetadataAndLinkLocal() {
        assertThrows( EgressGuard.EgressBlockedException.class,
                () -> EgressGuard.check( URI.create( "http://169.254.169.254/latest/meta-data/" ) ) );
    }

    @Test
    void blocksPrivateRanges() {
        for ( final String u : new String[] {
                "http://10.0.0.5/", "http://192.168.1.1/", "http://172.16.4.4/" } ) {
            assertThrows( EgressGuard.EgressBlockedException.class,
                    () -> EgressGuard.check( URI.create( u ) ), "must block " + u );
        }
    }

    @Test
    void blocksNonHttpSchemes() {
        for ( final String u : new String[] {
                "file:///etc/passwd", "gopher://x/", "ftp://host/" } ) {
            assertThrows( EgressGuard.EgressBlockedException.class,
                    () -> EgressGuard.check( URI.create( u ) ), "must block scheme of " + u );
        }
    }

    @Test
    void allowsAPublicLiteralAddress() {
        // 8.8.8.8 is a public literal — no DNS, deterministic, and not in any blocked range.
        assertDoesNotThrow( () -> EgressGuard.check( URI.create( "https://8.8.8.8/" ) ) );
    }

    @Test
    void redirectToInternalIsRejected() {
        final URI current = URI.create( "https://8.8.8.8/start" );
        assertThrows( EgressGuard.EgressBlockedException.class,
                () -> EgressGuard.followsSafely( current, "http://169.254.169.254/" ),
                "a redirect to cloud metadata must be blocked even from a public origin" );
    }

    @Test
    void redirectToPublicIsAllowedAndResolvesRelative() throws Exception {
        final URI current = URI.create( "https://8.8.8.8/a/b" );
        final URI next = EgressGuard.followsSafely( current, "/c" );
        assertTrue( next.toString().endsWith( "/c" ) );
    }

    @Test
    void ipv4MappedLoopbackIsBlocked() throws Exception {
        // ::ffff:127.0.0.1 must be recognised as loopback, not treated as a routable v6 host.
        final InetAddress mapped = InetAddress.getByName( "::ffff:127.0.0.1" );
        assertTrue( EgressGuard.isBlockedAddress( mapped ), "IPv4-mapped loopback must be blocked" );
    }
}
