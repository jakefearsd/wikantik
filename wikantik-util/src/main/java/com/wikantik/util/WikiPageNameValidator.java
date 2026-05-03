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

/**
 * Strict pre-check for caller-supplied wiki page names — rejects inputs the
 * existing {@link TextUtil#cleanString} would silently normalise into nonsense
 * (path-traversal sequences, control characters, multi-kilobyte names).
 *
 * <p>Used at MCP boundaries before any further processing. Internal uses of
 * {@code cleanLink}/{@code wikifyLink} that operate on already-stored page
 * names continue to work unchanged.</p>
 */
public final class WikiPageNameValidator {

    /** Hard cap on a wiki page name. Matches typical filesystem and UI limits. */
    public static final int MAX_LENGTH = 128;

    private WikiPageNameValidator() {}

    /**
     * Throws {@link IllegalArgumentException} when {@code name} is unfit to be a
     * caller-supplied page name. The message is safe to surface to the caller.
     */
    public static void requireValid( final String name, final String fieldName ) {
        final String label = fieldName == null ? "page name" : fieldName;
        if ( name == null ) {
            throw new IllegalArgumentException( label + " is required" );
        }
        final String trimmed = name.trim();
        if ( trimmed.isEmpty() ) {
            throw new IllegalArgumentException( label + " must not be blank" );
        }
        if ( trimmed.length() > MAX_LENGTH ) {
            throw new IllegalArgumentException(
                label + " exceeds maximum length (" + MAX_LENGTH + " characters)" );
        }
        for ( int i = 0; i < trimmed.length(); i++ ) {
            final char c = trimmed.charAt( i );
            if ( c == 0 || Character.isISOControl( c ) ) {
                throw new IllegalArgumentException(
                    label + " contains a control character (0x" + Integer.toHexString( c ) + ")" );
            }
            if ( c == '/' || c == '\\' ) {
                throw new IllegalArgumentException(
                    label + " must not contain path separators ('/' or '\\\\')" );
            }
        }
        if ( trimmed.contains( ".." ) ) {
            throw new IllegalArgumentException(
                label + " must not contain path-traversal sequence '..'" );
        }
    }

    /** Convenience: returns true iff {@link #requireValid} would accept {@code name}. */
    public static boolean isValid( final String name ) {
        try { requireValid( name, null ); return true; }
        catch ( final IllegalArgumentException e ) { return false; }
    }
}
