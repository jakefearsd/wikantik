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

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * The address half of {@link EgressGuard}: which resolved addresses a connector must never reach (loopback, any-local,
 * link-local, site-local, multicast, IPv6 unique-local, 0.0.0.0/8, carrier-grade NAT 100.64.0.0/10, and IPv4 targets
 * carried inside IPv6 by the IPv4-compatible, NAT64 and 6to4 forms).
 */
final class BlockedAddresses {

    private BlockedAddresses() {
    }

    /** True for any address a connector must never reach. */
    static boolean isBlocked( final InetAddress a ) {
        if ( a.isLoopbackAddress()      // 127.0.0.0/8, ::1
                || a.isAnyLocalAddress() // 0.0.0.0, ::
                || a.isLinkLocalAddress()// 169.254.0.0/16 (incl. 169.254.169.254 metadata), fe80::/10
                || a.isSiteLocalAddress()// 10/8, 172.16/12, 192.168/16
                || a.isMulticastAddress() ) {
            return true;
        }
        final byte[] b = a.getAddress();
        if ( a instanceof Inet4Address ) {
            return isReservedIpv4( b );
        }
        if ( b.length != 16 ) {
            return false;
        }
        // IPv6 unique-local addresses fc00::/7 are private but not flagged by isSiteLocalAddress.
        if ( ( b[ 0 ] & 0xFE ) == 0xFC ) {
            return true;
        }
        // IPv6 forms that embed an IPv4 target the JDK flags do not look through.
        final byte[] embedded = embeddedIpv4( b );
        return embedded != null && isBlocked( toInet4( embedded ) );
    }

    /** 0.0.0.0/8 ("this network") and 100.64.0.0/10 (carrier-grade NAT; some clouds put metadata here). */
    private static boolean isReservedIpv4( final byte[] b ) {
        final int first = b[ 0 ] & 0xFF;
        return first == 0 || ( first == 100 && ( b[ 1 ] & 0xC0 ) == 0x40 );
    }

    /** The IPv4 address carried by an IPv4-compatible (::/96), NAT64 (64:ff9b::/96) or 6to4 (2002::/16) address, else null. */
    private static byte[] embeddedIpv4( final byte[] b ) {
        if ( allZero( b, 0, 12 ) ) {
            return java.util.Arrays.copyOfRange( b, 12, 16 );
        }
        if ( b[ 0 ] == 0x00 && b[ 1 ] == 0x64 && ( b[ 2 ] & 0xFF ) == 0xFF && ( b[ 3 ] & 0xFF ) == 0x9B && allZero( b, 4, 12 ) ) {
            return java.util.Arrays.copyOfRange( b, 12, 16 );
        }
        if ( b[ 0 ] == 0x20 && b[ 1 ] == 0x02 ) {
            return java.util.Arrays.copyOfRange( b, 2, 6 );
        }
        return null;
    }

    private static boolean allZero( final byte[] b, final int from, final int to ) {
        for ( int i = from; i < to; i++ ) {
            if ( b[ i ] != 0 ) {
                return false;
            }
        }
        return true;
    }

    private static InetAddress toInet4( final byte[] v4 ) {
        try {
            return InetAddress.getByAddress( v4 );
        } catch ( final UnknownHostException e ) {
            // cannot happen for a 4-byte array; fail closed anyway
            throw new IllegalStateException( "4-byte address rejected", e );
        }
    }

}
