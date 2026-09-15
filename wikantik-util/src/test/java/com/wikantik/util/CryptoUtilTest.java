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

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

public class CryptoUtilTest {

    @Test
    public void testCommandLineHash() throws Exception {
        // Save old printstream
        final PrintStream oldOut = System.out;

        // Swallow System out and get command output
        final OutputStream out = new ByteArrayOutputStream();
        System.setOut( new PrintStream( out ) );
        CryptoUtil.main( new String[]{ "--hash", "password" } );
        final String output = out.toString();

        // Restore old printstream
        System.setOut( oldOut );

        // Run our tests
        Assertions.assertTrue( output.startsWith( "{SHA-256}" ) );
    }

    @Test
    public void bcryptHashRoundTrips() throws Exception {
        final byte[] pw = "s3cret-π".getBytes( StandardCharsets.UTF_8 );   // includes a multi-byte UTF-8 char
        final String hash = CryptoUtil.getBcryptHash( pw );

        Assertions.assertTrue( hash.startsWith( "{bcrypt}$2a$" ), "bcrypt hash should be prefixed: " + hash );
        Assertions.assertTrue( CryptoUtil.verifySaltedPassword( pw, hash ),
                "the correct password must verify against its bcrypt hash" );
        Assertions.assertFalse( CryptoUtil.verifySaltedPassword( "wrong".getBytes( StandardCharsets.UTF_8 ), hash ),
                "a wrong password must not verify" );
    }

    @Test
    public void bcryptUsesARandomSaltPerCall() throws Exception {
        final byte[] pw = "samePassword".getBytes( StandardCharsets.UTF_8 );
        Assertions.assertNotEquals( CryptoUtil.getBcryptHash( pw ), CryptoUtil.getBcryptHash( pw ),
                "two bcrypt hashes of the same password must differ (random salt)" );
    }

    @Test
    public void testCommandLineNoVerify() throws Exception {
        final String digest = CryptoUtil.getSaltedPassword( "testing123".getBytes( StandardCharsets.UTF_8 ), "{SHA-256}" );

        // Save old printstream
        final PrintStream oldOut = System.out;

        // Swallow System out and get command output
        final OutputStream out = new ByteArrayOutputStream();
        System.setOut( new PrintStream( out ) );
        // Supply a bogus password
        CryptoUtil.main( new String[]{ "--verify", "wrongpassword", digest } );
        final String output = out.toString();

        // Restore old printstream
        System.setOut( oldOut );

        // Run our tests
        Assertions.assertTrue( output.startsWith( "false" ) );
    }

    @Test
    public void testCommandLineSyntaxError1() {
        // Try verifying password without the {SSHA} prefix
        Assertions.assertThrows( IllegalArgumentException.class, () -> CryptoUtil.main( new String[]{ "--verify", "password", "yfT8SRT/WoOuNuA6KbJeF10OznZmb28=", "{SSHA}" } ) );
    }

    @Test
    public void testCommandLineVerify() throws Exception {
        final String digest = CryptoUtil.getSaltedPassword( "testing123".getBytes( StandardCharsets.UTF_8 ), "{SHA-256}" );

        // Save old printstream
        final PrintStream oldOut = System.out;

        // Swallow System out and get command output
        final OutputStream out = new ByteArrayOutputStream();
        System.setOut( new PrintStream( out ) );
        CryptoUtil.main( new String[]{ "--verify", "testing123", digest } );
        final String output = out.toString();

        // Restore old printstream
        System.setOut( oldOut );

        // Run our tests
        Assertions.assertTrue( output.startsWith( "true" ) );
    }

    @Test
    public void testExtractHash() {
        byte[] digest;

        digest = Base64.getDecoder().decode( "yfT8SRT/WoOuNuA6KbJeF10OznZmb28=".getBytes() );
        Assertions.assertEquals( "foo", new String( CryptoUtil.extractSalt( digest, 20 ) ) );

        digest = Base64.getDecoder().decode( "tAVisOOQGAeVyP8UMFQY9qi83lxsb09e".getBytes() );
        Assertions.assertEquals( "loO^", new String( CryptoUtil.extractSalt( digest, 20 ) ) );

        digest = Base64.getDecoder().decode( "BZaDYvB8czmNW3MjR2j7/mklODV0ZXN0eQ==".getBytes() );
        Assertions.assertEquals( "testy", new String( CryptoUtil.extractSalt( digest, 20 ) ) );
    }

    @Test
    public void testGetSaltedPasswordSha256RoundTrips() throws Exception {
        final byte[] password = "testing123".getBytes( StandardCharsets.UTF_8 );
        final String hash = CryptoUtil.getSaltedPassword( password, "foo".getBytes( StandardCharsets.UTF_8 ), "{SHA-256}" );

        Assertions.assertTrue( hash.startsWith( "{SHA-256}" ) );
        Assertions.assertTrue( CryptoUtil.verifySaltedPassword( password, hash ) );
        Assertions.assertFalse( CryptoUtil.verifySaltedPassword( "wrongpassword".getBytes( StandardCharsets.UTF_8 ), hash ) );
    }

    @Test
    public void testMultipleHashes() throws Exception {
        final String p1 = CryptoUtil.getSaltedPassword( "password".getBytes(), "{SHA-256}" );
        final String p2 = CryptoUtil.getSaltedPassword( "password".getBytes(), "{SHA-256}" );
        final String p3 = CryptoUtil.getSaltedPassword( "password".getBytes(), "{SHA-256}" );
        Assertions.assertNotSame( p1, p2 );
        Assertions.assertNotSame( p2, p3 );
        Assertions.assertNotSame( p1, p3 );
    }

    // --- {SSHA} (salted SHA-1) is no longer supported — both entry points reject it ---

    @Test
    public void verifySaltedPasswordRejectsSSHA() {
        final byte[] password = "testing123".getBytes( StandardCharsets.UTF_8 );
        // A real-looking legacy {SSHA} entry must not verify — it is rejected the same way any
        // entry lacking a recognized algorithm marker is: IllegalArgumentException, never a
        // silent false and never a crash.
        final IllegalArgumentException ex = Assertions.assertThrows( IllegalArgumentException.class,
                () -> CryptoUtil.verifySaltedPassword( password, "{SSHA}yfT8SRT/WoOuNuA6KbJeF10OznZmb28=" ) );
        Assertions.assertTrue( ex.getMessage().contains( "algorithm" ), "unexpected message: " + ex.getMessage() );
    }

    @Test
    public void getSaltedPasswordRejectsSSHA() {
        Assertions.assertThrows( IllegalArgumentException.class,
                () -> CryptoUtil.getSaltedPassword( "testing123".getBytes( StandardCharsets.UTF_8 ), "{SSHA}" ) );
    }

}
