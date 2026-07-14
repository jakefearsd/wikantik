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
package com.wikantik.connectors.confluence;

import com.wikantik.api.connectors.SourceItem;
import com.wikantik.connectors.ItemDigest;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Builds the {@link SourceItem} for a Confluence page: confluence:// uri, metadata, content hash.
 *  Content is the storage-format XHTML as text/html — the existing ingest path (TikaSourceExtractor)
 *  converts it to markdown; <ac:*> macros degrade to their text content (accepted v1 trade-off). */
final class ConfluenceItems {
    private ConfluenceItems() {}

    static SourceItem toItem( final String baseUrl, final String spaceKey, final ConfluencePage p ) {
        final byte[] bytes = p.storageXhtml().getBytes( StandardCharsets.UTF_8 );
        final Map< String, Object > md = new LinkedHashMap<>();
        md.put( "id", p.id() );
        md.put( "name", p.title() );
        md.put( "mimeType", "text/html" );
        md.put( "version", p.version() );
        md.put( "webViewLink", baseUrl + "/wiki" + p.webuiPath() );
        return new SourceItem( "confluence://" + spaceKey + "/" + p.id(), bytes, "text/html",
            md, List.of(), ItemDigest.sha256Hex( bytes ) );
    }
}
