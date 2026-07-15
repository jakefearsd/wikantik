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
package com.wikantik.connectors.github;

import com.wikantik.api.connectors.SourceItem;
import com.wikantik.connectors.ItemDigest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Builds the {@link SourceItem} for a GitHub markdown file: github:// uri, metadata, content hash.
 *  Metadata keys mirror DriveItems' convention; no modifiedTime (the tree listing carries none). */
final class GithubItems {
    private GithubItems() {}

    static SourceItem toItem( final String repo, final String branch, final GithubFile f, final byte[] bytes ) {
        final Map< String, Object > md = new LinkedHashMap<>();
        md.put( "id", f.path() );
        md.put( "name", f.path().substring( f.path().lastIndexOf( '/' ) + 1 ) );
        md.put( "mimeType", "text/markdown" );
        final String webUrl = "https://github.com/" + repo + "/blob/" + encodeSegments( branch ) + "/" + encodeSegments( f.path() );
        md.put( "webViewLink", webUrl );
        md.put( "source_url", webUrl );
        return new SourceItem( "github://" + repo + "/" + f.path(), bytes, "text/markdown",
            md, List.of(), ItemDigest.sha256Hex( bytes ) );
    }

    /** Percent-encodes each '/'-delimited segment of a path (or a bare branch name) so that
     *  URI-illegal characters — spaces, etc. — don't break URL construction. */
    static String encodeSegments( final String path ) {
        final StringBuilder sb = new StringBuilder();
        for ( final String seg : path.split( "/" ) ) {
            if ( sb.length() > 0 ) sb.append( '/' );
            sb.append( java.net.URLEncoder.encode( seg, java.nio.charset.StandardCharsets.UTF_8 ).replace( "+", "%20" ) );
        }
        return sb.toString();
    }
}
