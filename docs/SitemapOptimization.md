# Sitemap.xml Optimization Research: The Ideal Output for Google Search

## Executive Summary

This document explores what values beyond the basic URL help Google understand and index content, and what the "best possible outcome" sitemap looks like for optimal search visibility.

**Key Finding:** Google ignores `<changefreq>` and `<priority>` values. The only universally valuable optional field is `<lastmod>`, and only if it's **accurate**. However, **sitemap extensions** (image, video, hreflang) provide significant value for rich content and international sites.

---

## Part 1: What Google Actually Uses vs. Ignores

### 1.1 Fields Google Processes

| Field | Status | Notes |
|-------|--------|-------|
| `<loc>` | **Required** | The canonical URL - must be accurate and accessible |
| `<lastmod>` | **Conditional** | Used IF consistently accurate; ignored if manipulated |

### 1.2 Fields Google Ignores

| Field | Status | Notes |
|-------|--------|-------|
| `<changefreq>` | **Ignored** | Google has publicly confirmed they ignore this |
| `<priority>` | **Ignored** | Google has publicly confirmed they ignore this |

**Source:** [Google Search Central Documentation](https://developers.google.com/search/docs/crawling-indexing/sitemaps/build-sitemap) states:
> "Google ignores `<priority>` and `<changefreq>` values."

### 1.3 Why `<lastmod>` Matters (When Accurate)

Google's documentation states:
> "The `<lastmod>` value should reflect the date and time of the last **significant** update to the page. For example, an update to the main content, the structured data, or links on the page is generally considered significant, however an update to the copyright date is not."

**Critical Warning:** Google will stop trusting `<lastmod>` if:
- Dates are artificially inflated to appear "fresh"
- Dates don't match actual content changes
- Dates are updated without meaningful content changes

**Bing also uses `<lastmod>`** as a key freshness signal for both traditional SEO and AI-based content evaluation.

---

## Part 2: Sitemap Extensions That Add Real Value

While the basic sitemap fields are limited, **extensions** provide substantial benefits for rich content discovery.

### 2.1 Image Sitemap Extension

**Purpose:** Help Google discover images that might not be found through normal crawling (e.g., JavaScript-loaded images, images in carousels).

**Namespace:**
```xml
xmlns:image="http://www.google.com/schemas/sitemap-image/1.1"
```

**Example:**
```xml
<url>
  <loc>https://example.com/wiki/MainPage</loc>
  <lastmod>2024-11-28</lastmod>
  <image:image>
    <image:loc>https://example.com/attach/MainPage/diagram.png</image:loc>
  </image:image>
  <image:image>
    <image:loc>https://example.com/attach/MainPage/photo.jpg</image:loc>
  </image:image>
</url>
```

**Benefits:**
- Images appear in Google Images search results
- Better indexing of dynamically loaded images
- Up to 1,000 images per URL entry

**Deprecated Tags:** Google no longer uses `<image:caption>`, `<image:geo_location>`, `<image:title>`, or `<image:license>`. Only `<image:loc>` is processed.

**Source:** [Google Image Sitemaps Documentation](https://developers.google.com/search/docs/crawling-indexing/sitemaps/image-sitemaps)

### 2.2 Video Sitemap Extension

**Purpose:** Enable rich video search results and Video tab appearances.

**Namespace:**
```xml
xmlns:video="http://www.google.com/schemas/sitemap-video/1.1"
```

**Required Tags:**
```xml
<url>
  <loc>https://example.com/wiki/TutorialPage</loc>
  <video:video>
    <video:thumbnail_loc>https://example.com/thumbnails/tutorial.jpg</video:thumbnail_loc>
    <video:title>How to Use the Wiki</video:title>
    <video:description>A comprehensive tutorial on wiki editing.</video:description>
    <video:content_loc>https://example.com/videos/tutorial.mp4</video:content_loc>
    <!-- OR -->
    <video:player_loc>https://youtube.com/embed/xxxxx</video:player_loc>
  </video:video>
</url>
```

**Optional But Valuable Tags:**
```xml
<video:duration>600</video:duration>              <!-- Seconds -->
<video:publication_date>2024-11-01</video:publication_date>
<video:family_friendly>yes</video:family_friendly>
<video:live>no</video:live>
```

**Benefits:**
- Video thumbnails in search results
- Eligibility for Video carousel
- Enhanced click-through rates

**Source:** [XML Sitemaps Generator - Video Sitemap](https://www.xml-sitemaps.com/video-sitemap.html)

### 2.3 News Sitemap Extension

**Purpose:** Rapid indexing for news content (articles published within last 48 hours).

**Namespace:**
```xml
xmlns:news="http://www.google.com/schemas/sitemap-news/0.9"
```

**Example:**
```xml
<url>
  <loc>https://example.com/wiki/BreakingNews</loc>
  <news:news>
    <news:publication>
      <news:name>Example Wiki News</news:name>
      <news:language>en</news:language>
    </news:publication>
    <news:publication_date>2024-11-28T14:30:00+00:00</news:publication_date>
    <news:title>Major Update Announcement</news:title>
  </news:news>
</url>
```

**Requirements:**
- Only include articles from the **last 2 days**
- Remove older articles or strip `<news:news>` metadata
- Maximum 1,000 URLs per news sitemap
- Consider registering with [Google Publisher Center](https://support.google.com/news/publisher-center/answer/9606709)

**Benefits:**
- Faster indexing (within minutes for approved publishers)
- Eligibility for Google News and Top Stories
- Higher visibility for timely content

**Source:** [Google News Sitemap Documentation](https://developers.google.com/search/docs/crawling-indexing/sitemaps/news-sitemap)

### 2.4 Hreflang for International/Multilingual Content

**Purpose:** Tell Google about language and regional variations of pages to serve the right version to users.

**Namespace:**
```xml
xmlns:xhtml="http://www.w3.org/1999/xhtml"
```

**Example:**
```xml
<url>
  <loc>https://example.com/wiki/MainPage</loc>
  <xhtml:link rel="alternate" hreflang="en" href="https://example.com/wiki/MainPage"/>
  <xhtml:link rel="alternate" hreflang="de" href="https://example.com/wiki/de/MainPage"/>
  <xhtml:link rel="alternate" hreflang="fr" href="https://example.com/wiki/fr/MainPage"/>
  <xhtml:link rel="alternate" hreflang="x-default" href="https://example.com/wiki/MainPage"/>
</url>
```

**Critical Requirements:**
- **Bidirectional:** Every page must link to all alternates AND itself
- **Self-referential:** Include the current page in the list
- **x-default:** Designates the fallback for unmatched languages

**Language Code Format:**
- Language only: `en`, `de`, `fr`
- Language + region: `en-US`, `en-GB`, `de-AT`
- Uses ISO 639-1 (language) + ISO 3166-1 Alpha 2 (region)

**Benefits:**
- Correct regional content served to users
- Consolidated ranking signals across language versions
- Reduced duplicate content issues

**Source:** [Google Localized Versions Documentation](https://developers.google.com/search/docs/specialty/international/localized-versions)

---

## Part 3: The Ideal Sitemap Structure

### 3.1 Complete Example with All Extensions

```xml
<?xml version="1.0" encoding="UTF-8"?>
<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9"
        xmlns:image="http://www.google.com/schemas/sitemap-image/1.1"
        xmlns:video="http://www.google.com/schemas/sitemap-video/1.1"
        xmlns:xhtml="http://www.w3.org/1999/xhtml">

  <!-- Main page with images and language alternates -->
  <url>
    <loc>https://wiki.example.com/MainPage</loc>
    <lastmod>2024-11-28</lastmod>

    <!-- Language alternates -->
    <xhtml:link rel="alternate" hreflang="en" href="https://wiki.example.com/MainPage"/>
    <xhtml:link rel="alternate" hreflang="de" href="https://wiki.example.com/de/MainPage"/>
    <xhtml:link rel="alternate" hreflang="x-default" href="https://wiki.example.com/MainPage"/>

    <!-- Images on this page -->
    <image:image>
      <image:loc>https://wiki.example.com/attach/MainPage/welcome-banner.png</image:loc>
    </image:image>
  </url>

  <!-- Tutorial page with embedded video -->
  <url>
    <loc>https://wiki.example.com/GettingStarted</loc>
    <lastmod>2024-11-15</lastmod>

    <video:video>
      <video:thumbnail_loc>https://wiki.example.com/thumbnails/getting-started.jpg</video:thumbnail_loc>
      <video:title>Getting Started with Our Wiki</video:title>
      <video:description>Learn the basics of navigating and editing wiki pages.</video:description>
      <video:player_loc>https://www.youtube.com/embed/abc123</video:player_loc>
      <video:duration>480</video:duration>
      <video:publication_date>2024-11-15</video:publication_date>
    </video:video>

    <image:image>
      <image:loc>https://wiki.example.com/attach/GettingStarted/screenshot1.png</image:loc>
    </image:image>
    <image:image>
      <image:loc>https://wiki.example.com/attach/GettingStarted/screenshot2.png</image:loc>
    </image:image>
  </url>

  <!-- Regular content page -->
  <url>
    <loc>https://wiki.example.com/Documentation</loc>
    <lastmod>2024-10-20</lastmod>
  </url>

</urlset>
```

### 3.2 What NOT to Include

**Do NOT include in sitemap:**
- URLs that return non-200 status codes
- URLs blocked by robots.txt
- URLs with `noindex` meta tags
- Non-canonical URLs (include only the canonical version)
- Login-required pages (unless you want them indexed)
- Admin/system pages
- Duplicate content

**For Wikantik specifically, exclude:**
- LeftMenu, RightMenu, TitleBox, PageHeader, PageFooter
- CSS* pages
- Login.jsp, UserPreferences.jsp (administrative)
- Old page versions (only include current/canonical)

---

## Part 4: Technical Best Practices

### 4.1 File Size and URL Limits

| Limit | Value |
|-------|-------|
| Maximum URLs per sitemap | 50,000 |
| Maximum uncompressed size | 50 MB |
| Recommended for performance | < 10,000 URLs |

**For larger sites:** Use a sitemap index file:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<sitemapindex xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">
  <sitemap>
    <loc>https://wiki.example.com/sitemap-pages.xml</loc>
    <lastmod>2024-11-28</lastmod>
  </sitemap>
  <sitemap>
    <loc>https://wiki.example.com/sitemap-attachments.xml</loc>
    <lastmod>2024-11-25</lastmod>
  </sitemap>
</sitemapindex>
```

### 4.2 Compression

Use gzip compression for large sitemaps:
- Filename: `sitemap.xml.gz`
- Reduces bandwidth and speeds up crawling
- Google automatically decompresses

### 4.3 Date Formats

Use ISO 8601 format:
- Date only: `2024-11-28`
- With time: `2024-11-28T14:30:00+00:00`
- UTC: `2024-11-28T14:30:00Z`

### 4.4 URL Canonicalization

Ensure consistency:
- Choose www vs non-www and stick to it
- Choose trailing slash or not and stick to it
- Use HTTPS (not HTTP)
- Use lowercase for consistency

```xml
<!-- GOOD -->
<loc>https://wiki.example.com/Documentation</loc>

<!-- BAD - inconsistent -->
<loc>http://www.wiki.example.com/Documentation/</loc>
```

### 4.5 Submission Methods

1. **Google Search Console** (recommended)
   - Submit sitemap URL
   - Monitor indexing status
   - See errors and warnings

2. **robots.txt reference**
   ```
   Sitemap: https://wiki.example.com/sitemap.xml
   ```

3. **Ping URL** (deprecated but still works)
   ```
   https://www.google.com/ping?sitemap=https://wiki.example.com/sitemap.xml
   ```

---

## Part 5: Current Wikantik Implementation Analysis

### 5.1 Current State

The existing `SitemapServlet.java` (wikantik-main/src/main/java/org/apache/wiki/ui/SitemapServlet.java) generates:

```xml
<url>
  <loc>...</loc>
  <lastmod>...</lastmod>
  <changefreq>...</changefreq>  <!-- Google ignores -->
  <priority>...</priority>       <!-- Google ignores -->
</url>
```

**What it does well:**
- Excludes menu/template pages (LeftMenu, TitleBox, etc.)
- Checks page permissions (only public pages)
- Uses accurate `lastmod` from page metadata
- Proper XML escaping

**Opportunities for improvement:**
- Remove `<changefreq>` and `<priority>` (wasted bytes)
- Add image extension for attachments
- Add hreflang for multi-language wiki pages
- Consider sitemap index for large wikis

### 5.2 Recommended Enhancements

**Priority 1: Remove Ignored Fields**
```java
// Remove these lines (Google ignores them)
// out.println( "    <changefreq>" + determineChangeFreq( page ) + "</changefreq>" );
// out.println( "    <priority>" + determinePriority( page ) + "</priority>" );
```

**Priority 2: Add Image Extension**
```java
// Add namespace
out.println( "<urlset xmlns=\"http://www.sitemaps.org/schemas/sitemap/0.9\"" );
out.println( "        xmlns:image=\"http://www.google.com/schemas/sitemap-image/1.1\">" );

// For each page, include attachments
AttachmentManager attMgr = m_engine.getManager( AttachmentManager.class );
List<Attachment> attachments = attMgr.listAttachments( page );
for ( Attachment att : attachments ) {
    if ( isImageAttachment( att ) ) {
        out.println( "    <image:image>" );
        out.println( "      <image:loc>" + escapeXml( attachmentUrl ) + "</image:loc>" );
        out.println( "    </image:image>" );
    }
}
```

**Priority 3: Add Hreflang for Internationalization**
```java
// If wiki has language variants (e.g., /de/, /fr/ prefixes)
out.println( "    <xhtml:link rel=\"alternate\" hreflang=\"en\" " +
             "href=\"" + escapeXml( englishUrl ) + "\"/>" );
```

---

## Part 6: Measuring Success

### 6.1 Google Search Console Metrics

After implementing an optimized sitemap, monitor:

| Metric | Goal |
|--------|------|
| **Indexed pages** | Should match or approach submitted URLs |
| **Coverage errors** | Should decrease over time |
| **Crawl stats** | Efficient crawling, no wasted requests |
| **Rich results** | Increased if using image/video extensions |

### 6.2 Expected Outcomes

**With basic optimized sitemap:**
- Faster discovery of new/updated pages
- More accurate `lastmod` signals improve crawl efficiency
- Reduced crawl budget waste (no non-indexable URLs)

**With image extension:**
- Improved Google Images visibility
- Better image indexing for wiki attachments

**With video extension:**
- Video thumbnails in search results
- Eligibility for video carousels

**With hreflang:**
- Correct language versions served to international users
- Consolidated ranking power across translations

---

## Part 7: Summary - The Ideal Sitemap

### What to Include

```xml
<?xml version="1.0" encoding="UTF-8"?>
<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9"
        xmlns:image="http://www.google.com/schemas/sitemap-image/1.1"
        xmlns:xhtml="http://www.w3.org/1999/xhtml">

  <url>
    <loc>{canonical URL}</loc>
    <lastmod>{accurate ISO 8601 date}</lastmod>

    <!-- If page has images -->
    <image:image>
      <image:loc>{image URL}</image:loc>
    </image:image>

    <!-- If page has language variants -->
    <xhtml:link rel="alternate" hreflang="{lang}" href="{URL}"/>

  </url>

</urlset>
```

### What NOT to Include

- `<changefreq>` - Google ignores it
- `<priority>` - Google ignores it
- Non-canonical URLs
- URLs returning non-200 status
- URLs blocked by robots.txt
- Pages with noindex directives

### The Bottom Line

**The ideal sitemap is:**
1. **Accurate** - Only valid, canonical URLs with correct lastmod dates
2. **Complete** - All indexable public content included
3. **Rich** - Image/video extensions where applicable
4. **International** - Hreflang for multi-language content
5. **Lean** - No wasted fields that search engines ignore

---

## Sources

- [Google Search Central - Build and Submit a Sitemap](https://developers.google.com/search/docs/crawling-indexing/sitemaps/build-sitemap)
- [Google Image Sitemaps Documentation](https://developers.google.com/search/docs/crawling-indexing/sitemaps/image-sitemaps)
- [Google News Sitemap Documentation](https://developers.google.com/search/docs/crawling-indexing/sitemaps/news-sitemap)
- [Google Localized Versions (hreflang)](https://developers.google.com/search/docs/specialty/international/localized-versions)
- [Google Publisher Center - News Sitemap](https://support.google.com/news/publisher-center/answer/9606709)
- [Sitemaps.org Protocol](https://www.sitemaps.org/protocol.html)
- [XML Sitemaps Generator - Video Sitemap](https://www.xml-sitemaps.com/video-sitemap.html)
- [Sitemap Extensions Overview](https://dynomapper.com/blog/22-sitemaps/204-what-are-sitemap-extensions-and-how-to-use-them)

---

*Document created: November 2024*
