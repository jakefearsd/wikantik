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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetAddress;
import java.net.URI;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Range-edge coverage of the SSRF policy: every blocked range at its first/last address, and just outside it. */
class EgressGuardBoundaryTest {

    private static boolean blocked( final String literal ) throws Exception {
        return EgressGuard.isBlockedAddress( InetAddress.getByName( literal ) );
    }

    @ParameterizedTest
    @ValueSource( strings = {
            "10.0.0.0", "10.0.0.1", "10.255.255.255",
            "172.16.0.0", "172.20.1.1", "172.31.255.255",
            "192.168.0.0", "192.168.255.255",
            "127.0.0.1", "127.0.0.0", "127.255.255.255", "127.1.2.3",
            "169.254.0.0", "169.254.169.254", "169.254.255.255",
            "0.0.0.0",
            "224.0.0.1", "239.255.255.255",
            "::", "::1",
            "fc00::", "fc00::1", "fd12:3456:789a::1", "fdff:ffff:ffff:ffff:ffff:ffff:ffff:ffff",
            "fe80::1", "febf::1",
            "ff02::1", "ff0e::1",
            "::ffff:127.0.0.1", "::ffff:10.1.2.3", "::ffff:192.168.0.1", "::ffff:169.254.169.254",
            "::ffff:172.16.0.1", "::ffff:0.0.0.0",
    } )
    void internalAddressesAreBlocked( final String literal ) throws Exception {
        assertTrue( blocked( literal ), literal + " must be blocked" );
    }

    @ParameterizedTest
    @ValueSource( strings = {
            // "this network" 0.0.0.0/8, carrier-grade NAT 100.64.0.0/10 (cloud metadata e.g. 100.100.100.200, Tailscale)
            "0.0.0.1", "0.1.2.3", "0.255.255.255",
            "100.64.0.0", "100.100.100.200", "100.127.255.255",
            // IPv6 forms that smuggle an IPv4 target: IPv4-compatible, NAT64, 6to4, IPv6 site-local
            "::127.0.0.1", "::10.0.0.1", "::7f00:1", "64:ff9b::7f00:1", "64:ff9b::a9fe:a9fe", "64:ff9b::c0a8:1",
            "2002:7f00:1::1", "2002:a9fe:a9fe::1", "2002:0a00:0001::", "fec0::1",
    } )
    void rangesSmuggledPastTheJdkFlagsAreBlocked( final String literal ) throws Exception {
        assertTrue( blocked( literal ), literal + " must be blocked" );
    }

    @ParameterizedTest
    @ValueSource( strings = {
            "100.63.255.255", "100.128.0.0", "1.0.0.0",
            "64:ff9b::808:808", "2002:808:808::1", "::808:808",
    } )
    void neighboursOfTheNewRangesAndEmbeddedPublicV4StayAllowed( final String literal ) throws Exception {
        assertFalse( blocked( literal ), literal + " must stay reachable" );
    }

    @ParameterizedTest
    @ValueSource( strings = {
            "9.255.255.255", "11.0.0.0",
            "172.15.255.255", "172.32.0.0",
            "192.167.255.255", "192.169.0.0",
            "126.255.255.255", "128.0.0.1",
            "169.253.255.255", "169.255.0.0",
            "223.255.255.255",
            "8.8.8.8", "1.1.1.1",
            "fbff:ffff::1", "fe00::1",
            "2001:4860:4860::8888", "2606:4700:4700::1111",
            "::ffff:8.8.8.8",
    } )
    void publicAddressesJustOutsideTheRangesAreAllowed( final String literal ) throws Exception {
        assertFalse( blocked( literal ), literal + " is public and must stay reachable" );
    }

    @ParameterizedTest
    @ValueSource( strings = {
            "http://10.0.0.1/", "http://172.16.0.1/", "http://172.31.255.255/", "http://192.168.0.1/",
            "http://127.0.0.1/", "http://127.9.9.9:8080/", "http://169.254.169.254/", "http://0.0.0.0/",
            "http://[::1]/", "http://[fc00::1]/", "http://[fe80::1]/", "http://[::ffff:127.0.0.1]/",
            "http://[::ffff:10.0.0.1]/", "http://[ff02::1]/", "https://[fd00::1]:8443/x",
            // numeric forms the URL parser hands to the resolver
            "http://2130706433/", "http://0x7f000001/", "http://127.1/",
            "HTTP://127.0.0.1/",
    } )
    void checkRejectsInternalTargets( final String url ) {
        assertThrows( EgressGuard.EgressBlockedException.class, () -> EgressGuard.check( URI.create( url ) ),
                url + " must be blocked" );
    }

    @ParameterizedTest
    @ValueSource( strings = {
            "http://172.15.255.255/", "http://172.32.0.1/", "http://11.0.0.1/", "https://8.8.8.8/",
            "HTTPS://8.8.8.8/", "http://[2001:4860:4860::8888]/",
            // the JDK resolver (the one the fetch also uses) reads a leading zero as decimal: 177.0.0.1, public
            "http://0177.0.0.1/",
    } )
    void checkAllowsPublicTargets( final String url ) throws Exception {
        EgressGuard.check( URI.create( url ) );
    }

    @ParameterizedTest
    @ValueSource( strings = {
            "file:///etc/passwd", "ftp://8.8.8.8/", "gopher://8.8.8.8/", "jar:http://8.8.8.8/x!/", "ws://8.8.8.8/",
            "data:text/plain,x", "javascript:alert(1)", "//8.8.8.8/x", "httpx://8.8.8.8/", "ldap://8.8.8.8/",
    } )
    void checkRejectsNonHttpSchemesEvenForPublicHosts( final String url ) {
        assertThrows( EgressGuard.EgressBlockedException.class, () -> EgressGuard.check( URI.create( url ) ),
                url + " must be blocked" );
    }

    @ParameterizedTest
    @ValueSource( strings = {
            "http://169.254.169.254/", "http://127.0.0.1:8080/admin", "http://10.0.0.1/", "http://[::1]/",
            "http://[fd00::1]/", "http://[::ffff:192.168.1.1]/", "file:///etc/passwd", "gopher://8.8.8.8/",
            "//127.0.0.1/", "http:%2F%2F127.0.0.1",
    } )
    void everyRedirectHopIsRevalidatedAgainstAPublicOrigin( final String location ) {
        final URI origin = URI.create( "https://8.8.8.8/start" );
        assertThrows( EgressGuard.EgressBlockedException.class, () -> EgressGuard.followsSafely( origin, location ),
                "redirect to " + location + " must be blocked" );
    }

    @ParameterizedTest
    @ValueSource( strings = { "/a", "b/c", "../d", "?q=1", "https://1.1.1.1/z" } )
    void safeRedirectsResolveAgainstTheCurrentUrl( final String location ) throws Exception {
        final URI origin = URI.create( "https://8.8.8.8/x/y" );
        assertEquals( origin.resolve( location ), EgressGuard.followsSafely( origin, location ) );
    }

    @ParameterizedTest
    @ValueSource( strings = { "301", "302", "303", "307", "308" } )
    void redirectStatusesAreFollowed( final int status ) {
        assertTrue( EgressGuard.isRedirect( status ) );
    }

    @ParameterizedTest
    @ValueSource( strings = { "200", "204", "300", "304", "305", "306", "309", "400", "404", "500", "0", "-1" } )
    void nonRedirectStatusesAreNotFollowed( final int status ) {
        assertFalse( EgressGuard.isRedirect( status ) );
    }
}
