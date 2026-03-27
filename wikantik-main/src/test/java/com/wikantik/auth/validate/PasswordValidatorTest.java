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
package com.wikantik.auth.validate;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

class PasswordValidatorTest {

    private Properties props;

    @BeforeEach
    void setUp() {
        PasswordValidator.resetBlocklist();
        props = new Properties();
    }

    // --- Length validation ---

    @Test
    void rejectsNullPassword() {
        final List<String> errors = PasswordValidator.validate( null, props );
        assertEquals( 1, errors.size() );
        assertEquals( PasswordValidator.KEY_TOO_SHORT, errors.get( 0 ) );
    }

    @Test
    void rejectsEmptyPassword() {
        final List<String> errors = PasswordValidator.validate( "", props );
        assertEquals( 1, errors.size() );
        assertEquals( PasswordValidator.KEY_TOO_SHORT, errors.get( 0 ) );
    }

    @Test
    void rejectsPasswordShorterThanDefault() {
        final List<String> errors = PasswordValidator.validate( "abc", props );
        assertEquals( 1, errors.size() );
        assertEquals( PasswordValidator.KEY_TOO_SHORT, errors.get( 0 ) );
    }

    @Test
    void rejectsPasswordAtDefaultMinLengthMinusOne() {
        final List<String> errors = PasswordValidator.validate( "1234567", props );
        assertEquals( 1, errors.size() );
        assertEquals( PasswordValidator.KEY_TOO_SHORT, errors.get( 0 ) );
    }

    @Test
    void acceptsPasswordAtExactMinLength() {
        final List<String> errors = PasswordValidator.validate( "abcd1234", props );
        // "abcd1234" is 8 chars (default min) but is in the blocklist
        // Use a non-blocklisted 8-char password
        final List<String> errors2 = PasswordValidator.validate( "xkqp9m2z", props );
        assertTrue( errors2.isEmpty() );
    }

    @Test
    void rejectsPasswordExceedingMaxLength() {
        final String longPassword = "a".repeat( 129 );
        final List<String> errors = PasswordValidator.validate( longPassword, props );
        assertEquals( 1, errors.size() );
        assertEquals( PasswordValidator.KEY_TOO_LONG, errors.get( 0 ) );
    }

    @Test
    void acceptsPasswordAtExactMaxLength() {
        final String maxPassword = "x".repeat( 128 );
        final List<String> errors = PasswordValidator.validate( maxPassword, props );
        assertTrue( errors.isEmpty() );
    }

    // --- Configurable min/max length ---

    @Test
    void respectsCustomMinLength() {
        props.setProperty( PasswordValidator.PROP_MIN_LENGTH, "12" );
        final List<String> errors = PasswordValidator.validate( "12345678", props );
        assertEquals( 1, errors.size() );
        assertEquals( PasswordValidator.KEY_TOO_SHORT, errors.get( 0 ) );

        final List<String> errors2 = PasswordValidator.validate( "123456789012", props );
        assertTrue( errors2.isEmpty() );
    }

    @Test
    void respectsCustomMaxLength() {
        props.setProperty( PasswordValidator.PROP_MAX_LENGTH, "20" );
        final String password = "a".repeat( 21 );
        final List<String> errors = PasswordValidator.validate( password, props );
        assertEquals( 1, errors.size() );
        assertEquals( PasswordValidator.KEY_TOO_LONG, errors.get( 0 ) );
    }

    // --- Blocklist validation ---

    @Test
    void rejectsCommonPassword() {
        final List<String> errors = PasswordValidator.validate( "password", props );
        assertEquals( 1, errors.size() );
        assertEquals( PasswordValidator.KEY_COMMON, errors.get( 0 ) );
    }

