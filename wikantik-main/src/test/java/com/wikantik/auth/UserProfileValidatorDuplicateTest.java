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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** No second account may take a login or full name an account already holds; an account may keep its own. */
class UserProfileValidatorDuplicateTest {

    private UserDatabase db;
    private UserProfileValidator validator;
    private UserProfile candidate;

    @BeforeEach
    void setUp() throws Exception {
        db = mock( UserDatabase.class );
        validator = new UserProfileValidator( null, () -> db );
        candidate = mock( UserProfile.class );
        when( candidate.getLoginName() ).thenReturn( "alice" );
        when( candidate.getFullname() ).thenReturn( "Alice A" );
        when( db.findByLoginName( "alice" ) ).thenThrow( new NoSuchPrincipalException( "alice" ) );
        when( db.findByFullName( "Alice A" ) ).thenThrow( new NoSuchPrincipalException( "Alice A" ) );
        when( db.isLoginDeleted( "alice" ) ).thenReturn( false );
    }

    @Test
    void aFreeLoginAndFullNameAreAccepted() {
        assertDoesNotThrow( () -> validator.assertNoDuplicate( candidate, null ) );
    }

    @Test
    void aLoginHeldByAnotherAccountIsRefusedAsTaken() throws Exception {
        doReturn( mock( UserProfile.class ) ).when( db ).findByLoginName( "alice" );

        final DuplicateUserException e = assertThrows( DuplicateUserException.class,
                () -> validator.assertNoDuplicate( candidate, null ) );
        assertEquals( "security.error.login.taken", e.getMessage() );
    }

    @Test
    void aLoginHeldByAnotherAccountIsRefusedEvenWhenTheCandidateEditsADifferentAccount() throws Exception {
        doReturn( mock( UserProfile.class ) ).when( db ).findByLoginName( "alice" );

        final DuplicateUserException e = assertThrows( DuplicateUserException.class,
                () -> validator.assertNoDuplicate( candidate, mock( UserProfile.class ) ) );
        assertEquals( "security.error.login.taken", e.getMessage() );
    }

    @Test
    void anAccountMayKeepItsOwnLogin() throws Exception {
        final UserProfile own = mock( UserProfile.class );
        doReturn( own ).when( db ).findByLoginName( "alice" );

        assertDoesNotThrow( () -> validator.assertNoDuplicate( candidate, own ) );
    }

    @Test
    void aFullNameHeldByAnotherAccountIsRefusedAsTaken() throws Exception {
        doReturn( mock( UserProfile.class ) ).when( db ).findByFullName( "Alice A" );

        final DuplicateUserException e = assertThrows( DuplicateUserException.class,
                () -> validator.assertNoDuplicate( candidate, null ) );
        assertEquals( "security.error.fullname.taken", e.getMessage() );
    }

    @Test
    void anAccountMayKeepItsOwnFullName() throws Exception {
        final UserProfile own = mock( UserProfile.class );
        doReturn( own ).when( db ).findByFullName( "Alice A" );

        assertDoesNotThrow( () -> validator.assertNoDuplicate( candidate, own ) );
    }

    @Test
    void aLoginThatBelongedToADeletedAccountIsRefused() throws Exception {
        when( db.isLoginDeleted( "alice" ) ).thenReturn( true );

        assertEquals( "security.error.login.deleted", assertThrows( DuplicateUserException.class,
                () -> validator.assertNoDuplicate( candidate, null ) ).getMessage() );
    }
}
