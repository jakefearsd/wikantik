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

import com.wikantik.util.TextUtil;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;

/**
 * NIST 800-63B-aligned password strength validator. Enforces configurable minimum/maximum
 * length and rejects passwords found in a bundled blocklist of common passwords.
 *
 * <p>No arbitrary complexity rules (uppercase, digits, special characters) per NIST guidance —
 * research shows these lead to weaker passwords in practice.</p>
 *
 * <h3>Configuration properties</h3>
 * <ul>
 *   <li>{@code wikantik.password.minLength} — minimum password length (default 8)</li>
 *   <li>{@code wikantik.password.maxLength} — maximum password length (default 128)</li>
 *   <li>{@code wikantik.password.blocklist.enabled} — check against common passwords list (default true)</li>
 * </ul>
 *
 * <h3>Return value</h3>
 * <p>{@link #validate(String, Properties)} returns a list of i18n message keys. An empty list
 * means the password is acceptable. Callers use their own ResourceBundle to resolve the keys
 * to user-facing messages.</p>
 */
public final class PasswordValidator {

    private static final Logger LOG = LogManager.getLogger( PasswordValidator.class );

    public static final String PROP_MIN_LENGTH = "wikantik.password.minLength";
    public static final String PROP_MAX_LENGTH = "wikantik.password.maxLength";
    public static final String PROP_BLOCKLIST_ENABLED = "wikantik.password.blocklist.enabled";

    public static final int DEFAULT_MIN_LENGTH = 8;
    public static final int DEFAULT_MAX_LENGTH = 128;
    public static final boolean DEFAULT_BLOCKLIST_ENABLED = true;

    public static final String KEY_TOO_SHORT = "security.error.password.tooshort";
    public static final String KEY_TOO_LONG = "security.error.password.toolong";
    public static final String KEY_COMMON = "security.error.password.common";

    private static final String BLOCKLIST_RESOURCE = "com/wikantik/auth/validate/common-passwords.txt";

    private static volatile Set<String> blocklist;

    private PasswordValidator() {
        // utility class
    }

    /**
     * Validates a password against the configured policy, reading settings from the provided properties.
     *
     * @param password   the candidate password
     * @param properties wiki configuration properties
     * @return list of i18n message keys for validation failures (empty if valid)
     */
    public static List<String> validate( final String password, final Properties properties ) {
        final int minLength = TextUtil.getIntegerProperty( properties, PROP_MIN_LENGTH, DEFAULT_MIN_LENGTH );
        final int maxLength = TextUtil.getIntegerProperty( properties, PROP_MAX_LENGTH, DEFAULT_MAX_LENGTH );
        final boolean checkBlocklist = TextUtil.getBooleanProperty( properties, PROP_BLOCKLIST_ENABLED, DEFAULT_BLOCKLIST_ENABLED );
        return validate( password, minLength, maxLength, checkBlocklist );
    }

    /**
     * Validates a password against explicit policy parameters.
     *
     * @param password       the candidate password
     * @param minLength      minimum acceptable length
     * @param maxLength      maximum acceptable length
     * @param checkBlocklist whether to check against the common passwords blocklist
     * @return list of i18n message keys for validation failures (empty if valid)
     */
    public static List<String> validate( final String password, final int minLength,
                                         final int maxLength, final boolean checkBlocklist ) {
        final List<String> errors = new ArrayList<>();

        if ( password == null || password.length() < minLength ) {
            errors.add( KEY_TOO_SHORT );
            return errors;
        }

        if ( password.length() > maxLength ) {
            errors.add( KEY_TOO_LONG );
            return errors;
        }

        if ( checkBlocklist && isBlocklisted( password ) ) {
            errors.add( KEY_COMMON );
        }

        return errors;
    }

    /**
     * Checks whether the given password appears in the common passwords blocklist.
     * Case-insensitive comparison.
     */
    static boolean isBlocklisted( final String password ) {
        return getBlocklist().contains( password.toLowerCase() );
    }

    /**
     * Returns the lazily-loaded blocklist. Thread-safe via double-checked locking.
     */
    static Set<String> getBlocklist() {
        if ( blocklist == null ) {
            synchronized ( PasswordValidator.class ) {
                if ( blocklist == null ) {
                    blocklist = loadBlocklist();
                }
            }
        }
        return blocklist;
    }

    private static Set<String> loadBlocklist() {
        final Set<String> set = new HashSet<>();
        try ( final InputStream is = PasswordValidator.class.getClassLoader().getResourceAsStream( BLOCKLIST_RESOURCE ) ) {
            if ( is == null ) {
                LOG.warn( "Common passwords blocklist not found: {}", BLOCKLIST_RESOURCE );
                return Collections.emptySet();
            }
            try ( final BufferedReader reader = new BufferedReader( new InputStreamReader( is, StandardCharsets.UTF_8 ) ) ) {
                String line;
                while ( ( line = reader.readLine() ) != null ) {
                    final String trimmed = line.trim();
                    if ( !trimmed.isEmpty() && !trimmed.startsWith( "#" ) ) {
                        set.add( trimmed.toLowerCase() );
                    }
                }
            }
        } catch ( final IOException e ) {
            LOG.warn( "Failed to load common passwords blocklist — blocklist checking will be disabled", e );
            return Collections.emptySet();
        }
        LOG.info( "Loaded {} entries from common passwords blocklist", set.size() );
        return Collections.unmodifiableSet( set );
    }

    /**
     * Converts an i18n message key to a plain-English description suitable for REST API
     * error responses (where no ResourceBundle is available from a WikiContext).
     *
     * @param key the i18n message key
     * @return a human-readable description
     */
    public static String describeError( final String key ) {
        return switch ( key ) {
            case KEY_TOO_SHORT -> "Password is too short (minimum " + DEFAULT_MIN_LENGTH + " characters)";
            case KEY_TOO_LONG -> "Password is too long (maximum " + DEFAULT_MAX_LENGTH + " characters)";
            case KEY_COMMON -> "Password is too common and easily guessed";
            default -> key;
        };
    }

    /**
     * Clears the cached blocklist. Used by tests to reset state.
     */
    static void resetBlocklist() {
        synchronized ( PasswordValidator.class ) {
            blocklist = null;
        }
    }

}
