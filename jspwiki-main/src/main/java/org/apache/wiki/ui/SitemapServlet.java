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
package org.apache.wiki.ui;

import java.io.IOException;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Collectors;

import jakarta.servlet.ServletConfig;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.wiki.api.core.Attachment;
import org.apache.wiki.api.core.Context;
import org.apache.wiki.api.core.ContextEnum;
import org.apache.wiki.api.core.Engine;
import org.apache.wiki.api.core.Page;
import org.apache.wiki.api.spi.Wiki;
import org.apache.wiki.attachment.AttachmentManager;
import org.apache.wiki.auth.AuthorizationManager;
import org.apache.wiki.auth.permissions.PagePermission;
import org.apache.wiki.pages.PageManager;
import org.apache.wiki.url.URLConstructor;
import org.apache.wiki.util.TextUtil;

/**
 * Servlet that generates a sitemap.xml file for search engine indexing.
 * <p>
 * The sitemap follows the Sitemap Protocol (https://www.sitemaps.org/protocol.html)
 * and includes the Google Image Sitemap extension for image attachments.
 * </p>
 * <p>
 * Note: This implementation only includes {@code <loc>} and {@code <lastmod>} fields.
 * The {@code <changefreq>} and {@code <priority>} fields are intentionally omitted
 * as Google has confirmed they ignore these values.
 * </p>
 * <p>
 * Menu pages (LeftMenu, LeftMenuFooter, TitleBox, etc.) are automatically excluded
 * from the sitemap as they are not primary content pages.
 * </p>
 * <p>
 * When running behind a reverse proxy that terminates SSL (like cloudflared, nginx,
 * or a load balancer), the sitemap URLs may incorrectly use http:// instead of https://.
 * Use the {@link #PROP_SITEMAP_BASE_URL} property to explicitly set the base URL.
 * </p>
 *
 * @see <a href="https://developers.google.com/search/docs/crawling-indexing/sitemaps/build-sitemap">Google Sitemap Documentation</a>
 * @see <a href="https://developers.google.com/search/docs/crawling-indexing/sitemaps/image-sitemaps">Google Image Sitemap Documentation</a>
 */
public class SitemapServlet extends HttpServlet {

    private static final long serialVersionUID = 1L;
    private static final Logger LOG = LogManager.getLogger( SitemapServlet.class );

    /**
     * Property name for explicit sitemap base URL configuration.
     * <p>
     * When JSPWiki runs behind a reverse proxy that terminates SSL, use this property
     * to explicitly set the base URL for sitemap generation with the correct protocol.
     * </p>
     * <p>
     * Example: {@code jspwiki.sitemap.baseURL = https://wiki.example.com/JSPWiki}
     * </p>
     * <p>
     * If not set, the base URL is derived from the incoming request, which works
     * correctly when JSPWiki handles SSL directly.
     * </p>
     */
    public static final String PROP_SITEMAP_BASE_URL = "jspwiki.sitemap.baseURL";

    /** Sitemap namespace */
    private static final String SITEMAP_NS = "http://www.sitemaps.org/schemas/sitemap/0.9";

    /** Google Image sitemap namespace */
    private static final String IMAGE_NS = "http://www.google.com/schemas/sitemap-image/1.1";

    /** Image file extensions that should be included in the sitemap */
    private static final Set<String> IMAGE_EXTENSIONS = new HashSet<>( Arrays.asList(
        "jpg", "jpeg", "png", "gif", "webp", "svg", "bmp", "ico"
    ) );

    /** Pages that should be excluded from the sitemap (menu/template pages) */
    private static final Set<String> EXCLUDED_PAGES = new HashSet<>( Arrays.asList(
        "LeftMenu",
        "LeftMenuFooter",
        "TitleBox",
        "MoreMenu",
        "CSSRibbon",
        "PageHeader",
        "PageFooter"
    ) );

    private Engine m_engine;
    private String m_configuredBaseUrl;

    @Override
    public void init( final ServletConfig config ) throws ServletException {
        super.init( config );
        m_engine = Wiki.engine().find( config );

        // Check for configured sitemap base URL
        final Properties props = m_engine.getWikiProperties();
        m_configuredBaseUrl = TextUtil.getStringProperty( props, PROP_SITEMAP_BASE_URL, null );

        if ( m_configuredBaseUrl != null && !m_configuredBaseUrl.isBlank() ) {
            LOG.info( "SitemapServlet initialized with configured baseURL: {}", m_configuredBaseUrl );
        } else {
            LOG.info( "SitemapServlet initialized (baseURL will be derived from request)" );
        }
    }

