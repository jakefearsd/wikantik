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

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Locale;
import java.util.regex.Pattern;

/** Webhook secrets and the {@code sha256=} signature check (GitHub's scheme; the generic sender uses it too). */
final class CodeHostSecrets {

    /** The key a check uses when there is no live hook, so every check costs one HMAC. */
    static final String DUMMY_KEY = "wikantik-no-live-hook-dummy-key-00000000000000000000";

    private static final Pattern HEADER = Pattern.compile( "sha256=[0-9a-fA-F]{64}" );
    private static final SecureRandom RANDOM = new SecureRandom();

    private CodeHostSecrets() {
    }

    /** A new secret: 32 random bytes as 64 lower-case hex characters. */
    static String newSecret() {
        final byte[] bytes = new byte[ 32 ];
        RANDOM.nextBytes( bytes );
        return HexFormat.of().formatHex( bytes );
    }

    /**
     * Whether {@code header} is {@code sha256=} followed by the HMAC-SHA256 of {@code body} under {@code secret}.
     * The HMAC is always computed, whatever the header, and compared in constant time.
     */
    static boolean matches( final String secret, final byte[] body, final String header ) {
        final byte[] expected = hmac( secret, body == null ? new byte[ 0 ] : body );
        final boolean shaped = shaped( header );
        final byte[] given = shaped ? HexFormat.of().parseHex( header.substring( "sha256=".length() )
            .toLowerCase( Locale.ROOT ) ) : new byte[ expected.length ];
        return MessageDigest.isEqual( expected, given ) && shaped;
    }

    /** Whether {@code header} has the one shape a signature can have: {@code sha256=} and 64 hex digits. */
    static boolean shaped( final String header ) {
        return header != null && HEADER.matcher( header ).matches();
    }

    private static byte[] hmac( final String secret, final byte[] body ) {
        try {
            final Mac mac = Mac.getInstance( "HmacSHA256" );
            mac.init( new SecretKeySpec( secret.getBytes( StandardCharsets.UTF_8 ), "HmacSHA256" ) );
            return mac.doFinal( body );
        } catch ( final NoSuchAlgorithmException | InvalidKeyException e ) {
            throw new IllegalStateException( "HmacSHA256 is unavailable", e );
        }
    }
}
