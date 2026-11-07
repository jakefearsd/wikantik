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
package com.wikantik.collab.adapter;

import com.wikantik.api.identity.PublicIdentity;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PublicIdentityCollabIdentityTest {

    @Test
    void resolvesTheStoredLoginToTheDisplayName() {
        final PublicIdentity pi = mock( PublicIdentity.class );
        when( pi.displayName( "alice@example.com" ) ).thenReturn( "Alice A" );
        assertEquals( "Alice A", new PublicIdentityCollabIdentity( pi ).displayName( "alice@example.com" ) );
    }

    @Test
    void neverFallsBackToTheLoginWhenResolutionYieldsNothing() {
        final PublicIdentity pi = mock( PublicIdentity.class );
        when( pi.displayName( "alice@example.com" ) ).thenReturn( null );
        assertEquals( PublicIdentity.UNKNOWN, new PublicIdentityCollabIdentity( pi ).displayName( "alice@example.com" ) );
    }

    @Test
    void neverLeaksTheLoginWhenResolutionThrows() {
        final PublicIdentity pi = mock( PublicIdentity.class );
        when( pi.displayName( "alice@example.com" ) ).thenThrow( new IllegalStateException( "db down" ) );
        assertEquals( PublicIdentity.UNKNOWN, new PublicIdentityCollabIdentity( pi ).displayName( "alice@example.com" ) );
    }
}
