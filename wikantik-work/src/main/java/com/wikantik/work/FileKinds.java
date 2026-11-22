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

import java.util.Optional;

/**
 * What an attachment is, decided from its bytes and never from the client's claim, and a file name made safe to
 * store. Only four raster image types are ever shown inline; SVG and HTML are never among them.
 */
public final class FileKinds {

    /** The stored type of anything that is not one of the four inline images. */
    public static final String OCTET_STREAM = "application/octet-stream";

    private static final int MAX_NAME = 200;
    private static final int MAX_EXTENSION = 16;
    private static final byte[] PNG = { (byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A };
    private static final byte[] JPEG = { (byte) 0xFF, (byte) 0xD8, (byte) 0xFF };
    private static final byte[] GIF87 = { 'G', 'I', 'F', '8', '7', 'a' };
    private static final byte[] GIF89 = { 'G', 'I', 'F', '8', '9', 'a' };
    private static final byte[] RIFF = { 'R', 'I', 'F', 'F' };
    private static final byte[] WEBP = { 'W', 'E', 'B', 'P' };

    private FileKinds() {
    }

    /** The image type the leading bytes show, or empty (null, short, or anything that is not one of the four). */
    public static Optional< String > detect( final byte[] head ) {
        if ( head == null ) {
            return Optional.empty();
        }
        if ( startsWith( head, 0, PNG ) ) {
            return Optional.of( "image/png" );
        }
        if ( startsWith( head, 0, JPEG ) ) {
            return Optional.of( "image/jpeg" );
        }
        if ( startsWith( head, 0, GIF87 ) || startsWith( head, 0, GIF89 ) ) {
            return Optional.of( "image/gif" );
        }
        if ( startsWith( head, 0, RIFF ) && startsWith( head, 8, WEBP ) ) {
            return Optional.of( "image/webp" );
        }
        return Optional.empty();
    }

    /** True for exactly the four stored image types (case-sensitive). */
    public static boolean inline( final String contentType ) {
        return "image/png".equals( contentType ) || "image/jpeg".equals( contentType )
            || "image/gif".equals( contentType ) || "image/webp".equals( contentType );
    }

    /**
     * A file name that is safe to store and show: the text after the last slash or backslash, without control or
     * bidi-control characters, trimmed; "" when nothing usable is left; at most 200 characters, keeping a short
     * extension. The name is data: it is never used as a path.
     */
    public static String safeName( final String raw ) {
        if ( raw == null ) {
            return "";
        }
        final int cut = Math.max( raw.lastIndexOf( '/' ), raw.lastIndexOf( '\\' ) );
        final StringBuilder sb = new StringBuilder();
        raw.substring( cut + 1 ).codePoints().filter( c -> !unwanted( c ) ).forEach( sb::appendCodePoint );
        final String name = sb.toString().trim();
        if ( name.isEmpty() || name.chars().allMatch( c -> c == '.' ) ) {
            return "";
        }
        return name.length() > MAX_NAME ? shorten( name ) : name;
    }

    private static boolean unwanted( final int c ) {
        return c < 0x20 || c == 0x7F || ( c >= 0x202A && c <= 0x202E ) || ( c >= 0x2066 && c <= 0x2069 );
    }

    private static String shorten( final String name ) {
        final int dot = name.lastIndexOf( '.' );
        final String ext = dot > 0 && name.length() - dot <= MAX_EXTENSION ? name.substring( dot ) : "";
        return name.substring( 0, MAX_NAME - ext.length() ) + ext;
    }

    private static boolean startsWith( final byte[] data, final int at, final byte[] prefix ) {
        if ( data.length < at + prefix.length ) {
            return false;
        }
        for ( int i = 0; i < prefix.length; i++ ) {
            if ( data[ at + i ] != prefix[ i ] ) {
                return false;
            }
        }
        return true;
    }
}
