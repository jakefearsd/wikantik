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
package com.wikantik.auth;

import com.wikantik.auth.user.DuplicateUserException;
import com.wikantik.auth.user.UserDatabase;
import com.wikantik.auth.user.UserProfile;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Self-registration (DefaultUserManager.setUserProfile) refuses a login that belonged to a deleted account (#107). */
class UserProfileValidatorDeletedLoginTest {

    @Test
    void aTombstonedLoginIsRefusedWithTheDeletedAccountKey() throws Exception {
        final UserDatabase db = mock( UserDatabase.class );
        when( db.findByLoginName( "alice" ) ).thenThrow( new NoSuchPrincipalException( "alice" ) );
        when( db.findByFullName( "Alice A" ) ).thenThrow( new NoSuchPrincipalException( "Alice A" ) );
        when( db.isLoginDeleted( "alice" ) ).thenReturn( true );
        final UserProfile profile = mock( UserProfile.class );
        when( profile.getLoginName() ).thenReturn( "alice" );
        when( profile.getFullname() ).thenReturn( "Alice A" );

        final DuplicateUserException e = assertThrows( DuplicateUserException.class,
                () -> new UserProfileValidator( null, () -> db ).assertNoDuplicate( profile, null ) );

        assertEquals( "security.error.login.deleted", e.getMessage() );
    }
}