    @Test
    void rejectsCommonPasswordCaseInsensitive() {
        final List<String> errors = PasswordValidator.validate( "PASSWORD", props );
        assertEquals( 1, errors.size() );
        assertEquals( PasswordValidator.KEY_COMMON, errors.get( 0 ) );

        final List<String> errors2 = PasswordValidator.validate( "PaSsWoRd", props );
        assertEquals( 1, errors2.size() );
        assertEquals( PasswordValidator.KEY_COMMON, errors2.get( 0 ) );
    }

    @Test
    void rejectsNumericCommonPasswords() {
        final List<String> errors = PasswordValidator.validate( "12345678", props );
        assertEquals( 1, errors.size() );
        assertEquals( PasswordValidator.KEY_COMMON, errors.get( 0 ) );
    }

    @Test
    void rejectsPassword123() {
        final List<String> errors = PasswordValidator.validate( "password123", props );
        assertEquals( 1, errors.size() );
        assertEquals( PasswordValidator.KEY_COMMON, errors.get( 0 ) );
    }

    @Test
    void rejectsQwerty() {
        final List<String> errors = PasswordValidator.validate( "qwerty123", props );
        // "qwerty123" is 9 chars but may not be in blocklist. Check "qwertyuiop" which is
        final List<String> errors2 = PasswordValidator.validate( "qwertyuiop", props );
        assertEquals( 1, errors2.size() );
        assertEquals( PasswordValidator.KEY_COMMON, errors2.get( 0 ) );
    }

    @Test
    void blocklistCanBeDisabled() {
        props.setProperty( PasswordValidator.PROP_BLOCKLIST_ENABLED, "false" );
        // "password" is common but blocklist is disabled, and it's 8 chars (meets default min)
        final List<String> errors = PasswordValidator.validate( "password", props );
        assertTrue( errors.isEmpty(), "With blocklist disabled, 'password' should be accepted" );
    }

    // --- Acceptance cases ---

    @Test
    void acceptsStrongPassphrase() {
        final List<String> errors = PasswordValidator.validate( "correcthorsebatterystaple", props );
        assertTrue( errors.isEmpty() );
    }

    @Test
    void acceptsRandomPassword() {
        final List<String> errors = PasswordValidator.validate( "j8kLm3nP9qRs", props );
        assertTrue( errors.isEmpty() );
    }

    @Test
    void acceptsPasswordWithOnlyLowercase() {
        // NIST: no complexity rules
        final List<String> errors = PasswordValidator.validate( "alllowercasepassword", props );
        assertTrue( errors.isEmpty() );
    }

    @Test
    void acceptsPasswordWithOnlyDigits() {
        // NIST: no complexity rules — digits-only is fine if not in blocklist and meets length
        final List<String> errors = PasswordValidator.validate( "98765432109876", props );
        assertTrue( errors.isEmpty() );
    }

    // --- Direct parameter API ---

    @Test
    void directApiRespectsParameters() {
        final List<String> errors = PasswordValidator.validate( "short", 10, 50, false );
        assertEquals( 1, errors.size() );
        assertEquals( PasswordValidator.KEY_TOO_SHORT, errors.get( 0 ) );

        final List<String> errors2 = PasswordValidator.validate( "a".repeat( 51 ), 10, 50, false );
        assertEquals( 1, errors2.size() );
        assertEquals( PasswordValidator.KEY_TOO_LONG, errors2.get( 0 ) );

        final List<String> errors3 = PasswordValidator.validate( "validpassword", 10, 50, false );
        assertTrue( errors3.isEmpty() );
    }

    // --- Blocklist loading ---

    @Test
    void blocklistIsLoadedAndNonEmpty() {
        assertTrue( PasswordValidator.getBlocklist().size() > 100,
                "Blocklist should contain at least 100 entries" );
    }

    @Test
    void blocklistEntriesAreLowercase() {
        for ( final String entry : PasswordValidator.getBlocklist() ) {
            assertEquals( entry, entry.toLowerCase(),
                    "All blocklist entries should be stored lowercase: " + entry );
        }
    }

}
