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
package com.wikantik.api.attachment;

import java.util.regex.Pattern;

/**
 * Validates attachment filenames against strict naming rules:
 * only {@code a-zA-Z0-9._-}, max 40 chars, exactly one period,
 * no leading/trailing period, hyphen, or underscore.
 */
public final class AttachmentNameValidator {

    private static final int MAX_LENGTH = 40;
    private static final Pattern VALID_PATTERN = Pattern.compile(
            "^[a-zA-Z0-9][a-zA-Z0-9_-]*\\.[a-zA-Z0-9]+$"
    );

    private AttachmentNameValidator() { }

    public static boolean isValid( final String name ) {
        if ( name == null || name.isEmpty() || name.length() > MAX_LENGTH ) {
            return false;
        }
        if ( !VALID_PATTERN.matcher( name ).matches() ) {
            return false;
        }
        // Exactly one period
        if ( name.indexOf( '.' ) != name.lastIndexOf( '.' ) ) {
            return false;
        }
        // No trailing hyphen or underscore before the dot
        final int dotIndex = name.indexOf( '.' );
        final char beforeDot = name.charAt( dotIndex - 1 );
        return beforeDot != '-' && beforeDot != '_';
    }

    public static String getExtension( final String name ) {
        if ( name == null ) return "";
        final int dot = name.lastIndexOf( '.' );
        return ( dot >= 0 ) ? name.substring( dot + 1 ).toLowerCase() : "";
    }

    public static boolean extensionsMatch( final String originalName, final String desiredName ) {
        return getExtension( originalName ).equals( getExtension( desiredName ) );
    }
}
