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
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;

class ConfluenceItemsTest {

    @Test void buildsUriMetadataAndHtmlBody() {
        ConfluencePage p = new ConfluencePage( "12345", "Team Handbook", 7,
            "/spaces/ENG/pages/12345/Team+Handbook", "<p>hello <strong>world</strong></p>" );
        SourceItem item = ConfluenceItems.toItem( "https://acme.atlassian.net", "ENG", p );
        assertEquals( "confluence://ENG/12345", item.sourceUri() );
        assertEquals( "text/html", item.contentType() );   // existing ingest path converts to markdown
        assertArrayEquals( "<p>hello <strong>world</strong></p>".getBytes( StandardCharsets.UTF_8 ), item.content() );
        assertTrue( item.aclRefs().isEmpty() );
        assertEquals( 64, item.contentHash().length() );
        assertEquals( "12345", item.sourceMetadata().get( "id" ) );
        assertEquals( "Team Handbook", item.sourceMetadata().get( "name" ) );
        assertEquals( "text/html", item.sourceMetadata().get( "mimeType" ) );
        assertEquals( 7, item.sourceMetadata().get( "version" ) );
        assertEquals( "https://acme.atlassian.net/wiki/spaces/ENG/pages/12345/Team+Handbook",
            item.sourceMetadata().get( "webViewLink" ) );
        assertEquals( "https://acme.atlassian.net/wiki/spaces/ENG/pages/12345/Team+Handbook",
            item.sourceMetadata().get( "source_url" ) );
        assertFalse( item.sourceMetadata().containsKey( "modifiedTime" ) );
    }
}
