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
package com.wikantik.auth.identity;

import com.wikantik.auth.UserManager;
import com.wikantik.auth.user.UserDatabase;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PublicIdentityHolderTest {

    private static UserManager managerOver( final UserDatabase db ) {
        final UserManager users = mock( UserManager.class );
        when( users.getUserDatabase() ).thenReturn( db );
        return users;
    }

    @Test
    void theSameUserManagerAlwaysGetsTheSameIdentityAndIsListenedToOnce() {
        final PublicIdentityHolder holder = new PublicIdentityHolder();
        final UserManager users = managerOver( mock( UserDatabase.class ) );

        final DefaultPublicIdentity first = holder.forUsers( users );
        assertSame( first, holder.forUsers( users ) );
        assertSame( first, holder.forUsers( users ) );

        verify( users, times( 1 ) ).addWikiEventListener( any( DefaultPublicIdentity.class ) );
    }

    @Test
    void aManagerOverTheSameDatabaseKeepsTheIdentityButIsListenedToAsWell() {
        final PublicIdentityHolder holder = new PublicIdentityHolder();
        final UserDatabase db = mock( UserDatabase.class );
        final UserManager before = managerOver( db );
        final UserManager after = managerOver( db );

        final DefaultPublicIdentity identity = holder.forUsers( before );

        assertSame( identity, holder.forUsers( after ), "same accounts, same snapshot" );
        verify( after ).addWikiEventListener( identity );   // its profile events must reach the identity too
    }

    @Test
    void aDifferentDatabaseGetsAFreshIdentity() {
        final PublicIdentityHolder holder = new PublicIdentityHolder();
        final UserManager users = mock( UserManager.class );
        when( users.getUserDatabase() ).thenReturn( mock( UserDatabase.class ), mock( UserDatabase.class ) );

        final DefaultPublicIdentity first = holder.forUsers( users );
        assertNotSame( first, holder.forUsers( users ), "a snapshot of another account table must not be reused" );
    }
}
