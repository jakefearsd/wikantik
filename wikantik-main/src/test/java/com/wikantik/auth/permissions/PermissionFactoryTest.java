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
package com.wikantik.auth.permissions;

import com.wikantik.api.core.Page;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PermissionFactoryTest {

    /**
     * "Aa" and "BB" have identical String hashCodes. The legacy XOR-hashcode key
     * made the second lookup return the FIRST page's cached permission — a real
     * authorization-object mixup, admitted by the old FIXME.
     */
    @Test
    void collidingHashCodesYieldDistinctPermissions() {
        assertEquals( "Aa".hashCode(), "BB".hashCode(), "fixture precondition" );

        final PagePermission pa = PermissionFactory.getPagePermission( "Aa", "view" );
        final PagePermission pb = PermissionFactory.getPagePermission( "BB", "view" );

        assertEquals( "Aa", pa.getPage() );
        assertEquals( "BB", pb.getPage() );
    }

    @Test
    void sameKeyReturnsCachedInstance() {
        assertSame( PermissionFactory.getPagePermission( "PermissionFactoryTestPage", "view" ),
                    PermissionFactory.getPagePermission( "PermissionFactoryTestPage", "view" ) );
    }

    /**
     * The space-joined cache key ("wiki + ' ' + page + ' ' + actions") is ambiguous at the
     * wiki/page boundary: wiki="My Wiki", page="Test" and wiki="My", page="Wiki Test" both
     * produce the key "My Wiki Test view" — the second lookup wrongly returns the first
     * page's cached PagePermission, the same wrong-permission bug class the Caffeine
     * migration (commit fdf52398f1) was meant to eliminate.
     */
    @Test
    void wikiPageBoundaryIsUnambiguous() {
        final Page p1 = mock( Page.class );
        when( p1.getWiki() ).thenReturn( "My Wiki" );
        when( p1.getName() ).thenReturn( "Test" );
        final Page p2 = mock( Page.class );
        when( p2.getWiki() ).thenReturn( "My" );
        when( p2.getName() ).thenReturn( "Wiki Test" );

        final PagePermission a = PermissionFactory.getPagePermission( p1, "view" );
        final PagePermission b = PermissionFactory.getPagePermission( p2, "view" );

        assertNotSame( a, b );
        assertEquals( "My Wiki", a.getWiki() );
        assertEquals( "Test", a.getPage() );
        assertEquals( "My", b.getWiki() );
        assertEquals( "Wiki Test", b.getPage() );
    }
}
