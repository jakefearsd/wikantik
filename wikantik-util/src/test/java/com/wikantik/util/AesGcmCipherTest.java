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
package com.wikantik.util;

import org.junit.jupiter.api.Test;
import javax.crypto.SecretKey;
import java.security.SecureRandom;
import java.util.Base64;
import static org.junit.jupiter.api.Assertions.*;

class AesGcmCipherTest {
    private static SecretKey randomKey() {
        byte[] k = new byte[32];
        new SecureRandom().nextBytes( k );
        return AesGcmCipher.keyFromBase64( Base64.getEncoder().encodeToString( k ) );
    }

    @Test void roundTrips() throws Exception {
        AesGcmCipher c = new AesGcmCipher( randomKey() );
        String secret = "ghp_ExampleGitHubToken_1234567890";
        assertEquals( secret, c.decrypt( c.encrypt( secret ) ) );
    }

    @Test void ivIsRandomSoCiphertextsDiffer() {
        AesGcmCipher c = new AesGcmCipher( randomKey() );
        assertNotEquals( c.encrypt( "same" ), c.encrypt( "same" ) );   // random IV per encrypt
    }

    @Test void tamperedTokenFailsToDecrypt() {
        AesGcmCipher c = new AesGcmCipher( randomKey() );
        String token = c.encrypt( "secret" );
        byte[] raw = Base64.getDecoder().decode( token );
        raw[ raw.length - 1 ] ^= 0x01;                                 // flip a bit in the tag/ciphertext
        String tampered = Base64.getEncoder().encodeToString( raw );
        assertThrows( java.security.GeneralSecurityException.class, () -> c.decrypt( tampered ) );
    }

    @Test void wrongKeyFailsToDecrypt() {
        String token = new AesGcmCipher( randomKey() ).encrypt( "secret" );
        assertThrows( java.security.GeneralSecurityException.class, () -> new AesGcmCipher( randomKey() ).decrypt( token ) );
    }

    @Test void keyFromBase64RejectsWrongLength() {
        assertThrows( IllegalArgumentException.class,
            () -> AesGcmCipher.keyFromBase64( Base64.getEncoder().encodeToString( new byte[16] ) ) );  // 16 ≠ 32
    }
}
