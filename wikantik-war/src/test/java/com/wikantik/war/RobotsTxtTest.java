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
package com.wikantik.war;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards robots.txt against accidental removal of the Sitemap: directive.
 * OpenWebUI's sync script and external crawlers both depend on it.
 */
class RobotsTxtTest {

    @Test
    void testRobotsTxtContainsSitemapLine() throws Exception {
        final Path robots = Paths.get( "src", "main", "webapp", "robots.txt" );
        assertTrue( Files.exists( robots ), "robots.txt must exist at " + robots.toAbsolutePath() );

        final String content = Files.readString( robots );
        assertTrue( content.contains( "Sitemap: " ),
                "robots.txt must contain a 'Sitemap:' directive; was: " + content );
        assertTrue( content.contains( "/sitemap.xml" ),
                "robots.txt Sitemap: must reference /sitemap.xml; was: " + content );
    }

    @Test
    void testRenderCriticalPageApiStaysCrawlable() throws Exception {
        // Regression (Google "Soft 404"): the React reader (PageView.jsx) fetches
        // /api/pages/{name}?render=true to render the page body. Google's renderer
        // (WRS) will NOT fetch robots-disallowed resources, so a blanket
        // `Disallow: /api/` left rendered pages empty and Google classified them as
        // Soft 404. The read-only page-content path must remain crawlable, and the
        // Allow must precede the broad Disallow so first-match crawlers honour it.
        final Path robots = Paths.get( "src", "main", "webapp", "robots.txt" );
        final String content = Files.readString( robots );

        final int allowIdx = content.indexOf( "Allow: /api/pages/" );
        final int disallowApiIdx = content.indexOf( "Disallow: /api/" );

        assertTrue( allowIdx >= 0,
                "robots.txt must 'Allow: /api/pages/' so Googlebot can fetch page "
                        + "content during render (avoids Soft 404); was: " + content );
        assertTrue( disallowApiIdx >= 0,
                "robots.txt should still 'Disallow: /api/' for the rest of the API; was: " + content );
        assertTrue( allowIdx < disallowApiIdx,
                "'Allow: /api/pages/' must appear before 'Disallow: /api/' so first-match "
                        + "crawlers honour the allow; was: " + content );
    }

    @Test
    void testSitemapPointsAtCanonicalDomain() throws Exception {
        final Path robots = Paths.get( "src", "main", "webapp", "robots.txt" );
        final String content = Files.readString( robots );

        // Cross-domain sitemap references are ignored by Google unless both
        // domains are verified together, so the Sitemap: directive MUST point at
        // the canonical host that actually serves the wiki.
        assertTrue( content.contains( "Sitemap: https://wiki.wikantik.com/sitemap.xml" ),
                "robots.txt Sitemap: must point at https://wiki.wikantik.com/sitemap.xml; was: " + content );
        assertFalse( content.contains( "jakefear" ),
                "robots.txt must not reference the legacy jakefear.com domain; was: " + content );
    }
}
