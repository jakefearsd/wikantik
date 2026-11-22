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
package com.wikantik.work;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CodeHostIdentitiesTest {

    private WorkCodeHostStore store;
    private WorkDirectory directory;
    private CodeHostIdentities identities;

    @BeforeEach
    void setUp() throws SQLException {
        store = mock( WorkCodeHostStore.class );
        directory = mock( WorkDirectory.class );
        when( store.learned( anyString(), anyString() ) ).thenReturn( Optional.empty() );
        identities = new CodeHostIdentities( store, directory );
    }

    @Test
    void anEmailMatchTeachesTheUsername() throws SQLException {
        when( directory.loginForEmail( "ana@example.com" ) ).thenReturn( Optional.of( "ana" ) );
        final CodeHostWho who = identities.resolve( "github",
            new CodeEvent.Person( "octocat", "Ana A", "ana@example.com" ) );
        assertEquals( "ana", who.login() );
        assertEquals( "octocat", who.external() );
        verify( store ).learn( "github", "octocat", "ana" );
    }

    @Test
    void aLearnedUsernameResolvesWithoutAnEmail() throws SQLException {
        when( store.learned( "github", "octocat" ) ).thenReturn( Optional.of( "ana" ) );
        final CodeHostWho who = identities.resolve( "github", new CodeEvent.Person( "octocat", null, null ) );
        assertEquals( "ana", who.login() );
        verify( directory, never() ).loginForEmail( any() );
    }

    @Test
    void aNoreplyAddressIsNeverLookedUp() throws SQLException {
        final CodeHostWho who = identities.resolve( "github",
            new CodeEvent.Person( "octocat", "O", "123+octocat@Users.Noreply.GitHub.com" ) );
        assertNull( who.login() );
        verify( directory, never() ).loginForEmail( any() );
    }

    @Test
    void noMatchKeepsTheHostName() throws SQLException {
        when( directory.loginForEmail( any() ) ).thenReturn( Optional.empty() );
        assertEquals( "octocat", identities.resolve( "github", new CodeEvent.Person( "octocat", "O", "o@x.org" ) )
            .external() );
        assertEquals( "Mona Lisa", identities.resolve( "github", new CodeEvent.Person( null, "Mona Lisa", null ) )
            .external() );
        assertEquals( "unknown", identities.resolve( "github", new CodeEvent.Person( " ", " ", null ) ).external() );
        assertEquals( "unknown", identities.resolve( "github", null ).external() );
        verify( store, never() ).learn( anyString(), anyString(), anyString() );
    }

    @Test
    void learningIsPerHost() throws SQLException {
        when( store.learned( "generic", "octocat" ) ).thenReturn( Optional.of( "ana" ) );
        assertNull( identities.resolve( "github", new CodeEvent.Person( "octocat", null, null ) ).login() );
        assertEquals( "ana", identities.resolve( "generic", new CodeEvent.Person( "octocat", null, null ) ).login() );
    }

    @Test
    void aPersonWithoutAUsernameMatchedByEmailLearnsNothing() throws SQLException {
        when( directory.loginForEmail( "ana@example.com" ) ).thenReturn( Optional.of( "ana" ) );
        assertEquals( "ana", identities.resolve( "github", new CodeEvent.Person( null, "Ana", "ana@example.com" ) ).login() );
        verify( store, never() ).learn( anyString(), anyString(), anyString() );
    }

    @Test
    void oneDeliveryLooksAPersonUpOnce() throws SQLException {
        when( directory.loginForEmail( "ana@example.com" ) ).thenReturn( Optional.of( "ana" ) );
        final CodeEvent.Person p = new CodeEvent.Person( "octocat", "Ana", "ana@example.com" );
        identities.resolve( "github", p );
        identities.resolve( "github", p );
        verify( directory, times( 1 ) ).loginForEmail( "ana@example.com" );
    }

    @Test
    void aLaterAddressNamingAnotherAccountNeverReplacesTheLearnedUsername() throws Exception {
        when( store.learned( "github", "octocat" ) ).thenReturn( Optional.of( "ana" ) );
        when( directory.loginForEmail( "mia@example.com" ) ).thenReturn( Optional.of( "mia" ) );
        final CodeHostWho[] who = new CodeHostWho[ 1 ];
        final java.util.List< String > warned = WarnCapture.of( CodeHostIdentities.class, () -> who[ 0 ] =
            identities.resolve( "github", new CodeEvent.Person( "octocat", "Mia", "mia@example.com" ) ) );
        assertEquals( "ana", who[ 0 ].login(), "first match wins" );
        verify( store, never() ).learn( anyString(), anyString(), anyString() );
        assertEquals( 1, warned.size(), "the conflicting match is reported" );
        org.junit.jupiter.api.Assertions.assertFalse( warned.get( 0 ).contains( "mia" ), "without naming the account" );
    }
}
