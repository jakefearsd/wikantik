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

import java.util.regex.Pattern;

/** What a caller may say about a deployment: its version, where it goes, a note, and the evidence it was verified. */
final class DeploymentInputs {

    static final int MAX_EVIDENCE = 20_000;
    static final int MAX_NOTE = 2_000;
    static final String PRODUCTION = "production";
    private static final Pattern VERSION = Pattern.compile( "[A-Za-z0-9._+-]{1,60}" );
    private static final Pattern ENVIRONMENT = Pattern.compile( "[a-z0-9-]{1,40}" );

    private DeploymentInputs() {
    }

    static String version( final String value ) {
        return text( "version", value, VERSION,
            "A version is 1 to 60 letters, digits, dots, plus signs, hyphens or underscores." );
    }

    /** The environment named, or production when none is. */
    static String environment( final String value ) {
        return value == null || value.isBlank() ? PRODUCTION
            : text( "environment", value, ENVIRONMENT, "An environment is 1 to 40 lower-case letters, digits or hyphens." );
    }

    /** A note, or null when none was given. */
    static String note( final String note ) {
        if ( note == null || note.isBlank() ) {
            return null;
        }
        if ( note.length() > MAX_NOTE ) {
            throw new WorkValidationException( "note", "A note may be at most " + MAX_NOTE + " characters." );
        }
        return note;
    }

    /** The health and smoke-test output a verification must carry. */
    static String evidence( final String evidence ) {
        if ( evidence == null || evidence.isBlank() ) {
            throw new WorkValidationException( "evidence",
                "Attach the health and smoke-test output before marking this verified." );
        }
        if ( evidence.length() > MAX_EVIDENCE ) {
            throw new WorkValidationException( "evidence",
                "Evidence may be at most " + MAX_EVIDENCE + " characters." );
        }
        return evidence;
    }

    private static String text( final String field, final String value, final Pattern pattern, final String rule ) {
        final String trimmed = value == null ? "" : value.trim();
        if ( !pattern.matcher( trimmed ).matches() ) {
            throw new WorkValidationException( field, rule );
        }
        return trimmed;
    }
}