    @Override
    protected void doGet( final HttpServletRequest req, final HttpServletResponse resp )
            throws ServletException, IOException {

        LOG.debug( "Generating sitemap.xml" );

        // Get all pages
        final PageManager pageManager = m_engine.getManager( PageManager.class );
        final Collection<Page> allPages;
        try {
            allPages = pageManager.getAllPages();
        } catch ( final org.apache.wiki.api.exceptions.ProviderException e ) {
            LOG.error( "Error retrieving pages for sitemap: {}", e.getMessage() );
            resp.sendError( HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Error generating sitemap" );
            return;
        }

        // Filter pages: exclude menu pages and check permissions
        final AuthorizationManager authManager = m_engine.getManager( AuthorizationManager.class );
        final Context context = Wiki.context().create( m_engine, req, ContextEnum.PAGE_VIEW.getRequestContext() );

        final List<Page> publicPages = allPages.stream()
            .filter( page -> !isExcludedPage( page.getName() ) )
            .filter( page -> {
                try {
                    final PagePermission permission = new PagePermission( page, PagePermission.VIEW_ACTION );
                    return authManager.checkPermission( context.getWikiSession(), permission );
                } catch ( final Exception e ) {
                    LOG.debug( "Error checking permission for page {}: {}", page.getName(), e.getMessage() );
                    return false;
                }
            } )
            .collect( Collectors.toList() );

        LOG.info( "Filtered {} public pages from {} total pages", publicPages.size(), allPages.size() );

        // Generate XML output
        resp.setContentType( "application/xml" );
        resp.setCharacterEncoding( "UTF-8" );

        final PrintWriter out = resp.getWriter();
        out.println( "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" );
        out.println( "<urlset xmlns=\"" + SITEMAP_NS + "\"" );
        out.println( "        xmlns:image=\"" + IMAGE_NS + "\">" );

        final SimpleDateFormat dateFormat = new SimpleDateFormat( "yyyy-MM-dd", Locale.ROOT );
        final URLConstructor urlConstructor = m_engine.getManager( URLConstructor.class );
        final AttachmentManager attachmentManager = m_engine.getManager( AttachmentManager.class );

        // Build fully qualified base URL
        // First check for explicit sitemap baseURL configuration (for proxy/HTTPS scenarios)
        String baseUrl = m_configuredBaseUrl;

        // Fall back to engine's baseURL if no sitemap-specific URL configured
        if ( baseUrl == null || baseUrl.isBlank() ) {
            baseUrl = m_engine.getBaseURL();
        }

        // If still no protocol, build from request (original behavior)
        if ( baseUrl == null || !baseUrl.startsWith( "http" ) ) {
            final String scheme = req.getScheme();
            final String serverName = req.getServerName();
            final int serverPort = req.getServerPort();
            final String contextPath = req.getContextPath();

            if ( scheme != null && serverName != null ) {
                baseUrl = scheme + "://" + serverName;
                if ( ( "http".equals( scheme ) && serverPort != 80 ) ||
                     ( "https".equals( scheme ) && serverPort != 443 ) ) {
                    baseUrl += ":" + serverPort;
                }
                if ( contextPath != null ) {
                    baseUrl += contextPath;
                }
            } else {
                // Fallback to a safe default if request info is not available
                baseUrl = "http://localhost";
                if ( contextPath != null ) {
                    baseUrl += contextPath;
                }
            }
        }

        // Ensure baseUrl doesn't end with slash for consistent concatenation
        if ( baseUrl.endsWith( "/" ) ) {
            baseUrl = baseUrl.substring( 0, baseUrl.length() - 1 );
        }

        for ( final Page page : publicPages ) {
            // Get page URL from URLConstructor - this already includes the base URL
            final String pageUrl = urlConstructor.makeURL(
                ContextEnum.PAGE_VIEW.getRequestContext(),
                page.getName(),
                null
            );

            // The URLConstructor returns a full URL, but we need to ensure it uses
            // the correct base URL (especially for HTTPS behind proxy)
            final String finalUrl = fixBaseUrl( pageUrl, baseUrl );

            out.println( "  <url>" );
            out.println( "    <loc>" + escapeXml( finalUrl ) + "</loc>" );

            if ( page.getLastModified() != null ) {
                out.println( "    <lastmod>" + dateFormat.format( page.getLastModified() ) + "</lastmod>" );
            }

            // Add image entries for image attachments
            // Note: changefreq and priority are intentionally omitted as Google ignores them
            writeImageEntries( out, page, baseUrl, attachmentManager );

            out.println( "  </url>" );
        }

        out.println( "</urlset>" );

        LOG.debug( "Sitemap generated with {} pages", publicPages.size() );
    }

    /**
     * Fixes the base URL in a generated URL to use the configured base URL.
     * <p>
     * The URLConstructor generates URLs using the engine's base URL, but when
     * running behind a proxy, we may need to use a different base URL (e.g., HTTPS).
     * This method extracts the path portion from the generated URL and combines
     * it with the correct base URL.
     * </p>
     *
     * @param generatedUrl the URL generated by URLConstructor
     * @param correctBaseUrl the correct base URL to use
     * @return the URL with the correct base
     */
    private String fixBaseUrl( final String generatedUrl, final String correctBaseUrl ) {
        // The generated URL format is like: http://localhost:8080/JSPWiki/wiki/PageName
        // We need to extract the path after the context path and combine with correctBaseUrl

        // Find the context path pattern (e.g., /JSPWiki/) in the URL
        // Look for the path portion after host:port
        final int protocolEnd = generatedUrl.indexOf( "://" );
        if ( protocolEnd < 0 ) {
            // Not a full URL, treat as path
            return correctBaseUrl + ( generatedUrl.startsWith( "/" ) ? generatedUrl : "/" + generatedUrl );
        }

        // Find the first slash after the protocol
        final int pathStart = generatedUrl.indexOf( '/', protocolEnd + 3 );
        if ( pathStart < 0 ) {
            // No path portion
            return correctBaseUrl;
        }

        // Extract path (e.g., /JSPWiki/wiki/PageName)
        final String path = generatedUrl.substring( pathStart );

        // The correctBaseUrl already includes context path (e.g., http://example.com/JSPWiki)
        // The path also starts with context path (e.g., /JSPWiki/wiki/PageName)
        // We need to avoid duplicating the context path

        // Find the context path in correctBaseUrl
        final int correctProtocolEnd = correctBaseUrl.indexOf( "://" );
        final int correctPathStart = correctBaseUrl.indexOf( '/', correctProtocolEnd + 3 );

        if ( correctPathStart >= 0 ) {
            // correctBaseUrl has a context path
            final String contextPath = correctBaseUrl.substring( correctPathStart );
            if ( path.startsWith( contextPath ) ) {
                // Remove context path from path to avoid duplication
                final String pathWithoutContext = path.substring( contextPath.length() );
                return correctBaseUrl + pathWithoutContext;
            }
        }

        // No context path duplication issue
        return correctBaseUrl + path;
    }

    /**
     * Builds a fully qualified URL from base URL and path.
     *
     * @param baseUrl the base URL
     * @param path the path to append
     * @return the fully qualified URL
     */
    private String buildFullUrl( final String baseUrl, final String path ) {
        if ( path.startsWith( "/" ) ) {
            return baseUrl + path;
        }
        return baseUrl + "/" + path;
    }

    /**
     * Writes image:image entries for any image attachments on the page.
     *
     * @param out the PrintWriter to write to
     * @param page the page to check for attachments
     * @param baseUrl the base URL for building attachment URLs
     * @param attachmentManager the attachment manager
     */
    private void writeImageEntries( final PrintWriter out, final Page page,
                                    final String baseUrl, final AttachmentManager attachmentManager ) {
        if ( attachmentManager == null || !attachmentManager.attachmentsEnabled() ) {
            return;
        }

        try {
            final List<Attachment> attachments = attachmentManager.listAttachments( page );
            for ( final Attachment attachment : attachments ) {
                if ( isImageAttachment( attachment.getFileName() ) ) {
                    final String attachmentUrl = buildAttachmentUrl( baseUrl, attachment );
                    out.println( "    <image:image>" );
                    out.println( "      <image:loc>" + escapeXml( attachmentUrl ) + "</image:loc>" );
                    out.println( "    </image:image>" );
                }
            }
        } catch ( final Exception e ) {
            LOG.debug( "Error listing attachments for page {}: {}", page.getName(), e.getMessage() );
        }
    }

    /**
     * Checks if a filename represents an image based on its extension.
     *
     * @param fileName the file name to check
     * @return true if the file appears to be an image
     */
    private boolean isImageAttachment( final String fileName ) {
        if ( fileName == null || fileName.isEmpty() ) {
            return false;
        }
        final int dotIndex = fileName.lastIndexOf( '.' );
        if ( dotIndex < 0 || dotIndex == fileName.length() - 1 ) {
            return false;
        }
        final String extension = fileName.substring( dotIndex + 1 ).toLowerCase( Locale.ROOT );
        return IMAGE_EXTENSIONS.contains( extension );
    }

    /**
     * Builds the URL for an attachment.
     *
     * @param baseUrl the base URL
     * @param attachment the attachment
     * @return the attachment URL
     */
    private String buildAttachmentUrl( final String baseUrl, final Attachment attachment ) {
        // Attachment URLs follow the pattern: /attach/ParentPage/filename
        return baseUrl + "/attach/" + attachment.getParentName() + "/" + attachment.getFileName();
    }

    /**
     * Checks if a page should be excluded from the sitemap.
     *
     * @param pageName the name of the page
     * @return true if the page should be excluded
     */
    private boolean isExcludedPage( final String pageName ) {
        return EXCLUDED_PAGES.contains( pageName ) || pageName.startsWith( "CSS" );
    }

    /**
     * Escapes special XML characters in a string.
     *
     * @param input the string to escape
     * @return the escaped string
     */
    private String escapeXml( final String input ) {
        if ( input == null ) {
            return "";
        }
        return input
            .replace( "&", "&amp;" )
            .replace( "<", "&lt;" )
            .replace( ">", "&gt;" )
            .replace( "\"", "&quot;" )
            .replace( "'", "&apos;" );
    }

}
