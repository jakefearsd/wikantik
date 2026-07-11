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
import java.nio.charset.StandardCharsets;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
class RobotsPolicyTest {
    private static PageFetcher fetcherServing( Map<String,String> robotsByHostUrl ) {
        return url -> {
            String body = robotsByHostUrl.get( url );
            if ( body == null ) return new FetchResult( 404, "text/plain", new byte[0], url );
            return new FetchResult( 200, "text/plain", body.getBytes( StandardCharsets.UTF_8 ), url );
        };
    }
    @Test void disallowHonored() {
        RobotsPolicy p = new RobotsPolicy( fetcherServing( Map.of(
            "https://ex.com/robots.txt", "User-agent: *\nDisallow: /private\n" ) ), "WikantikCrawler/1.0" );
        assertTrue( p.isAllowed( "https://ex.com/public/x" ) );
        assertFalse( p.isAllowed( "https://ex.com/private/y" ) );
    }
    @Test void crawlDelayParsed() {
        RobotsPolicy p = new RobotsPolicy( fetcherServing( Map.of(
            "https://ex.com/robots.txt", "User-agent: *\nCrawl-delay: 2\n" ) ), "WikantikCrawler/1.0" );
        // Crawl-delay: 2 → exactly 2000 ms. Bound BOTH ends: a one-sided >=2000 would also pass a
        // seconds-vs-ms unit bug (2s treated as 2,000,000 ms), the exact regression this locks in.
        assertEquals( 2000L, p.crawlDelayMs( "https://ex.com/x" ) );
    }
    @Test void unreachableRobotsAllowsAll() {
        RobotsPolicy p = new RobotsPolicy( fetcherServing( Map.of() ), "WikantikCrawler/1.0" ); // 404 robots
        assertTrue( p.isAllowed( "https://ex.com/anything" ) );
    }
}
