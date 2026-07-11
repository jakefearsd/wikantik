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

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

/**
 * Reversible authenticated encryption for small secrets (connector credentials), using AES-256-GCM
 * from the JDK. Token = base64( IV(12 bytes) ‖ ciphertext ‖ GCM tag(16 bytes) ), a fresh random IV
 * per {@link #encrypt}. GCM's auth tag means {@link #decrypt} fails (throws) on any tamper or wrong key.
 * Sibling to the hash-only {@link CryptoUtil}. Never logs or exposes key/plaintext.
 */
public final class AesGcmCipher {

    private static final String TRANSFORM = "AES/GCM/NoPadding";
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final int AES_256_KEY_BYTES = 32;

    private final SecretKey key;
    private final SecureRandom random = new SecureRandom();

    public AesGcmCipher( final SecretKey key ) {
        this.key = key;
    }

    /** @return base64(IV‖ciphertext‖tag). */
    public String encrypt( final String plaintext ) {
        try {
            final byte[] iv = new byte[ IV_BYTES ];
            random.nextBytes( iv );
            final Cipher cipher = Cipher.getInstance( TRANSFORM );
            cipher.init( Cipher.ENCRYPT_MODE, key, new GCMParameterSpec( TAG_BITS, iv ) );
            final byte[] ct = cipher.doFinal( plaintext.getBytes( StandardCharsets.UTF_8 ) );  // includes tag
            final byte[] out = new byte[ iv.length + ct.length ];
            System.arraycopy( iv, 0, out, 0, iv.length );
            System.arraycopy( ct, 0, out, iv.length, ct.length );
            return Base64.getEncoder().encodeToString( out );
        } catch ( final GeneralSecurityException e ) {
            // an encrypt failure is a configuration/JVM bug, not attacker input — never happens with a valid key
            throw new IllegalStateException( "AES-GCM encrypt failed", e );
        }
    }

    /** @throws GeneralSecurityException on a tampered token or wrong key (GCM auth-tag mismatch). */
    public String decrypt( final String token ) throws GeneralSecurityException {
        final byte[] all = Base64.getDecoder().decode( token );
        if ( all.length <= IV_BYTES ) {
            throw new GeneralSecurityException( "credential token too short" );
        }
        final byte[] iv = Arrays.copyOfRange( all, 0, IV_BYTES );
        final byte[] ct = Arrays.copyOfRange( all, IV_BYTES, all.length );
        final Cipher cipher = Cipher.getInstance( TRANSFORM );
        cipher.init( Cipher.DECRYPT_MODE, key, new GCMParameterSpec( TAG_BITS, iv ) );
        return new String( cipher.doFinal( ct ), StandardCharsets.UTF_8 );   // doFinal throws on tag mismatch
    }

    /** Build an AES-256 key from a base64-encoded 32-byte value. */
    public static SecretKey keyFromBase64( final String base64 ) {
        final byte[] k = Base64.getDecoder().decode( base64 );
        if ( k.length != AES_256_KEY_BYTES ) {
            throw new IllegalArgumentException( "AES-256 key must be 32 bytes; got " + k.length );
        }
        return new SecretKeySpec( k, "AES" );
    }
}
