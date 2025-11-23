# Implementing a Sitemap.xml Servlet for JSPWiki

## Overview

A sitemap.xml tells search engines about the pages on your wiki, helping them crawl more efficiently. This document provides a detailed breakdown of what needs to be implemented for Google Search Console compliance.

---

## Step 1: Understand Google Sitemap Requirements

Google Search Console expects sitemaps to follow the [Sitemap Protocol](https://www.sitemaps.org/protocol.html):

```xml
<?xml version="1.0" encoding="UTF-8"?>
<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">
  <url>
    <loc>https://wiki.example.com/Wiki.jsp?page=Main</loc>
    <lastmod>2025-11-22</lastmod>
    <changefreq>weekly</changefreq>
    <priority>1.0</priority>
  </url>
  <!-- more URLs -->
</urlset>
```

**Key requirements:**
- Maximum 50,000 URLs per sitemap file
- Maximum 50MB uncompressed size
- UTF-8 encoding
- Proper XML namespace declaration
- URLs must be absolute and properly escaped

---

## Step 2: Create the Sitemap Servlet

**Location:** `jspwiki-main/src/main/java/org/apache/wiki/ui/SitemapServlet.java`

### Class Structure

```java
package org.apache.wiki.ui;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.wiki.api.core.Engine;
import org.apache.wiki.api.core.Page;
import org.apache.wiki.api.spi.Wiki;
import org.apache.wiki.pages.PageManager;

public class SitemapServlet extends HttpServlet {

    private Engine m_engine;

    @Override
    public void init(ServletConfig config) throws ServletException {
        super.init(config);
        ServletContext context = config.getServletContext();
        m_engine = Wiki.engine().find(context, null);
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp)
            throws ServletException, IOException {
        // Generate sitemap XML
    }
}
```

---

## Step 3: Implement the Core Sitemap Generation Logic

### 3.1 Get All Pages

```java
PageManager pageManager = m_engine.getManager(PageManager.class);
Collection<Page> allPages = pageManager.getAllPages();
```

### 3.2 Filter Pages Based on Permissions

Only include pages that are publicly accessible (anonymous users can view):

```java
AuthorizationManager authManager = m_engine.getManager(AuthorizationManager.class);
Session guestSession = Wiki.session().guest(m_engine);

List<Page> publicPages = allPages.stream()
    .filter(page -> {
        try {
            return authManager.checkPermission(guestSession,
                new PagePermission(page, PagePermission.VIEW_ACTION));
        } catch (Exception e) {
            return false;
        }
    })
    .collect(Collectors.toList());
```

### 3.3 Generate URLs for Each Page

```java
String baseUrl = m_engine.getBaseURL(); // e.g., "https://wiki.example.com/JSPWiki/"

for (Page page : publicPages) {
    String pageUrl = baseUrl + "Wiki.jsp?page=" +
        URLEncoder.encode(page.getName(), StandardCharsets.UTF_8);
    Date lastModified = page.getLastModified();
    // Write to XML
}
```

### 3.4 Write XML Output

```java
resp.setContentType("application/xml");
resp.setCharacterEncoding("UTF-8");

PrintWriter out = resp.getWriter();
out.println("<?xml version=\"1.0\" encoding=\"UTF-8\"?>");
out.println("<urlset xmlns=\"http://www.sitemaps.org/schemas/sitemap/0.9\">");

SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd");

for (Page page : publicPages) {
    out.println("  <url>");
    out.println("    <loc>" + escapeXml(pageUrl) + "</loc>");

    if (page.getLastModified() != null) {
        out.println("    <lastmod>" + dateFormat.format(page.getLastModified()) + "</lastmod>");
    }

    out.println("    <changefreq>" + determineChangeFreq(page) + "</changefreq>");
    out.println("    <priority>" + determinePriority(page) + "</priority>");
    out.println("  </url>");
}

out.println("</urlset>");
```

---

## Step 4: Implement Helper Methods

### 4.1 Determine Change Frequency

Based on page edit history:

```java
private String determineChangeFreq(Page page) {
    // Get page history to determine how often it changes
    PageManager pm = m_engine.getManager(PageManager.class);
    List<Page> history = pm.getVersionHistory(page.getName());

    if (history == null || history.size() <= 1) {
        return "monthly";
    }

    // Calculate average time between edits
    // Return: always, hourly, daily, weekly, monthly, yearly, never
    long daysSinceLastEdit = ChronoUnit.DAYS.between(
        page.getLastModified().toInstant(), Instant.now());

    if (daysSinceLastEdit < 1) return "daily";
    if (daysSinceLastEdit < 7) return "weekly";
    if (daysSinceLastEdit < 30) return "monthly";
    return "yearly";
}
```

### 4.2 Determine Priority

Based on page importance:

```java
private String determinePriority(Page page) {
    String pageName = page.getName();

    // Main page gets highest priority
    if (pageName.equals(m_engine.getFrontPage())) {
        return "1.0";
    }

    // Could also consider:
    // - Number of incoming links (from ReferenceManager)
    // - Page size
    // - Recent activity

    ReferenceManager refManager = m_engine.getManager(ReferenceManager.class);
    Collection<String> referrers = refManager.findReferrers(pageName);

    if (referrers != null && referrers.size() > 10) {
        return "0.8";
    } else if (referrers != null && referrers.size() > 5) {
        return "0.6";
    }

    return "0.5"; // Default priority
}
```

### 4.3 XML Escaping

```java
private String escapeXml(String input) {
    return input
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;");
}
```

---

## Step 5: Handle Large Wikis with Sitemap Index

For wikis with more than 50,000 pages, implement a sitemap index:

### 5.1 Sitemap Index Format

```xml
<?xml version="1.0" encoding="UTF-8"?>
<sitemapindex xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">
  <sitemap>
    <loc>https://wiki.example.com/sitemap1.xml</loc>
    <lastmod>2025-11-22</lastmod>
  </sitemap>
  <sitemap>
    <loc>https://wiki.example.com/sitemap2.xml</loc>
    <lastmod>2025-11-22</lastmod>
  </sitemap>
</sitemapindex>
```

### 5.2 URL Routing

```java
@Override
protected void doGet(HttpServletRequest req, HttpServletResponse resp) {
    String pathInfo = req.getPathInfo(); // e.g., "/1" for sitemap1.xml

    if (pathInfo == null || pathInfo.equals("/")) {
        // Check if we need sitemap index
        int totalPages = pageManager.getTotalPageCount();
        if (totalPages > MAX_URLS_PER_SITEMAP) {
            generateSitemapIndex(resp, totalPages);
        } else {
            generateSitemap(resp, 0, totalPages);
        }
    } else {
        // Generate specific sitemap chunk
        int chunk = Integer.parseInt(pathInfo.substring(1));
        generateSitemap(resp, chunk * MAX_URLS_PER_SITEMAP, MAX_URLS_PER_SITEMAP);
    }
}
```

---

## Step 6: Register the Servlet

### 6.1 Update web.xml

**Location:** `jspwiki-war/src/main/webapp/WEB-INF/web.xml`

```xml
<servlet>
    <servlet-name>SitemapServlet</servlet-name>
    <servlet-class>org.apache.wiki.ui.SitemapServlet</servlet-class>
</servlet>

<servlet-mapping>
    <servlet-name>SitemapServlet</servlet-name>
    <url-pattern>/sitemap.xml</url-pattern>
</servlet-mapping>

<!-- For sitemap index support -->
<servlet-mapping>
    <servlet-name>SitemapServlet</servlet-name>
    <url-pattern>/sitemap/*</url-pattern>
</servlet-mapping>
```

---

## Step 7: Add Configuration Properties

**Location:** Add to `jspwiki.properties`

```properties
# Sitemap configuration
jspwiki.sitemap.enabled = true
jspwiki.sitemap.includeAttachments = false
jspwiki.sitemap.cacheTimeout = 3600
jspwiki.sitemap.excludePatterns = Admin*,Test*
jspwiki.sitemap.defaultChangeFreq = weekly
jspwiki.sitemap.defaultPriority = 0.5
```

---

## Step 8: Implement Caching

For performance, cache the generated sitemap:

```java
private String cachedSitemap;
private long cacheTimestamp;
private static final long CACHE_DURATION = 3600000; // 1 hour

@Override
protected void doGet(HttpServletRequest req, HttpServletResponse resp) {
    long now = System.currentTimeMillis();

    if (cachedSitemap == null || (now - cacheTimestamp) > CACHE_DURATION) {
        cachedSitemap = generateSitemapContent();
        cacheTimestamp = now;
    }

    resp.setContentType("application/xml");
    resp.setCharacterEncoding("UTF-8");
    resp.getWriter().write(cachedSitemap);
}
```

**Better approach:** Use `CachingManager`:

```java
CachingManager cache = m_engine.getManager(CachingManager.class);
String sitemap = cache.get("sitemap", "main", () -> generateSitemapContent());
```

---

## Step 9: Include Attachments (Optional)

To index attachments:

```java
AttachmentManager attachmentManager = m_engine.getManager(AttachmentManager.class);

for (Page page : publicPages) {
    // Add page URL...

    // Add attachment URLs
    if (includeAttachments) {
        Collection<Attachment> attachments = attachmentManager.listAttachments(page);
        for (Attachment att : attachments) {
            String attUrl = baseUrl + "attach/" +
                URLEncoder.encode(att.getName(), StandardCharsets.UTF_8);
            // Write attachment URL to sitemap
        }
    }
}
```

---

## Step 10: Add robots.txt Reference

Update or create `robots.txt` to point to the sitemap:

**Location:** `jspwiki-war/src/main/webapp/robots.txt`

```
User-agent: *
Allow: /

Sitemap: https://wiki.example.com/JSPWiki/sitemap.xml
```

---

## Step 11: Testing and Validation

### 11.1 Unit Tests

Create tests in `jspwiki-main/src/test/java/org/apache/wiki/ui/SitemapServletTest.java`:

- Test XML validity
- Test URL encoding
- Test permission filtering
- Test pagination for large wikis
- Test caching behavior

### 11.2 Validation Tools

- Use Google Search Console's sitemap testing tool
- Validate XML with online validators
- Check URL accessibility

---

## Summary of Files to Create/Modify

| File | Action | Description |
|------|--------|-------------|
| `jspwiki-main/.../ui/SitemapServlet.java` | Create | Main servlet implementation |
| `jspwiki-war/.../WEB-INF/web.xml` | Modify | Register servlet mapping |
| `jspwiki-main/.../ini/jspwiki.properties` | Modify | Add configuration properties |
| `jspwiki-war/.../robots.txt` | Create/Modify | Add sitemap reference |
| `jspwiki-main/.../ui/SitemapServletTest.java` | Create | Unit tests |

---

## Additional Considerations

1. **URL Format**: Consider using "pretty URLs" (`/wiki/PageName`) instead of query parameters if your wiki supports them - Google prefers cleaner URLs

2. **HTTP Headers**: Add `Last-Modified` and `ETag` headers for efficient caching by search engines

3. **Compression**: Support gzip compression for large sitemaps (`sitemap.xml.gz`)

4. **Internationalization**: If your wiki has multiple language versions, consider using `hreflang` annotations

5. **News Sitemaps**: If your wiki has time-sensitive content, consider implementing Google News sitemap extensions
