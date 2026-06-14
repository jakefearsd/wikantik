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
package com.wikantik.derived;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.Optional;

/** The derived-page frontmatter contract (provenance keys) + small shared helpers. */
public final class DerivedPage {
    private DerivedPage() {}

    /** Presence of this key marks a page as derived; value is the source attachment filename. */
    public static final String DERIVED_FROM            = "derived_from";
    public static final String DERIVED_EXTRACTOR       = "derived_extractor";
    public static final String DERIVED_EXTRACTOR_VERSION = "derived_extractor_version";
    public static final String DERIVED_SOURCE_SHA      = "derived_source_sha";

    public static boolean isDerived( final Map< String, Object > metadata ) {
        return metadata != null && metadata.get( DERIVED_FROM ) != null
            && !metadata.get( DERIVED_FROM ).toString().isBlank();
    }

    public static Optional< String > derivedFrom( final Map< String, Object > metadata ) {
        if ( metadata == null ) { return Optional.empty(); }
        final Object v = metadata.get( DERIVED_FROM );
        return v == null || v.toString().isBlank() ? Optional.empty() : Optional.of( v.toString() );
    }

    /** Lowercase hex SHA-256 of the source bytes — idempotency / dedup key. */
    public static String sha256( final byte[] bytes ) {
        try {
            final byte[] d = MessageDigest.getInstance( "SHA-256" ).digest( bytes );
            final StringBuilder sb = new StringBuilder( d.length * 2 );
            for ( final byte b : d ) {
                sb.append( Character.forDigit( ( b >> 4 ) & 0xF, 16 ) ).append( Character.forDigit( b & 0xF, 16 ) );
            }
            return sb.toString();
        } catch ( final NoSuchAlgorithmException e ) {
            throw new IllegalStateException( "SHA-256 unavailable", e );
        }
    }

    /** Maximum length of the derived page name produced by {@link #pageNameFor}. */
    private static final int MAX_PAGE_NAME_LENGTH = 200;

    /**
     * Stable, <strong>path-safe</strong> page name derived from the source filename.
     *
     * <p><b>Security boundary.</b> This method is called with the raw upload filename, which
     * may contain path-traversal sequences ({@code /}, {@code \}, {@code ..}) designed to
     * overwrite arbitrary pages or escape the attachment storage directory. The sanitization
     * below is the primary defence against both attack vectors:
     * <ol>
     *   <li><b>Basename extraction</b> — strip everything up to and including the last
     *       {@code /} or {@code \} (both separators, not relying on {@code Paths.get}
     *       which does not treat {@code \} as a separator on Linux).</li>
     *   <li><b>Extension stripping</b> — remove the last {@code .} and everything after it.</li>
     *   <li><b>Character allow-list</b> — keep only letters, digits, space, hyphen, and
     *       underscore ({@code [^A-Za-z0-9 _-]}); everything else is removed.</li>
     *   <li><b>Collapse whitespace</b> — any run of spaces is collapsed to a single space
     *       and the result is trimmed.</li>
     *   <li><b>Length cap</b> — truncated to {@value #MAX_PAGE_NAME_LENGTH} characters.</li>
     *   <li><b>Empty fallback</b> — if the result is empty after all steps, return
     *       {@code "Document"}.</li>
     *   <li><b>First-char uppercase</b> — consistent with {@code MarkupParser.cleanLink()}
     *       used by {@code DefaultAttachmentManager.getAttachmentInfo} (attach path lookup
     *       capitalises the parent page name).</li>
     * </ol>
     */
    public static String pageNameFor( final String filename ) {
        // Step 1: extract basename (handle both / and \)
        String base = filename;
        final int lastSlash = Math.max( base.lastIndexOf( '/' ), base.lastIndexOf( '\\' ) );
        if ( lastSlash >= 0 ) {
            base = base.substring( lastSlash + 1 );
        }

        // Step 2: strip extension (last dot and everything after)
        final int dot = base.lastIndexOf( '.' );
        final String stem = dot > 0 ? base.substring( 0, dot ) : base;

        // Step 3: apply character allow-list — keep letters, digits, space, hyphen, underscore
        final String safe = stem.replaceAll( "[^A-Za-z0-9 _-]", "" );

        // Step 4: collapse whitespace, trim
        final String collapsed = safe.replaceAll( "\\s+", " " ).trim();

        // Step 5: cap length
        final String capped = collapsed.length() > MAX_PAGE_NAME_LENGTH
            ? collapsed.substring( 0, MAX_PAGE_NAME_LENGTH ).trim()
            : collapsed;

        // Step 6: empty fallback
        if ( capped.isEmpty() ) {
            return "Document";
        }

        // Step 7: uppercase first character (cleanLink stability)
        return Character.toUpperCase( capped.charAt( 0 ) ) + capped.substring( 1 );
    }
}
