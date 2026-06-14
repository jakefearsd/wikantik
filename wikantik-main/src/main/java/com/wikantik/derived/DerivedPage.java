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

    /**
     * Stable page name from the source filename (NOT the extracted title — see spec §A).
     *
     * <p>The first character is always uppercased so the name is consistent with what
     * {@code MarkupParser.cleanLink()} produces when the attachment manager resolves the
     * parent-page name from a slash-separated attachment path.  Without this,
     * {@code DefaultAttachmentManager.getAttachmentInfo("slug/slug.txt")} applies
     * {@code cleanLink} and capitalises the first letter, then calls {@code getPage} with
     * the capitalised name — which misses the page if it was stored in all-lowercase.
     */
    public static String pageNameFor( final String filename ) {
        final int dot = filename.lastIndexOf( '.' );
        final String stem = ( dot > 0 ? filename.substring( 0, dot ) : filename ).trim();
        if ( stem.isEmpty() ) {
            return stem;
        }
        return Character.toUpperCase( stem.charAt( 0 ) ) + stem.substring( 1 );
    }
}
