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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * The shape of an agent's own token. It carries a prefix no personal API key has, so a filter can tell the two
 * apart without a database read and a token is recognisable in a log or a secret scanner. Only its SHA-256 is
 * stored: the token is 32 random bytes, so a fast hash is enough.
 */
public final class AgentTokens {

    /** What every agent token starts with; a personal API key starts with {@code wkk_}. */
    public static final String PREFIX = "wka_";

    private static final int RANDOM_BYTES = 32;

    private AgentTokens() {
    }

    /** A new token: the prefix and 32 random bytes as unpadded base64url. */
    public static String generate( final SecureRandom random ) {
        final byte[] bytes = new byte[ RANDOM_BYTES ];
        random.nextBytes( bytes );
        return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString( bytes );
    }

    /** Whether a bearer value is shaped like an agent token (case-sensitive prefix); says nothing about validity. */
    public static boolean looksLikeAgentToken( final String bearer ) {
        return bearer != null && bearer.startsWith( PREFIX );
    }

    /** Lowercase hex SHA-256 of the token: what the credential table stores. */
    public static String hash( final String token ) {
        try {
            return HexFormat.of().formatHex(
                MessageDigest.getInstance( "SHA-256" ).digest( token.getBytes( StandardCharsets.UTF_8 ) ) );
        } catch ( final NoSuchAlgorithmException e ) {
            throw new IllegalStateException( "SHA-256 is required by every Java runtime", e );
        }
    }
}
