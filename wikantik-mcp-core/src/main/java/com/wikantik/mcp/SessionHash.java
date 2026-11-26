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
package com.wikantik.mcp;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HexFormat;

/**
 * A short, stable, non-reversible stand-in for an MCP session id: the first 12 hex characters of
 * SHA-256 over a per-JVM random salt and the id. The raw id never reaches a log; the salt means the hash
 * cannot be matched across restarts or against a guessed id.
 */
public final class SessionHash {

    /** The value for an absent or blank session id. */
    public static final String NONE = "none";

    private static final byte[] SALT = new byte[ 16 ];

    static {
        new SecureRandom().nextBytes( SALT );
    }

    private SessionHash() {
    }

    /** @return 12 lowercase hex characters, or {@code "none"} when the id is null or blank */
    public static String of( final String sessionIdOrNull ) {
        if ( sessionIdOrNull == null || sessionIdOrNull.isBlank() ) {
            return NONE;
        }
        try {
            final MessageDigest sha = MessageDigest.getInstance( "SHA-256" );
            sha.update( SALT );
            sha.update( sessionIdOrNull.getBytes( StandardCharsets.UTF_8 ) );
            return HexFormat.of().formatHex( sha.digest() ).substring( 0, 12 );
        } catch ( final NoSuchAlgorithmException e ) {
            // SHA-256 is mandatory on every JVM; unreachable in practice.
            throw new IllegalStateException( "SHA-256 unavailable", e );
        }
    }
}
