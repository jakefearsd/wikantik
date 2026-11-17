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

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.regex.Pattern;

/** Validation of what a caller says about a change. Every failure names its field. */
final class ChangeInputs {

    static final int MAX_BRANCH = 200;
    static final int MAX_URL = 500;
    static final int MAX_NOTE = 2000;

    private static final Pattern BRANCH = Pattern.compile( "[^\\s\\p{C}]{1," + MAX_BRANCH + "}" );
    private static final Pattern SHA = Pattern.compile( "[0-9a-f]{7,64}" );

    private ChangeInputs() {
    }

    static String branch( final String field, final String value ) {
        if ( value == null || !BRANCH.matcher( value ).matches() ) {
            throw new WorkValidationException( field,
                "A branch name is 1 to " + MAX_BRANCH + " visible characters with no spaces." );
        }
        return value;
    }

    /** A commit id, lower-cased; null when none was given. */
    static String sha( final String field, final String value ) {
        if ( value == null || value.isBlank() ) {
            return null;
        }
        final String lower = value.trim().toLowerCase( Locale.ROOT );
        if ( !SHA.matcher( lower ).matches() ) {
            throw new WorkValidationException( field, "A commit id is 7 to 64 hexadecimal characters." );
        }
        return lower;
    }

    static String requiredSha( final String field, final String value ) {
        final String sha = sha( field, value );
        if ( sha == null ) {
            throw new WorkValidationException( field, "Name the commit." );
        }
        return sha;
    }

    /** An http or https link, or null when none was given. */
    static String url( final String value ) {
        if ( value == null || value.isBlank() ) {
            return null;
        }
        final String trimmed = value.trim();
        try {
            final URI uri = new URI( trimmed );
            final String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase( Locale.ROOT );
            if ( trimmed.length() <= MAX_URL && uri.getHost() != null
                && ( "http".equals( scheme ) || "https".equals( scheme ) ) ) {
                return trimmed;
            }
        } catch ( final URISyntaxException e ) {
            throw new WorkValidationException( "reviewUrl", "A review link is an http or https address." );
        }
        throw new WorkValidationException( "reviewUrl", "A review link is an http or https address." );
    }

    static String note( final String value ) {
        if ( value == null || value.isBlank() ) {
            return null;
        }
        if ( value.length() > MAX_NOTE ) {
            throw new WorkValidationException( "note", "A note may be at most " + MAX_NOTE + " characters." );
        }
        return value;
    }
}
