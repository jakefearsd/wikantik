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
package com.wikantik.auth.apikeys;

import com.wikantik.auth.apikeys.AbstractApiAccessFilter.CidrEntry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The IP allowlist is an access decision: exactly which addresses a CIDR entry admits, and which malformed entries
 * are dropped instead of widening the list.
 */
class CidrAllowlistTest {

    private static boolean admits( final String cidr, final String address ) throws UnknownHostException {
        final List< CidrEntry > entries = AbstractApiAccessFilter.parseCidrs( cidr );
        assertEquals( 1, entries.size(), "entry should parse: " + cidr );
        return AbstractApiAccessFilter.matches( InetAddress.getByName( address ).getAddress(), entries.get( 0 ) );
    }

    @ParameterizedTest( name = "{0} admits {1}: {2}" )
    @CsvSource( {
        "10.0.0.0/8,        10.255.255.255, true",
        "10.0.0.0/8,        11.0.0.0,       false",
        "10.0.0.0/8,        9.255.255.255,  false",
        "192.168.1.0/24,    192.168.1.200,  true",
        "192.168.1.0/24,    192.168.2.1,    false",
        // partial-byte prefixes: the boundary is the last address of the block
        "192.168.1.0/25,    192.168.1.127,  true",
        "192.168.1.0/25,    192.168.1.128,  false",
        "192.168.1.128/25,  192.168.1.128,  true",
        "192.168.1.128/25,  192.168.1.127,  false",
        "192.168.1.0/31,    192.168.1.1,    true",
        "192.168.1.0/31,    192.168.1.2,    false",
        "192.168.1.5/32,    192.168.1.5,    true",
        "192.168.1.5/32,    192.168.1.4,    false",
        "192.168.1.5/32,    192.168.1.6,    false",
        "172.16.0.0/12,     172.31.255.255, true",
        "172.16.0.0/12,     172.32.0.0,     false",
        "0.0.0.0/0,         8.8.8.8,        true",
        "0.0.0.0/1,         127.255.255.255, true",
        "0.0.0.0/1,         128.0.0.0,      false",
        // address family mismatch never matches
        "10.0.0.0/8,        ::1,            false",
        "fd00::/8,          10.0.0.1,       false",
        "::/0,              10.0.0.1,       false",
        // IPv6
        "fd00::/8,          fd12:3456::1,   true",
        "fd00::/8,          fe80::1,        false",
        "fd00::/7,          fdff::1,        true",
        "fd00::/7,          fe00::1,        false",
        "2001:db8::/32,     2001:db8:ffff::1, true",
        "2001:db8::/32,     2001:db9::1,    false",
        "::1/128,           ::1,            true",
        "::1/128,           ::2,            false",
    } )
    void aCidrEntryAdmitsExactlyItsBlock( final String cidr, final String address, final boolean expected ) throws Exception {
        assertEquals( expected, admits( cidr.trim(), address.trim() ) );
    }

    @ParameterizedTest( name = "{0} is dropped" )
    @CsvSource( {
        "10.0.0.0",              // no prefix length
        "10.0.0.0/",             // empty prefix
        "10.0.0.0/33",           // beyond IPv4
        "10.0.0.0/-1",           // negative
        "10.0.0.0/abc",          // not a number
        "10.0.0.0/8/9",          // too many parts
        "fd00::/129",            // beyond IPv6
        "no-such-host.invalid/8" // unresolvable network
    } )
    void aMalformedEntryIsDroppedNeverWidenedIntoAnAllowEverythingEntry( final String cidr ) {
        assertTrue( AbstractApiAccessFilter.parseCidrs( cidr ).isEmpty(), cidr );
    }

    @Test
    void theLargestPrefixLengthOfEachFamilyIsAccepted() {
        assertEquals( 1, AbstractApiAccessFilter.parseCidrs( "10.0.0.1/32" ).size() );
        assertEquals( 1, AbstractApiAccessFilter.parseCidrs( "::1/128" ).size() );
        assertEquals( 1, AbstractApiAccessFilter.parseCidrs( "10.0.0.0/0" ).size() );
    }

    @Test
    void aListKeepsEveryValidEntryInOrderSkipsBlanksAndTrimsWhitespace() {
        final List< CidrEntry > entries =
                AbstractApiAccessFilter.parseCidrs( " 10.0.0.0/8 , ,192.168.0.0/16,, bogus , 172.16.0.0 / 12 " );

        assertEquals( 3, entries.size() );
        assertEquals( 8, entries.get( 0 ).prefixLen() );
        assertEquals( 16, entries.get( 1 ).prefixLen() );
        assertEquals( 12, entries.get( 2 ).prefixLen() );
        assertEquals( ( byte ) 192, entries.get( 1 ).network()[ 0 ] );
    }

    @Test
    void nullBlankAndCommaOnlyPropertiesYieldAnEmptyList() {
        assertTrue( AbstractApiAccessFilter.parseCidrs( null ).isEmpty() );
        assertTrue( AbstractApiAccessFilter.parseCidrs( "" ).isEmpty() );
        assertTrue( AbstractApiAccessFilter.parseCidrs( "   " ).isEmpty() );
        assertTrue( AbstractApiAccessFilter.parseCidrs( " , ,, " ).isEmpty() );
    }

    @Test
    void theParsedListCannotBeModified() {
        final List< CidrEntry > entries = AbstractApiAccessFilter.parseCidrs( "10.0.0.0/8" );

        org.junit.jupiter.api.Assertions.assertThrows( UnsupportedOperationException.class,
                () -> entries.add( new CidrEntry( new byte[ 4 ], 0 ) ) );
        assertFalse( entries.isEmpty() );
    }
}
