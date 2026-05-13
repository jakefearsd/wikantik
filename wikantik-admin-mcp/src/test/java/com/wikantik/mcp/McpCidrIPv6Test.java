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
package com.wikantik.mcp;

import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exhaustive coverage for IPv6 CIDR matching on McpAccessFilter.
 *
 * <p>The matching code uses InetAddress.getByName() (which returns either an
 * Inet4Address or Inet6Address backed by a 4-byte or 16-byte array) and
 * manual bit-shifting against the prefix length. These tests verify that
 * symmetric math works correctly for both address widths.</p>
 */
public class McpCidrIPv6Test {

    @Test
    void exactHost128MatchesItself() throws Exception {
        final var cidrs = McpAccessFilter.parseCidrs( "::1/128" );
        assertTrue( matches( cidrs, "::1" ) );
        assertFalse( matches( cidrs, "::2" ) );
    }

    @Test
    void prefix32MatchesAddressesInRange() throws Exception {
        final var cidrs = McpAccessFilter.parseCidrs( "2001:db8::/32" );
        assertTrue( matches( cidrs, "2001:db8::1" ) );
        assertTrue( matches( cidrs, "2001:db8:abcd:ef01::42" ) );
        assertFalse( matches( cidrs, "2001:db9::1" ) );
        assertFalse( matches( cidrs, "fe80::1" ) );
    }

    @Test
    void linkLocalPrefix10Matches() throws Exception {
        final var cidrs = McpAccessFilter.parseCidrs( "fe80::/10" );
        assertTrue( matches( cidrs, "fe80::1" ) );
        assertTrue( matches( cidrs, "febf:ffff:ffff:ffff::1" ) );
        assertFalse( matches( cidrs, "fec0::1" ) );
        assertFalse( matches( cidrs, "::1" ) );
    }

    @Test
    void prefix0MatchesEverythingForItsFamily() throws Exception {
        final var v6All = McpAccessFilter.parseCidrs( "::/0" );
        assertTrue( matches( v6All, "::1" ) );
        assertTrue( matches( v6All, "2001:db8::42" ) );
        assertFalse( matches( v6All, "10.0.0.1" ) );
    }

    @Test
    void prefix127IsPenultimateBoundary() throws Exception {
        final var cidrs = McpAccessFilter.parseCidrs( "2001:db8::/127" );
        assertTrue( matches( cidrs, "2001:db8::0" ) );
        assertTrue( matches( cidrs, "2001:db8::1" ) );
        assertFalse( matches( cidrs, "2001:db8::2" ) );
    }

    @Test
    void mixedV4AndV6AllowlistRoutesByFamily() throws Exception {
        final var cidrs = McpAccessFilter.parseCidrs( "10.0.0.0/8, 2001:db8::/32" );
        assertTrue( matches( cidrs, "10.5.5.5" ) );
        assertTrue( matches( cidrs, "2001:db8::1" ) );
        assertFalse( matches( cidrs, "192.168.1.1" ) );
        assertFalse( matches( cidrs, "fe80::1" ) );
    }

    @Test
    void v4CallerAgainstV6CidrDoesNotMatch() throws Exception {
        final var cidrs = McpAccessFilter.parseCidrs( "::/0" );
        assertFalse( matches( cidrs, "10.0.0.1" ),
                "/0 IPv6 must not silently match a 4-byte IPv4 address" );
    }

    @Test
    void v6CallerAgainstV4CidrDoesNotMatch() throws Exception {
        final var cidrs = McpAccessFilter.parseCidrs( "0.0.0.0/0" );
        assertFalse( matches( cidrs, "::1" ),
                "/0 IPv4 must not silently match a 16-byte IPv6 address" );
    }

    private static boolean matches( final List< McpAccessFilter.CidrEntry > cidrs,
                                    final String addr ) throws Exception {
        final byte[] bytes = InetAddress.getByName( addr ).getAddress();
        for ( final var c : cidrs ) {
            if ( McpAccessFilter.matches( bytes, c ) ) return true;
        }
        return false;
    }
}
