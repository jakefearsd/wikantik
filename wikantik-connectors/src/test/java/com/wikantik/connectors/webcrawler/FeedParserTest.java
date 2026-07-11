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
package com.wikantik.connectors.webcrawler;

import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class FeedParserTest {
    private static List<FeedEntry> parse( String xml ) {
        return FeedParser.parse( xml.getBytes( StandardCharsets.UTF_8 ), "https://ex.com/feed" );
    }

    @Test void parsesRss2WithContentEncoded() {
        String rss = "<?xml version='1.0'?><rss version='2.0' xmlns:content='http://purl.org/rss/1.0/modules/content/'>"
            + "<channel><title>Ch</title>"
            + "<item><title>A</title><link>https://ex.com/a</link>"
            + "<description>sum-a</description><content:encoded>&lt;p&gt;full a&lt;/p&gt;</content:encoded></item>"
            + "<item><title>B</title><link>https://ex.com/b</link><description>sum-b</description></item>"
            + "</channel></rss>";
        List<FeedEntry> e = parse( rss );
        assertEquals( 2, e.size() );
        assertEquals( "A", e.get( 0 ).title() );
        assertEquals( "https://ex.com/a", e.get( 0 ).link() );
        assertTrue( e.get( 0 ).contentHtml().contains( "full a" ) );   // content:encoded preferred
        assertTrue( e.get( 1 ).contentHtml().contains( "sum-b" ) );    // description fallback
    }

    @Test void parsesAtom() {
        String atom = "<?xml version='1.0'?><feed xmlns='http://www.w3.org/2005/Atom'><title>F</title>"
            + "<entry><title>E1</title><link href='https://ex.com/e1'/><content type='html'>&lt;p&gt;c1&lt;/p&gt;</content></entry>"
            + "<entry><title>E2</title><link href='https://ex.com/e2'/><summary>s2</summary></entry></feed>";
        List<FeedEntry> e = parse( atom );
        assertEquals( 2, e.size() );
        assertEquals( "https://ex.com/e1", e.get( 0 ).link() );        // Atom link href
        assertTrue( e.get( 0 ).contentHtml().contains( "c1" ) );
        assertTrue( e.get( 1 ).contentHtml().contains( "s2" ) );       // summary fallback
    }

    @Test void skipsBlankLinkAndMalformedIsSafe() {
        assertTrue( parse( "" ).isEmpty() );
        assertTrue( parse( "not a feed <<<" ).isEmpty() );
    }
}
