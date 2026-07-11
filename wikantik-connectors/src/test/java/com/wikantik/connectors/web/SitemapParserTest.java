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
package com.wikantik.connectors.web;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class SitemapParserTest {
    @Test void parsesUrlsetPageLocs() {
        String xml = "<?xml version='1.0'?><urlset xmlns='http://www.sitemaps.org/schemas/sitemap/0.9'>"
            + "<url><loc>https://ex.com/a</loc></url><url><loc> https://ex.com/b </loc></url>"
            + "<url><loc></loc></url></urlset>";
        ParsedSitemap p = SitemapParser.parse( xml );
        assertFalse( p.isIndex() );
        assertEquals( List.of( "https://ex.com/a", "https://ex.com/b" ), p.locs() );  // trimmed, blank dropped
    }
    @Test void parsesSitemapIndexSubSitemaps() {
        String xml = "<?xml version='1.0'?><sitemapindex xmlns='http://www.sitemaps.org/schemas/sitemap/0.9'>"
            + "<sitemap><loc>https://ex.com/sm1.xml</loc></sitemap>"
            + "<sitemap><loc>https://ex.com/sm2.xml</loc></sitemap></sitemapindex>";
        ParsedSitemap p = SitemapParser.parse( xml );
        assertTrue( p.isIndex() );
        assertEquals( List.of( "https://ex.com/sm1.xml", "https://ex.com/sm2.xml" ), p.locs() );
    }
    @Test void malformedOrEmptyIsSafe() {
        assertTrue( SitemapParser.parse( "" ).locs().isEmpty() );
        assertTrue( SitemapParser.parse( "not xml <<<" ).locs().isEmpty() );
    }
}
