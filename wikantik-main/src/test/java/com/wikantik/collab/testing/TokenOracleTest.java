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
package com.wikantik.collab.testing;

import com.wikantik.collab.model.StoredUpdate;
import com.wikantik.collab.text.ChangeSet;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TokenOracleTest {

    private static final String T1 = "⟦AAAAA⟧";
    private static final String T2 = "⟦BBBBB⟧";

    private static StoredUpdate client( final int version, final String doc, final int from, final int to ) {
        return new StoredUpdate( version, StoredUpdate.TEXT, "c1", "author-a", ChangeSet.replace( doc.length(), from, to,
            "" ) );
    }

    private static StoredUpdate server( final int version, final String doc, final int from, final int to,
                                        final String insert ) {
        return new StoredUpdate( version, StoredUpdate.SERVER, "server", null, ChangeSet.replace( doc.length(), from, to,
            insert ) );
    }

    @Test
    void tokensAreUniqueAndSnappingNeverCutsOne() {
        final Set< String > used = new HashSet<>();
        final SplittableRandom rnd = new SplittableRandom( 1 );
        final String a = TokenOracle.token( rnd, used );
        final String b = TokenOracle.token( rnd, used );
        assertNotEquals( a, b );
        assertEquals( 2, used.size() );
        final String doc = "xy" + T1 + "z";   // the token spans [2, 9)
        assertEquals( 2, TokenOracle.snap( doc, 5, true ) );
        assertEquals( 9, TokenOracle.snap( doc, 5, false ) );
        assertEquals( 2, TokenOracle.snap( doc, 2, false ), "a boundary is kept" );
        assertEquals( 9, TokenOracle.snap( doc, 9, true ) );
        assertEquals( 1, TokenOracle.snap( doc, 1, false ) );
    }

    @Test
    void aTokenTwiceInTheDraftOrAPageVersionFails() {
        final TokenOracle o = new TokenOracle();
        o.inserted( T1 );
        final AssertionError draft = assertThrows( AssertionError.class, () -> o.checkDraft( T1 + "x" + T1, 1, List.of() ) );
        assertTrue( draft.getMessage().contains( "AAAAA appears 2 times in the draft" ), draft.getMessage() );
        final AssertionError page = assertThrows( AssertionError.class, () -> o.checkVersion( "P version 3", T1 + T1 ) );
        assertTrue( page.getMessage().contains( "in P version 3" ), page.getMessage() );
    }

    @Test
    void aClientsDeleteRemovesATokenButAServerUpdateMayNotUnlessThePageLostIt() {
        final TokenOracle o = new TokenOracle();
        o.inserted( T1 );
        o.inserted( T2 );
        final String doc = T1 + T2;
        o.checkDraft( doc, 0, List.of() );
        // a client deletes T1: legitimate
        final String afterClient = T2;
        assertDoesNotThrow( () -> o.checkDraft( afterClient, 1, List.of( client( 1, doc, 0, 7 ) ) ) );
        // a server update (a merge) drops T2 although no outside save deleted it: text lost
        final AssertionError lost = assertThrows( AssertionError.class,
            () -> o.checkDraft( "", 2, List.of( server( 2, afterClient, 0, 7, "" ) ) ) );
        assertTrue( lost.getMessage().contains( "a server update dropped [BBBBB]" ), lost.getMessage() );
    }

    @Test
    void aMergeMayDropATokenAnOutsideSaveDeleted() {
        final TokenOracle o = new TokenOracle();
        o.inserted( T1 );
        o.checkDraft( T1, 0, List.of() );
        o.edited( "page " + T1, "page " );   // the outside save took it out of the page
        assertDoesNotThrow( () -> o.checkDraft( "", 1, List.of( server( 1, T1, 0, 7, "" ) ) ) );
    }

    @Test
    void aLogThatDoesNotAccountForTheChangeIsNotJudged() {
        final TokenOracle o = new TokenOracle();
        o.inserted( T1 );
        o.checkDraft( T1, 3, List.of() );
        assertDoesNotThrow( () -> o.checkDraft( "", 5, List.of() ), "a truncated gap: the loss cannot be attributed" );
    }

    @Test
    void theDocumentedCrashWindowAllowsTheRewrittenSavesTokensTwice() {
        final TokenOracle o = new TokenOracle();
        o.inserted( T1 );
        o.inserted( T2 );
        o.residualM1( "base " + T1, "base " );
        assertDoesNotThrow( () -> o.checkDraft( T1 + T1, 1, List.of() ) );
        assertThrows( AssertionError.class, () -> o.checkVersion( "P version 2", T2 + T2 ), "only the window's tokens" );
    }
}
