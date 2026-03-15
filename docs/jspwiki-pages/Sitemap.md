---
type: article
tags:
- uncategorized
summary: Implementing a Sitemap.xml Servlet for JSPWiki
---
1. Implementing a Sitemap.xml Servlet for JSPWiki

  1. Overview

A sitemap.xml tells search engines about the pages on your wiki, helping them crawl more efficiently. This document provides a detailed breakdown of what needs to be implemented for Google Search Console compliance.

---

  1. Google Sitemap Requirements

Google Search Console expects sitemaps to follow the [Sitemap Protocol](https://www.sitemaps.org/protocol.html):

```xml
<?xml version="1.0" encoding="UTF-8"?>
<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">
  <url>
    <loc>https://wiki.example.com/wiki/Main</loc>
    <lastmod>2025-11-22</lastmod>
    <changefreq>weekly</changefreq>
    <priority>1.0</priority>
  </url>
  <!-- more URLs -->
</urlset>
```

  - Key requirements:**
- Maximum 50,000 URLs per sitemap file
- Maximum 50MB uncompressed size
- UTF-8 encoding
- Proper XML namespace declaration
- URLs must be absolute and properly escaped

---

  1. Implementation Plan

    1. Phase 1: Core Implementation (MVP)

      1. Step 1: Create the Sitemap Servlet

  - Location:** `jspwiki-main/src/main/java/org/apache/wiki/ui/SitemapServlet.java`

```java
package org.apache.wiki.ui;

import java.io.IOException;
import java.io.PrintWriter;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Collection;
import java.util.List;
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
import org.apache.wiki.api.core.Session;
import org.apache.wiki.api.spi.Wiki;
import org.apache.wiki.auth.AuthorizationManager;
import org.apache.wiki.auth.permissions.PagePermission;
import org.apache.wiki.pages.PageManager;
import org.apache.wiki.references.ReferenceManager;
import org.apache.wiki.url.URLConstructor;

public class SitemapServlet extends HttpServlet {

    private static final Logger LOG = LogManager.getLogger( SitemapServlet.class );
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
        // Generate sitemap XML
    }
}
```

      1. Step 2: Implement Core Sitemap Generation Logic

        1. 2.1 Get All Pages

```java
PageManager pageManager = m_engine.getManager( PageManager.class );
Collection<Page> allPages = pageManager.getAllPages();
```

        1. 2.2 Filter Pages Based on Permissions

Only include pages that are publicly accessible (anonymous users can view):

```java
AuthorizationManager authManager = m_engine.getManager( AuthorizationManager.class );
Session session = Wiki.session().find( m_engine, req );

List<Page> publicPages = allPages.stream()
    .filter( page -> {
        try {
            PagePermission permission = new PagePermission( page, PagePermission.VIEW_ACTION );
            // For sitemap, we want pages viewable by anonymous users
            // Create a context to check permissions
            Context context = Wiki.context().create( m_engine, req, ContextEnum.PAGE_VIEW.getRequestContext() );
            context.setPage( page );
            return authManager.checkPermission( context.getWikiSession(), permission );
        } catch ( Exception e ) {
            return false;
        }
    } )
    .collect( Collectors.toList() );
```

        1. 2.3 Generate URLs for Each Page

Use the URLConstructor to generate proper URLs based on the wiki's configuration:

```java
URLConstructor urlConstructor = m_engine.getManager( URLConstructor.class );
String baseUrl = m_engine.getBaseURL();

for ( Page page : publicPages ) {
    // Use URLConstructor to generate URLs in the configured format
    String pageUrl = baseUrl + urlConstructor.makeURL(
        ContextEnum.PAGE_VIEW.getRequestContext(),
        page.getName(),
        null
    );
    // Remove any leading slash duplication
    pageUrl = pageUrl.replace( baseUrl + "/" + baseUrl, baseUrl );

    Date lastModified = page.getLastModified();
    // Write to XML
}
```

        1. 2.4 Write XML Output

```java
resp.setContentType( "application/xml" );
resp.setCharacterEncoding( "UTF-8" );

PrintWriter out = resp.getWriter();
out.println( "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" );
out.println( "<urlset xmlns=\"http://www.sitemaps.org/schemas/sitemap/0.9\">" );

SimpleDateFormat dateFormat = new SimpleDateFormat( "yyyy-MM-dd" );

for ( Page page : publicPages ) {
    String pageUrl = m_engine.getManager( URLConstructor.class )
        .makeURL( ContextEnum.PAGE_VIEW.getRequestContext(), page.getName(), null );

    // Make URL absolute
    if ( !pageUrl.startsWith( "http" ) ) {
        pageUrl = m_engine.getBaseURL() + pageUrl;
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
```

      1. Step 3: Implement Helper Methods

        1. 3.1 Determine Change Frequency

Based on page edit history:

```java
private String determineChangeFreq( final Page page ) {
    PageManager pm = m_engine.getManager( PageManager.class );
    List<Page> history = pm.getVersionHistory( page.getName() );

    if ( history == null || history.size() <= 1 ) {
        return "monthly";
    }

    // Calculate days since last edit
    long daysSinceLastEdit = java.time.temporal.ChronoUnit.DAYS.between(
        page.getLastModified().toInstant(), java.time.Instant.now() );

    if ( daysSinceLastEdit < 1 ) return "daily";
    if ( daysSinceLastEdit < 7 ) return "weekly";
    if ( daysSinceLastEdit < 30 ) return "monthly";
    return "yearly";
}
```

        1. 3.2 Determine Priority

Based on page importance:

```java
private String determinePriority( final Page page ) {
    String pageName = page.getName();

    // Main page gets highest priority
    if ( pageName.equals( m_engine.getFrontPage() ) ) {
        return "1.0";
    }

    // Consider number of incoming links
    ReferenceManager refManager = m_engine.getManager( ReferenceManager.class );
    Collection<String> referrers = refManager.findReferrers( pageName );

    if ( referrers != null && referrers.size() > 10 ) {
        return "0.8";
    } else if ( referrers != null && referrers.size() > 5 ) {
        return "0.6";
    }

    return "0.5"; // Default priority
}
```

        1. 3.3 XML Escaping

```java
private String escapeXml( final String input ) {
    return input
        .replace( "&", "&amp;" )
        .replace( "<", "&lt;" )
        .replace( ">", "&gt;" )
        .replace( "\"", "&quot;" )
        .replace( "'", "&apos;" );
}
```

      1. Step 4: Register the Servlet

  - Location:** `jspwiki-war/src/main/webapp/WEB-INF/web.xml`

```xml
<servlet>
    <servlet-name>SitemapServlet</servlet-name>
    <servlet-class>org.apache.wiki.ui.SitemapServlet</servlet-class>
</servlet>

<servlet-mapping>
    <servlet-name>SitemapServlet</servlet-name>
    <url-pattern>/sitemap.xml</url-pattern>
</servlet-mapping>
```

      1. Step 5: Write Unit Tests

  - Location:** `jspwiki-main/src/test/java/org/apache/wiki/ui/SitemapServletTest.java`

Create tests for:
- XML validity
- URL encoding
- Permission filtering
- Change frequency calculation
- Priority calculation

---

    1. Phase 2: Enhanced Features

      1. Step 6: Implement Caching

For performance, cache the generated sitemap using JSPWiki's CachingManager:

```java
private static final String CACHE_SITEMAP = "jspwiki.sitemapCache";
private static final String CACHE_KEY = "sitemap";

@Override
protected void doGet( final HttpServletRequest req, final HttpServletResponse resp )
        throws ServletException, IOException {

    CachingManager cachingManager = m_engine.getManager( CachingManager.class );

    String sitemap = cachingManager.get( CACHE_SITEMAP, CACHE_KEY, () -> generateSitemapContent( req ) );

    resp.setContentType( "application/xml" );
    resp.setCharacterEncoding( "UTF-8" );
    resp.getWriter().write( sitemap );
}

private String generateSitemapContent( final HttpServletRequest req ) {
    // Generate sitemap XML as a String
    // ...
}
```

  - Note:** You may need to register the cache name in the caching configuration.

      1. Step 7: Add Configuration Properties

  - Location:** Add to `jspwiki-main/src/main/resources/ini/jspwiki.properties`

```properties
1. Sitemap configuration
jspwiki.sitemap.enabled = true
jspwiki.sitemap.includeAttachments = false
jspwiki.sitemap.cacheTimeout = 3600
jspwiki.sitemap.excludePatterns = Admin*,Test*
jspwiki.sitemap.defaultChangeFreq = weekly
jspwiki.sitemap.defaultPriority = 0.5
```

Read configuration in the servlet:

```java
private boolean isEnabled() {
    return TextUtil.getBooleanProperty(
        m_engine.getWikiProperties(),
        "jspwiki.sitemap.enabled",
        true
    );
}
```

---

    1. Phase 3: Large Wiki Support

      1. Step 8: Handle Large Wikis with Sitemap Index

For wikis with more than 50,000 pages, implement a sitemap index:

        1. 8.1 Sitemap Index Format

```xml
<?xml version="1.0" encoding="UTF-8"?>
<sitemapindex xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">
  <sitemap>
    <loc>https://wiki.example.com/sitemap/1</loc>
    <lastmod>2025-11-22</lastmod>
  </sitemap>
  <sitemap>
    <loc>https://wiki.example.com/sitemap/2</loc>
    <lastmod>2025-11-22</lastmod>
  </sitemap>
</sitemapindex>
```

        1. 8.2 URL Routing

Add additional servlet mapping in web.xml:

```xml
<servlet-mapping>
    <servlet-name>SitemapServlet</servlet-name>
    <url-pattern>/sitemap/*</url-pattern>
</servlet-mapping>
```

Implementation:

```java
private static final int MAX_URLS_PER_SITEMAP = 50000;

@Override
protected void doGet( final HttpServletRequest req, final HttpServletResponse resp )
        throws ServletException, IOException {

    String pathInfo = req.getPathInfo(); // e.g., "/1" for sitemap chunk 1

    PageManager pageManager = m_engine.getManager( PageManager.class );
    int totalPages = pageManager.getTotalPageCount();

    if ( pathInfo == null || pathInfo.equals( "/" ) ) {
        // Check if we need sitemap index
        if ( totalPages > MAX_URLS_PER_SITEMAP ) {
            generateSitemapIndex( resp, totalPages );
        } else {
            generateSitemap( resp, req, 0, totalPages );
        }
    } else {
        // Generate specific sitemap chunk
        int chunk = Integer.parseInt( pathInfo.substring( 1 ) );
        generateSitemap( resp, req, chunk * MAX_URLS_PER_SITEMAP, MAX_URLS_PER_SITEMAP );
    }
}
```

---

    1. Phase 4: Optional Features

      1. Step 9: Include Attachments (Optional)

To index attachments:

```java
AttachmentManager attachmentManager = m_engine.getManager( AttachmentManager.class );

for ( Page page : publicPages ) {
    // Add page URL...

    // Add attachment URLs
    if ( includeAttachments ) {
        Collection<Attachment> attachments = attachmentManager.listAttachments( page );
        for ( Attachment att : attachments ) {
            String attUrl = m_engine.getManager( URLConstructor.class )
                .makeURL( ContextEnum.PAGE_ATTACH.getRequestContext(), att.getName(), null );
            // Write attachment URL to sitemap
        }
    }
}
```

      1. Step 10: Add robots.txt Reference

  - Location:** `jspwiki-war/src/main/webapp/robots.txt`

```
User-agent: *
Allow: /

Sitemap: https://wiki.example.com/sitemap.xml
```

  - Note:** The sitemap URL should be configurable or dynamically generated based on `engine.getBaseURL()`.

---

  1. Summary of Files to Create/Modify

| File | Action | Phase | Description |
|------|--------|-------|-------------|
| `jspwiki-main/.../ui/SitemapServlet.java` | Create | 1 | Main servlet implementation |
| `jspwiki-war/.../WEB-INF/web.xml` | Modify | 1 | Register servlet mapping |
| `jspwiki-main/.../ui/SitemapServletTest.java` | Create | 1 | Unit tests |
| `jspwiki-main/.../ini/jspwiki.properties` | Modify | 2 | Add configuration properties |
| `jspwiki-war/.../robots.txt` | Create/Modify | 4 | Add sitemap reference |

---

  1. Additional Considerations

1. **URL Format**: The implementation uses `URLConstructor` to generate URLs in the configured format (e.g., `/wiki/PageName` for ShortViewURLConstructor)

2. **HTTP Headers**: Add `Last-Modified` and `ETag` headers for efficient caching by search engines

3. **Compression**: Support gzip compression for large sitemaps (`sitemap.xml.gz`)

4. **Internationalization**: If your wiki has multiple language versions, consider using `hreflang` annotations

5. **News Sitemaps**: If your wiki has time-sensitive content, consider implementing Google News sitemap extensions

---

  1. API Reference

    1. Key Classes and Methods

- **Engine access**: `Wiki.engine().find( config )`
- **Session access**: `Wiki.session().find( engine, request )`
- **Context creation**: `Wiki.context().create( engine, request, contextEnum )`
- **URL generation**: `engine.getManager( URLConstructor.class ).makeURL( context, name, params )`
- **Permission checking**: `engine.getManager( AuthorizationManager.class ).checkPermission( session, permission )`
- **Caching**: `engine.getManager( CachingManager.class ).get( cacheName, key, supplier )`
- **Page management**: `engine.getManager( PageManager.class ).getAllPages()`
- **References**: `engine.getManager( ReferenceManager.class ).findReferrers( pageName )`

    1. Import Requirements

Use Jakarta EE (not javax):
```java
import jakarta.servlet.ServletConfig;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
```
