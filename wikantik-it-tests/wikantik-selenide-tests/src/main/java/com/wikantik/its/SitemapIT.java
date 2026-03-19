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
package com.wikantik.its;

import com.wikantik.its.environment.Env;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.stream.Collectors;


/**
 * Integration tests for the Sitemap servlet.
 * <p>
 * The sitemap follows the Sitemap Protocol and includes the Google Image Sitemap extension.
 * Note: changefreq and priority are intentionally omitted as Google ignores these values.
 * </p>
 */
public class SitemapIT extends WithIntegrationTestSetup {

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void sitemapReturnsValidXml() throws Exception {
        final String sitemap = fetchSitemap();

        // Verify XML structure
        Assertions.assertTrue( sitemap.contains( "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" ),
            "Sitemap should contain XML declaration" );
        Assertions.assertTrue( sitemap.contains( "<urlset xmlns=\"http://www.sitemaps.org/schemas/sitemap/0.9\"" ),
            "Sitemap should contain urlset with correct namespace" );
        Assertions.assertTrue( sitemap.contains( "</urlset>" ),
            "Sitemap should contain closing urlset tag" );
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void sitemapIncludesImageNamespace() throws Exception {
        final String sitemap = fetchSitemap();

        // Verify image namespace is included for Google Image Sitemap extension
        Assertions.assertTrue( sitemap.contains( "xmlns:image=\"http://www.google.com/schemas/sitemap-image/1.1\"" ),
            "Sitemap should contain Google Image sitemap namespace" );
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void sitemapContainsMainPage() throws Exception {
        final String sitemap = fetchSitemap();

        // The Main page should be in the sitemap
        Assertions.assertTrue( sitemap.contains( "Main" ),
            "Sitemap should contain the Main page" );
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void sitemapUrlsAreFullyQualified() throws Exception {
        final String sitemap = fetchSitemap();

        // All URLs in the sitemap should be fully qualified
        Assertions.assertTrue( sitemap.contains( "<loc>http://" ) || sitemap.contains( "<loc>https://" ),
            "Sitemap URLs should be fully qualified with http:// or https://" );

        // Should not contain relative URLs in loc elements
        Assertions.assertFalse( sitemap.contains( "<loc>/wiki/" ),
            "Sitemap URLs should not be relative paths" );

        // Verify URLs contain the expected host
        Assertions.assertTrue( sitemap.contains( "localhost:8080" ),
            "Sitemap URLs should contain the host and port" );
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void sitemapExcludesMenuPages() throws Exception {
        final String sitemap = fetchSitemap();

        // Menu pages should NOT be in the sitemap
        // These are template pages used for UI structure, not content pages
        assertPageNotInSitemap( sitemap, "LeftMenu" );
        assertPageNotInSitemap( sitemap, "LeftMenuFooter" );
        assertPageNotInSitemap( sitemap, "TitleBox" );
        assertPageNotInSitemap( sitemap, "MoreMenu" );
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void sitemapContainsRequiredElements() throws Exception {
        final String sitemap = fetchSitemap();

        // Verify sitemap contains required elements for each URL
        Assertions.assertTrue( sitemap.contains( "<url>" ),
            "Sitemap should contain url elements" );
        Assertions.assertTrue( sitemap.contains( "<loc>" ),
            "Sitemap should contain loc elements" );
        Assertions.assertTrue( sitemap.contains( "<lastmod>" ),
            "Sitemap should contain lastmod elements" );
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void sitemapOmitsChangefreqAndPriority() throws Exception {
        final String sitemap = fetchSitemap();

        // Verify changefreq and priority are NOT present
        // These are intentionally omitted as Google has confirmed they ignore these values
        Assertions.assertFalse( sitemap.contains( "<changefreq>" ),
            "Sitemap should NOT contain changefreq (Google ignores it)" );
        Assertions.assertFalse( sitemap.contains( "<priority>" ),
            "Sitemap should NOT contain priority (Google ignores it)" );
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void sitemapHasCorrectContentType() throws Exception {
        // Construct the sitemap URL
        String baseUrl = Env.TESTS_BASE_URL;
        String sitemapUrl;
        if ( baseUrl.endsWith( "/" ) ) {
            sitemapUrl = baseUrl + "sitemap.xml";
        } else {
            sitemapUrl = baseUrl + "/sitemap.xml";
        }

        final URL url = URI.create( sitemapUrl ).toURL();
        final HttpURLConnection connection = (HttpURLConnection) url.openConnection();

        try {
            connection.setRequestMethod( "GET" );
            final int responseCode = connection.getResponseCode();

            Assertions.assertEquals( 200, responseCode,
                "Sitemap should return HTTP 200" );

            final String contentType = connection.getContentType();
            Assertions.assertTrue( contentType != null && contentType.contains( "application/xml" ),
                "Sitemap should have application/xml content type, but was: " + contentType );
        } finally {
            connection.disconnect();
        }
    }

    /**
     * Fetches the sitemap content from the test server.
     *
     * @return the sitemap XML content
     * @throws Exception if there's an error fetching the sitemap
     */
    private String fetchSitemap() throws Exception {
        // Construct the sitemap URL - handle both root deployment and context path deployment
        String baseUrl = Env.TESTS_BASE_URL;
        String sitemapUrl;
        if ( baseUrl.endsWith( "/" ) ) {
            sitemapUrl = baseUrl + "sitemap.xml";
        } else {
            sitemapUrl = baseUrl + "/sitemap.xml";
        }

        final URL url = URI.create( sitemapUrl ).toURL();
        final HttpURLConnection connection = (HttpURLConnection) url.openConnection();

        try {
            connection.setRequestMethod( "GET" );
            final int responseCode = connection.getResponseCode();

            Assertions.assertEquals( 200, responseCode,
                "Sitemap should return HTTP 200, but returned: " + responseCode + " for URL: " + sitemapUrl );

            try ( BufferedReader reader = new BufferedReader(
                    new InputStreamReader( connection.getInputStream(), StandardCharsets.UTF_8 ) ) ) {
                return reader.lines().collect( Collectors.joining( "\n" ) );
            }
        } finally {
            connection.disconnect();
        }
    }

    /**
     * Asserts that a page name does not appear in the sitemap URLs.
     * This checks that the page name doesn't appear in a loc element.
     *
     * @param sitemap the sitemap XML content
     * @param pageName the page name to check
     */
    private void assertPageNotInSitemap( final String sitemap, final String pageName ) {
        // Check if the page name appears in a <loc> element
        // We need to be careful not to match partial names, so we check for the page name
        // in URL patterns like /wiki/PageName or ?page=PageName
        final boolean containsInUrl = sitemap.contains( "/wiki/" + pageName ) ||
                                       sitemap.contains( "page=" + pageName ) ||
                                       sitemap.contains( "/" + pageName + "</loc>" );

        Assertions.assertFalse( containsInUrl,
            "Sitemap should not contain the menu page: " + pageName );
    }

}
