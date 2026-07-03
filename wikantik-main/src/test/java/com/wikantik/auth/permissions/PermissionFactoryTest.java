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

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

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
}
