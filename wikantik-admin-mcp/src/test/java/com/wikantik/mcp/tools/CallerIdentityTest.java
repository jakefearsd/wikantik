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
package com.wikantik.mcp.tools;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** The per-call client identity that stops concurrent clients being credited with each other's writes. */
class CallerIdentityTest {

    @Test
    void theNameIsBoundOnlyForTheDurationOfTheCall() {
        assertNull( CallerIdentity.clientName() );

        final String inside = CallerIdentity.runAs( "claude", CallerIdentity::clientName );

        assertEquals( "claude", inside );
        assertNull( CallerIdentity.clientName(), "cleared afterwards" );
    }

    @Test
    void aNullOrBlankNameBindsNothingEvenInsideAnOuterBinding() {
        final String[] seen = new String[ 3 ];
        CallerIdentity.runAs( "outer", () -> {
            seen[ 0 ] = CallerIdentity.runAs( null, CallerIdentity::clientName );
            seen[ 1 ] = CallerIdentity.runAs( "   ", CallerIdentity::clientName );
            seen[ 2 ] = CallerIdentity.clientName();
            return null;
        } );

        assertNull( seen[ 0 ], "a client that sent no name must never see the previous caller's" );
        assertNull( seen[ 1 ] );
        assertEquals( "outer", seen[ 2 ], "the outer binding is restored after the inner call" );
    }

    @Test
    void anOuterBindingIsRestoredAfterANestedNamedCall() {
        final String[] after = new String[ 1 ];
        CallerIdentity.runAs( "outer", () -> {
            CallerIdentity.runAs( "inner", () -> null );
            after[ 0 ] = CallerIdentity.clientName();
            return null;
        } );
        assertEquals( "outer", after[ 0 ] );
    }

    @Test
    void theBindingIsClearedEvenWhenTheCallThrows() {
        assertThrows( IllegalStateException.class, () -> CallerIdentity.runAs( "claude", () -> {
            throw new IllegalStateException( "boom" );
        } ) );
        assertNull( CallerIdentity.clientName() );
    }

    @Test
    void theCallsReturnValueIsPassedThrough() {
        assertEquals( Set.of( 1 ), CallerIdentity.runAs( "x", () -> Set.of( 1 ) ) );
    }
}
