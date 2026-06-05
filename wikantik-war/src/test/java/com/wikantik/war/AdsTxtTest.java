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
 * Guards the wiki's ads.txt against accidental removal or corruption of the
 * AdSense authorized-seller line. Google's crawler fetches
 * {@code https://wiki.wikantik.com/ads.txt} verbatim; any drift in the
 * publisher ID, relationship, or certification-authority ID silently breaks
 * ad-ownership verification.
 *
 * <p>The file is served as a plain static asset: {@code SpaRoutingFilter}
 * passes any path containing a {@code .} (other than {@code .html}) straight
 * through to Tomcat's default servlet, so {@code /ads.txt} is never swallowed
 * by SPA routing — exactly like {@code robots.txt}.
 */
class AdsTxtTest {

    /** Exact authorized-seller record from the AdSense UI (DIRECT relationship). */
    private static final String AUTHORIZED_SELLER_LINE =
            "google.com, pub-5083997587716933, DIRECT, f08c47fec0942fa0";

    @Test
    void testAdsTxtExists() throws Exception {
        final Path ads = Paths.get( "src", "main", "webapp", "ads.txt" );
        assertTrue( Files.exists( ads ), "ads.txt must exist at " + ads.toAbsolutePath() );
    }

    @Test
    void testAdsTxtContainsAuthorizedSellerLine() throws Exception {
        final Path ads = Paths.get( "src", "main", "webapp", "ads.txt" );
        final String content = Files.readString( ads );
        assertTrue( content.contains( AUTHORIZED_SELLER_LINE ),
                "ads.txt must contain the AdSense authorized-seller line '"
                        + AUTHORIZED_SELLER_LINE + "'; was: " + content );
    }

    @Test
    void testAdsTxtHasNoHtmlContamination() throws Exception {
        // If SPA routing ever regresses and the build ships index.html in place of
        // ads.txt, the crawler would parse markup and fail verification. Catch the
        // obvious shape of that mistake at build time.
        final Path ads = Paths.get( "src", "main", "webapp", "ads.txt" );
        final String content = Files.readString( ads );
        assertFalse( content.contains( "<html" ) || content.contains( "<!DOCTYPE" ),
                "ads.txt must be plain text, not HTML; was: " + content );
    }
}
