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
package com.wikantik.markdown.extensions.fences;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.crypto.KeyGenerator;
import javax.crypto.Mac;
import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * Signed placeholder tokens for server-kind fences: {@code sha256hex(source) + "." + hmacSha256hex(secret, sha256hex)}.
 * The token is stable for a given source within a JVM, so the HTML caches stay deterministic, and unforgeable
 * without the secret, so a hand-written {@code data-fence-drawing} cannot make the resolver draw arbitrary
 * source. The secret is 32 random bytes generated per JVM: tokens cached by another node (the opt-in memcached
 * adapter) fail {@link #verify} here and show their source instead.
 */
public final class FencePlaceholders {

    private static final Logger LOG = LogManager.getLogger( FencePlaceholders.class );
    private static final String HMAC = "HmacSHA256";
    /** Per-JVM random HMAC key: placeholders signed by one process are never trusted by another. */
    private static final SecretKey KEY = newKey();

    private static SecretKey newKey() {
        try {
            final KeyGenerator generator = KeyGenerator.getInstance( HMAC );
            generator.init( 256 );
            return generator.generateKey();
        } catch ( final GeneralSecurityException e ) {
            throw new IllegalStateException( "HmacSHA256 unavailable", e );
        }
    }

    private FencePlaceholders() {
    }

    /**
     * @param source the exact fence source (LF-normalised, as placed in the {@code <code>} element)
     * @return the signed token
     * @throws IllegalStateException if the JVM lacks SHA-256 or HmacSHA256 (cannot happen on a conforming JVM)
     */
    public static String sign( final String source ) {
        final String hash = sha256Hex( source );
        return hash + "." + mac( hash );
    }

    /** @return true when {@code token} is the valid token for {@code source}; never throws */
    public static boolean verify( final String token, final String source ) {
        if ( token == null || source == null ) {
            return false;
        }
        try {
            final String expected = sign( source );
            return MessageDigest.isEqual( expected.getBytes( StandardCharsets.US_ASCII ),
                    token.getBytes( StandardCharsets.US_ASCII ) );
        } catch ( final IllegalStateException e ) {
            LOG.warn( "Fence placeholder verification failed unexpectedly; treating as invalid: {}", e.getMessage(), e );
            return false;
        }
    }

    private static String sha256Hex( final String source ) {
        try {
            return HexFormat.of().formatHex( MessageDigest.getInstance( "SHA-256" ).digest( source.getBytes( StandardCharsets.UTF_8 ) ) );
        } catch ( final GeneralSecurityException e ) {
            throw new IllegalStateException( "SHA-256 unavailable", e );
        }
    }

    private static String mac( final String hash ) {
        try {
            final Mac mac = Mac.getInstance( HMAC );
            mac.init( KEY );
            return HexFormat.of().formatHex( mac.doFinal( hash.getBytes( StandardCharsets.US_ASCII ) ) );
        } catch ( final GeneralSecurityException e ) {
            throw new IllegalStateException( "HmacSHA256 unavailable", e );
        }
    }
}
