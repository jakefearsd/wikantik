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

import com.wikantik.api.connectors.CredentialStore;
import com.wikantik.api.connectors.SourceConnector;
import com.wikantik.connectors.confluence.ConfluenceConfig;
import com.wikantik.connectors.gdrive.DriveConfig;
import com.wikantik.connectors.github.GithubConfig;
import com.wikantik.connectors.web.FeedConfig;
import com.wikantik.connectors.web.SitemapConfig;
import com.wikantik.connectors.web.WebCrawlerConfig;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConnectorAssemblerTest {

    private final CredentialStore store = new CredentialStore() {
        @Override public boolean enabled() { return true; }
        @Override public void put( final String c, final String n, final String s ) {}
        @Override public Optional< String > get( final String c, final String n ) { return Optional.of( "sekrit-" + n ); }
        @Override public List< String > list( final String c ) { return List.of(); }
        @Override public void delete( final String c, final String n ) {}
    };

    @Test void buildsEachUiType() {
        assertTrue( ConnectorAssembler.build( "w", "webcrawler",
            new WebCrawlerConfig( List.of( "https://e.com" ), true, null, 10, 2, 0L, "UA", true ), store ).isPresent() );
        assertTrue( ConnectorAssembler.build( "s", "sitemap",
            new SitemapConfig( List.of( "https://e.com/sitemap.xml" ), 10, 0L, "UA", true, true ), store ).isPresent() );
        assertTrue( ConnectorAssembler.build( "f", "feed",
            new FeedConfig( List.of( "https://e.com/rss" ), 10, true, 0L, "UA", true, true ), store ).isPresent() );
        assertTrue( ConnectorAssembler.build( "g", "gdrive",
            new DriveConfig( List.of( "folder1" ), 10, "cid", null, "https://cb", "text/markdown" ), store ).isPresent() );
        assertTrue( ConnectorAssembler.build( "gh", "github",
            new GithubConfig( "o/r", null, null, 10 ), store ).isPresent() );
        assertTrue( ConnectorAssembler.build( "cf", "confluence",
            new ConfluenceConfig( "https://x.atlassian.net", "SP", "a@b.c", 10 ), store ).isPresent() );
    }

    @Test void unknownTypeIsEmpty() {
        assertTrue( ConnectorAssembler.build( "x", "nope", new Object(), store ).isEmpty() );
    }

    @Test void connectorIdsPropagate() {
        final SourceConnector c = ConnectorAssembler.build( "gh", "github",
            new GithubConfig( "o/r", null, null, 10 ), store ).orElseThrow();
        assertEquals( "gh", c.connectorId() );
    }
}
