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
class LinkExtractorTest {
    @Test void extractsAbsoluteLinksAndResolvesRelative() {
        String html = "<html><body><a href='/a'>a</a><a href='https://ex.com/b'>b</a>"
            + "<a href='sub/c'>c</a><a href='mailto:x@y.z'>m</a></body></html>";
        List<String> links = LinkExtractor.links( html, "https://ex.com/dir/" );
        assertTrue( links.contains( "https://ex.com/a" ) );
        assertTrue( links.contains( "https://ex.com/b" ) );
        assertTrue( links.contains( "https://ex.com/dir/sub/c" ) );
        assertTrue( links.stream().anyMatch( l -> l.startsWith( "mailto:" ) ) ); // extracted; CrawlScope filters it
    }
    @Test void titleAndMalformedSafe() {
        assertEquals( "Hi", LinkExtractor.title( "<html><head><title>Hi</title></head></html>" ) );
        assertEquals( "", LinkExtractor.title( "" ) );
        assertTrue( LinkExtractor.links( "", "https://ex.com" ).isEmpty() );
    }
}
