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
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import jakarta.servlet.ServletConfig;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.wiki.api.core.Context;
import org.apache.wiki.api.core.ContextEnum;
import org.apache.wiki.api.core.Engine;
import org.apache.wiki.api.core.Page;
import org.apache.wiki.api.spi.Wiki;
import org.apache.wiki.auth.AuthorizationManager;
import org.apache.wiki.auth.permissions.PagePermission;
import org.apache.wiki.pages.PageManager;
import org.apache.wiki.references.ReferenceManager;
import org.apache.wiki.url.URLConstructor;

/**
 * Servlet that generates a sitemap.xml file for search engine indexing.
 * <p>
 * The sitemap follows the Sitemap Protocol (https://www.sitemaps.org/protocol.html)
 * and is compatible with Google Search Console requirements.
 * </p>
 * <p>
 * Menu pages (LeftMenu, LeftMenuFooter, TitleBox, etc.) are automatically excluded
 * from the sitemap as they are not primary content pages.
 * </p>
 */
public class SitemapServlet extends HttpServlet {

    private static final long serialVersionUID = 1L;
    private static final Logger LOG = LogManager.getLogger( SitemapServlet.class );

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

    @Override
    public void init( final ServletConfig config ) throws ServletException {
        super.init( config );
        m_engine = Wiki.engine().find( config );
        LOG.info( "SitemapServlet initialized." );
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
        out.println( "<urlset xmlns=\"http://www.sitemaps.org/schemas/sitemap/0.9\">" );

        final SimpleDateFormat dateFormat = new SimpleDateFormat( "yyyy-MM-dd" );
        final URLConstructor urlConstructor = m_engine.getManager( URLConstructor.class );

        // Build fully qualified base URL
        String baseUrl = m_engine.getBaseURL();

        // If baseUrl doesn't have protocol, build from request
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
            // Get page path from URLConstructor
            final String pagePath = urlConstructor.makeURL(
                ContextEnum.PAGE_VIEW.getRequestContext(),
                page.getName(),
                null
            );

            // Build fully qualified URL
            final String pageUrl;
            if ( pagePath.startsWith( "/" ) ) {
                pageUrl = baseUrl + pagePath;
            } else {
                pageUrl = baseUrl + "/" + pagePath;
            }

            out.println( "  <url>" );
            out.println( "    <loc>" + escapeXml( pageUrl ) + "</loc>" );

            if ( page.getLastModified() != null ) {
                out.println( "    <lastmod>" + dateFormat.format( page.getLastModified() ) + "</lastmod>" );
            }

            out.println( "    <changefreq>" + determineChangeFreq( page ) + "</changefreq>" );
            out.println( "    <priority>" + determinePriority( page ) + "</priority>" );
            out.println( "  </url>" );
        }

        out.println( "</urlset>" );

        LOG.debug( "Sitemap generated with {} pages", publicPages.size() );
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
     * Determines the change frequency for a page based on its edit history.
     *
     * @param page the page to check
     * @return change frequency string (daily, weekly, monthly, yearly)
     */
    private String determineChangeFreq( final Page page ) {
        final PageManager pm = m_engine.getManager( PageManager.class );
        final List<Page> history = pm.getVersionHistory( page.getName() );

        if ( history == null || history.size() <= 1 ) {
            return "monthly";
        }

        if ( page.getLastModified() == null ) {
            return "monthly";
        }

        // Calculate days since last edit
        final long daysSinceLastEdit = ChronoUnit.DAYS.between(
            page.getLastModified().toInstant(),
            Instant.now()
        );

        if ( daysSinceLastEdit < 1 ) {
            return "daily";
        }
        if ( daysSinceLastEdit < 7 ) {
            return "weekly";
        }
        if ( daysSinceLastEdit < 30 ) {
            return "monthly";
        }
        return "yearly";
    }

    /**
     * Determines the priority for a page based on its importance.
     *
     * @param page the page to check
     * @return priority string (0.0 to 1.0)
     */
    private String determinePriority( final Page page ) {
        final String pageName = page.getName();

        // Main page gets highest priority
        if ( pageName.equals( m_engine.getFrontPage() ) ) {
            return "1.0";
        }

        // Consider number of incoming links
        final ReferenceManager refManager = m_engine.getManager( ReferenceManager.class );
        final Collection<String> referrers = refManager.findReferrers( pageName );

        if ( referrers != null && referrers.size() > 10 ) {
            return "0.8";
        } else if ( referrers != null && referrers.size() > 5 ) {
            return "0.6";
        }

        return "0.5"; // Default priority
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
