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

import java.security.SecureRandom;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentTokensTest {

    @Test
    void generatedTokensStartWithThePrefixAndDiffer() {
        final SecureRandom random = new SecureRandom();
        final String a = AgentTokens.generate( random );
        final String b = AgentTokens.generate( random );
        assertTrue( a.startsWith( "wka_" ) );
        assertEquals( "wka_".length() + 43, a.length(), "32 random bytes as unpadded base64url" );
        assertTrue( a.substring( 4 ).matches( "[A-Za-z0-9_-]+" ) );
        assertNotEquals( a, b );
    }

    @Test
    void hashIsStableHexSha256() {
        assertEquals( "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", AgentTokens.hash( "abc" ) );
        assertEquals( AgentTokens.hash( "wka_x" ), AgentTokens.hash( "wka_x" ) );
    }

    @Test
    void aPersonalKeyPrefixIsNotAnAgentToken() {
        assertFalse( AgentTokens.looksLikeAgentToken( "wkk_abc" ) );
        assertFalse( AgentTokens.looksLikeAgentToken( null ) );
        assertFalse( AgentTokens.looksLikeAgentToken( "" ) );
        assertFalse( AgentTokens.looksLikeAgentToken( "WKA_abc" ), "the prefix is case-sensitive" );
        assertTrue( AgentTokens.looksLikeAgentToken( "wka_abc" ) );
    }
}
