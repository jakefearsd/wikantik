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

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CodeHostSecretsTest {

    private static final String SECRET = "It's a Secret to Everybody";
    private static final byte[] BODY = "Hello, World!".getBytes( StandardCharsets.UTF_8 );
    private static final String SIG = "sha256=757107ea0eb2509fc211221cce984b8a37570b6d7586c22c46f4379c8b043e17";

    @Test
    void githubsDocumentedExampleVerifies() {
        assertTrue( CodeHostSecrets.matches( SECRET, BODY, SIG ) );
    }

    @Test
    void upperCaseHexIsAccepted() {
        assertTrue( CodeHostSecrets.matches( SECRET, BODY, "sha256=" + SIG.substring( 7 ).toUpperCase() ) );
    }

    @Test
    void aWrongSecretOrBodyOrShapeIsRefused() {
        assertFalse( CodeHostSecrets.matches( "other", BODY, SIG ) );
        assertFalse( CodeHostSecrets.matches( SECRET, "Hello, World?".getBytes( StandardCharsets.UTF_8 ), SIG ) );
        assertFalse( CodeHostSecrets.matches( SECRET, BODY, "sha1=" + SIG.substring( 7, 47 ) ) );
        assertFalse( CodeHostSecrets.matches( SECRET, BODY, SIG.substring( 7 ) ) );
        assertFalse( CodeHostSecrets.matches( SECRET, BODY, SIG.substring( 0, SIG.length() - 1 ) ) );
        assertFalse( CodeHostSecrets.matches( SECRET, BODY, null ) );
        assertFalse( CodeHostSecrets.matches( SECRET, BODY, "sha256=" + "z".repeat( 64 ) ) );
    }

    @Test
    void newSecretsAreRandomHex() {
        final String a = CodeHostSecrets.newSecret();
        final String b = CodeHostSecrets.newSecret();
        assertNotEquals( a, b );
        assertEquals( 64, a.length() );
        assertTrue( a.matches( "[0-9a-f]{64}" ) );
    }
}
