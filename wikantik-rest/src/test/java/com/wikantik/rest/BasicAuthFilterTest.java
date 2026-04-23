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
package com.wikantik.rest;

import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Unit tests for the header-decoding helper. The full servlet contract is
 * harder to exercise without engine wiring — the production path is covered
 * by the manual-test A/B against the live admin endpoints and by the explicit
 * {@link AdminAuthFilterTest} suite that already covers what happens once a
 * principal is on the session.
 */
class BasicAuthFilterTest {

    @Test
    void decodesWellFormedBasicCredentials() {
        final String header = "Basic " + Base64.getEncoder()
                .encodeToString( "alice:s3cret".getBytes() );
        final String[] decoded = BasicAuthFilter.decodeCredentials( header );
        assertArrayEquals( new String[]{ "alice", "s3cret" }, decoded );
    }

    @Test
    void handlesColonInPasswordByTakingOnlyFirstSplit() {
        final String header = "Basic " + Base64.getEncoder()
                .encodeToString( "alice:secret:with:colons".getBytes() );
        final String[] decoded = BasicAuthFilter.decodeCredentials( header );
        assertEquals( "alice", decoded[ 0 ] );
        assertEquals( "secret:with:colons", decoded[ 1 ] );
    }

    @Test
    void returnsNullOnMalformedBase64() {
        assertNull( BasicAuthFilter.decodeCredentials( "Basic !!not base64!!" ) );
    }

    @Test
    void missingColonYieldsEmptyUsernameAndPassword() {
        final String header = "Basic " + Base64.getEncoder()
                .encodeToString( "no-colon-here".getBytes() );
        final String[] decoded = BasicAuthFilter.decodeCredentials( header );
        assertArrayEquals( new String[]{ "", "" }, decoded );
    }

    @Test
    void emptyCredentialsDecodeToEmpty() {
        final String header = "Basic " + Base64.getEncoder()
                .encodeToString( ":".getBytes() );
        final String[] decoded = BasicAuthFilter.decodeCredentials( header );
        assertArrayEquals( new String[]{ "", "" }, decoded );
    }
}
