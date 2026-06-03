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
package com.wikantik.audit;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Computes the SHA-256 hash chain over audit entries. */
public final class AuditChainHasher {

    public static final String GENESIS_PREV_HASH = "0".repeat( 64 );

    private AuditChainHasher() {}

    /** row_hash = SHA256( prevHash || entry.canonical() ), lowercase hex. */
    public static String hash( final String prevHash, final AuditEntry entry ) {
        try {
            final MessageDigest md = MessageDigest.getInstance( "SHA-256" );
            md.update( prevHash.getBytes( StandardCharsets.UTF_8 ) );
            md.update( entry.canonical().getBytes( StandardCharsets.UTF_8 ) );
            final byte[] digest = md.digest();
            final StringBuilder sb = new StringBuilder( 64 );
            for ( final byte b : digest ) {
                sb.append( Character.forDigit( ( b >> 4 ) & 0xF, 16 ) );
                sb.append( Character.forDigit( b & 0xF, 16 ) );
            }
            return sb.toString();
        } catch ( final NoSuchAlgorithmException e ) {
            // SHA-256 is guaranteed present on every JRE; this is unreachable.
            throw new IllegalStateException( "SHA-256 unavailable", e );
        }
    }
}
