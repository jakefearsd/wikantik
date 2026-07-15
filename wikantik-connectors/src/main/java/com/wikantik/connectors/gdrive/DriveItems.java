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
package com.wikantik.connectors.gdrive;

import com.wikantik.api.connectors.SourceItem;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Builds the {@link SourceItem} for a Drive file: gdrive:// uri, metadata, content hash. */
final class DriveItems {
    private DriveItems() {}

    static SourceItem toItem( final DriveFile f, final byte[] bytes, final String contentType ) {
        final Map< String, Object > md = new LinkedHashMap<>();
        md.put( "id", f.id() );
        md.put( "name", f.name() );
        md.put( "mimeType", f.mimeType() );
        md.put( "modifiedTime", f.modifiedTime() );
        md.put( "webViewLink", f.webViewLink() );
        md.put( "source_url", f.webViewLink() );
        return new SourceItem( "gdrive://" + f.id(), bytes, contentType, md, List.of(), sha256Hex( bytes ) );
    }

    static String sha256Hex( final byte[] bytes ) {
        try {
            final byte[] d = MessageDigest.getInstance( "SHA-256" ).digest( bytes );
            final StringBuilder sb = new StringBuilder( d.length * 2 );
            for ( final byte b : d ) sb.append( Character.forDigit( ( b >> 4 ) & 0xF, 16 ) )
                                       .append( Character.forDigit( b & 0xF, 16 ) );
            return sb.toString();
        } catch ( final NoSuchAlgorithmException e ) {
            throw new IllegalStateException( "SHA-256 unavailable", e );   // JDK guarantees SHA-256
        }
    }
}
