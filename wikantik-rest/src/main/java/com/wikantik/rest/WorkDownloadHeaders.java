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

import java.nio.charset.StandardCharsets;

/** The {@code Content-Disposition} of an attachment download; a file name can never break out of the header. */
final class WorkDownloadHeaders {

    private WorkDownloadHeaders() {
    }

    /** {@code inline|attachment; filename="<ascii>"; filename*=UTF-8''<percent-encoded>}. */
    static String disposition( final boolean inline, final String name ) {
        return ( inline ? "inline" : "attachment" ) + "; filename=\"" + asciiFallback( name ) + "\"; filename*=UTF-8''"
            + percentEncode( name );
    }

    /** Every character outside printable ASCII, and the quote and backslash, becomes an underscore. */
    static String asciiFallback( final String name ) {
        final StringBuilder sb = new StringBuilder();
        for ( int i = 0; i < name.length(); i++ ) {
            final char ch = name.charAt( i );
            sb.append( ch < 0x20 || ch > 0x7e || ch == '"' || ch == '\\' ? '_' : ch );
        }
        return sb.isEmpty() ? "download" : sb.toString();
    }

    /** RFC 5987 {@code attr-char}: ALPHA, DIGIT and {@code !#$&+-.^_`|~} stay; every other byte is {@code %XX}. */
    static String percentEncode( final String name ) {
        final StringBuilder sb = new StringBuilder();
        for ( final byte b : name.getBytes( StandardCharsets.UTF_8 ) ) {
            final int ch = b & 0xff;
            if ( ch >= 'a' && ch <= 'z' || ch >= 'A' && ch <= 'Z' || ch >= '0' && ch <= '9'
                    || "!#$&+-.^_`|~".indexOf( ch ) >= 0 ) {
                sb.append( (char) ch );
            } else {
                sb.append( '%' ).append( Character.toUpperCase( Character.forDigit( ch >> 4, 16 ) ) )
                    .append( Character.toUpperCase( Character.forDigit( ch & 0xf, 16 ) ) );
            }
        }
        return sb.toString();
    }
}
