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
}
