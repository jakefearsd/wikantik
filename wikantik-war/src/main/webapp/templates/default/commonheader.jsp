<%--
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
--%>

<%@ taglib uri="https://wikantik.com/tags" prefix="wiki" %>
<%@ page import="com.wikantik.api.core.*" %>
<%@ page import="com.wikantik.ui.*" %>
<%@ page import="com.wikantik.util.*" %>
<%@ page import="com.wikantik.preferences.Preferences" %>
<%@ page import="java.util.*" %>
<%@ taglib prefix="c" uri="jakarta.tags.core" %>
<%@ taglib prefix="fmt" uri="jakarta.tags.fmt" %>
<%@ taglib prefix="fn" uri="jakarta.tags.functions" %>
<fmt:setLocale value="${prefs.Language}" />
<fmt:setBundle basename="templates.default"/>
<%--
   This file provides a common header which includes the important Wikantik scripts and other files.
   You need to include this in your template, within <head> and </head>.  It is recommended that
   you don't change this file in your own template, as it is likely to change quite a lot between
   revisions.

   Any new functionality, scripts, etc, should be included using the TemplateManager resource
   include scheme (look below at the <wiki:IncludeResources> tags to see what kind of things
   can be included).
--%>
<%-- CSS stylesheet --%>
<%--
BOOTSTRAP, IE compatibility / http://getbootstrap.com/getting-started/#support-ie-compatibility-modes
--%>
<meta charset="<wiki:ContentEncoding />">
<meta http-equiv="x-ua-compatible" content="ie=edge" />
<meta name="viewport" content="width=device-width, initial-scale=1">
<%--
<meta http-equiv="Content-Security-Policy" content="upgrade-insecure-requests">
--%>

<%-- SEO Meta Tags --%>
<wiki:PageExists>
  <%-- Preload frontmatter metadata into page attributes so it is available for meta tags --%>
  <% com.wikantik.frontmatter.FrontmatterPreloader.preloadMetadata(
         (com.wikantik.WikiContext) pageContext.getAttribute("jspwiki.context",
         PageContext.REQUEST_SCOPE)); %>

  <c:set var="wikiPageName"><wiki:Variable var="pagename" /></c:set>
  <c:set var="wikiAuthor"><wiki:Author format='plain'/></c:set>
  <c:set var="wikiPageVersion"><wiki:PageVersion /></c:set>
  <c:set var="wikiLastModified"><wiki:PageDate format='yyyy-MM-dd'/></c:set>
  <c:set var="wikiAppName"><wiki:Variable var="ApplicationName" /></c:set>
  <c:set var="wikiBaseUrl"><wiki:BaseURL /></c:set>
  <%-- Ensure wikiBaseUrl is fully qualified (scheme://host:port/context/).
       WikiEngine.getBaseURL() returns only the context path when no explicit
       base URL is configured. Build a full URL from the request in that case,
       matching the pattern used by SitemapServlet and AtomFeedServlet. --%>
  <%
  {
      String _base = (String) pageContext.getAttribute("wikiBaseUrl");
      if (_base == null || !_base.startsWith("http")) {
          String _s = request.getScheme();
          String _h = request.getServerName();
          int _p = request.getServerPort();
          String _c = request.getContextPath();
          StringBuilder _sb = new StringBuilder(_s).append("://").append(_h);
          if (("http".equals(_s) && _p != 80) || ("https".equals(_s) && _p != 443)) {
              _sb.append(':').append(_p);
          }
          if (_c != null) _sb.append(_c);
          _base = _sb.toString();
      }
      if (!_base.endsWith("/")) _base += "/";
      pageContext.setAttribute("wikiBaseUrl", _base);
  }
  %>
  <%-- Build canonical URL: baseURL ends with / (e.g., http://host/Wikantik/), append wiki/PageName --%>
  <c:set var="canonicalUrl">${wikiBaseUrl}wiki/${wikiPageName}</c:set>

  <%-- Read frontmatter fields into JSTL variables --%>
  <c:set var="pageSummary"><wiki:Variable var='summary' default='' /></c:set>
  <c:set var="pageDescription"><wiki:Variable var='description' default='' /></c:set>
  <c:set var="pageTags"><wiki:Variable var='tags' default='' /></c:set>
  <c:set var="pageType"><wiki:Variable var='type' default='' /></c:set>
  <c:set var="pageCluster"><wiki:Variable var='cluster' default='' /></c:set>
  <c:set var="pageDate"><wiki:Variable var='date' default='' /></c:set>
  <c:set var="pageRelated"><wiki:Variable var='related' default='' /></c:set>

  <%-- Compute effective description: frontmatter summary > description > generic fallback --%>
  <c:set var="effectiveDescription" value="${!empty pageSummary ? pageSummary : pageDescription}" />
  <%-- Compute effective keywords: frontmatter tags > legacy keywords variable --%>
  <c:set var="legacyKeywords"><wiki:Variable var='keywords' default='' /></c:set>
  <c:set var="effectiveKeywords" value="${!empty pageTags ? pageTags : legacyKeywords}" />

  <%-- Canonical URL to prevent duplicate content issues --%>
  <link rel="canonical" href="${canonicalUrl}" />

  <meta name="author" content="${wikiAuthor}">
  <%-- Meta description from frontmatter summary, description, or generic fallback --%>
  <c:choose>
    <c:when test="${!empty effectiveDescription}">
      <meta name="description" content="${fn:escapeXml(effectiveDescription)}" />
    </c:when>
    <c:when test="${!empty wikiPageVersion}">
      <meta name="description" content="${wikiPageName} - ${wikiAppName} wiki page. Last modified by ${wikiAuthor}." />
    </c:when>
    <c:otherwise>
      <meta name="description" content="${wikiPageName} - ${wikiAppName} wiki page." />
    </c:otherwise>
  </c:choose>

  <c:if test="${!empty effectiveKeywords}">
    <meta name="keywords" content="${fn:escapeXml(effectiveKeywords)}" />
  </c:if>

  <%-- Open Graph meta tags for social sharing --%>
  <meta property="og:title" content="${fn:escapeXml(wikiPageName)} - ${fn:escapeXml(wikiAppName)}" />
  <meta property="og:type" content="article" />
  <meta property="og:url" content="${canonicalUrl}" />
  <c:choose>
    <c:when test="${!empty effectiveDescription}">
      <meta property="og:description" content="${fn:escapeXml(effectiveDescription)}" />
    </c:when>
    <c:otherwise>
      <meta property="og:description" content="${wikiPageName} - A page on ${wikiAppName}." />
    </c:otherwise>
  </c:choose>
  <meta property="og:site_name" content="${fn:escapeXml(wikiAppName)}" />
  <c:if test="${!empty wikiLastModified}">
    <meta property="article:modified_time" content="${wikiLastModified}" />
  </c:if>
  <meta property="article:author" content="${fn:escapeXml(wikiAuthor)}" />

  <%-- og:image from attached preview image (og-image.png or og-image.jpg) --%>
  <%
  try {
      final com.wikantik.api.core.Engine _engine =
              ( com.wikantik.api.core.Engine )
                application.getAttribute( "com.wikantik.WikiEngine" );
      if ( _engine != null ) {
          final com.wikantik.attachment.AttachmentManager attMgr =
                  _engine.getManager( com.wikantik.attachment.AttachmentManager.class );
          if ( attMgr != null && attMgr.attachmentsEnabled() ) {
              final com.wikantik.api.core.Page currentPage =
                      ( ( com.wikantik.WikiContext ) pageContext.getAttribute( "jspwiki.context",
                      PageContext.REQUEST_SCOPE ) ).getPage();
              String ogImageName = null;
              for ( final String candidate : new String[]{ "og-image.png", "og-image.jpg", "og-image.webp" } ) {
                  try {
                      if ( attMgr.getAttachmentInfo( currentPage.getName() + "/" + candidate ) != null ) {
                          ogImageName = candidate;
                          break;
                      }
                  } catch ( final Exception ignore ) { }
              }
              if ( ogImageName != null ) {
                  pageContext.setAttribute( "ogImageUrl",
                          pageContext.getAttribute( "wikiBaseUrl" ) + "attach/"
                          + currentPage.getName() + "/" + ogImageName );
              }
          }
      }
  } catch ( final Exception _ogEx ) { /* og:image is optional — never crash the page */ }
  %>
  <c:if test="${!empty ogImageUrl}">
  <meta property="og:image" content="${fn:escapeXml(ogImageUrl)}" />
  <meta name="twitter:image" content="${fn:escapeXml(ogImageUrl)}" />
  </c:if>

  <%-- Emit one article:tag per frontmatter tag --%>
  <c:if test="${!empty pageTags}">
    <c:forEach var="tag" items="${fn:split(pageTags, ',')}">
    <meta property="article:tag" content="${fn:escapeXml(fn:trim(tag))}" />
    </c:forEach>
  </c:if>

  <%-- Twitter Card meta tags --%>
  <meta name="twitter:card" content="summary" />
  <meta name="twitter:title" content="${fn:escapeXml(wikiPageName)} - ${fn:escapeXml(wikiAppName)}" />
  <c:choose>
    <c:when test="${!empty effectiveDescription}">
      <meta name="twitter:description" content="${fn:escapeXml(effectiveDescription)}" />
    </c:when>
    <c:otherwise>
      <meta name="twitter:description" content="${wikiPageName} - A page on ${wikiAppName}." />
    </c:otherwise>
  </c:choose>

  <%-- Atom feed autodiscovery --%>
  <link rel="alternate" type="application/atom+xml"
        title="${fn:escapeXml(wikiAppName)} - Recent Articles"
        href="${wikiBaseUrl}feed.xml" />
  <c:if test="${!empty pageCluster}">
  <link rel="alternate" type="application/atom+xml"
        title="${fn:escapeXml(wikiAppName)} - ${fn:escapeXml(pageCluster)} Articles"
        href="${wikiBaseUrl}feed.xml?cluster=${pageCluster}" />
  </c:if>

  <%-- JSON-LD Structured Data for SEO --%>
  <script type="application/ld+json">
  {
    "@context": "https://schema.org",
    <c:choose>
      <c:when test="${pageType == 'hub'}">
    "@type": "CollectionPage",
    "name": "${fn:escapeXml(wikiPageName)}",
      <c:if test="${!empty pageRelated}">
    "hasPart": [
        <c:set var="relatedItems" value="${fn:split(pageRelated, ',')}" />
        <c:forEach var="relItem" items="${relatedItems}" varStatus="relStatus">
      { "@type": "Article", "name": "${fn:escapeXml(fn:trim(relItem))}", "url": "${wikiBaseUrl}wiki/${fn:trim(relItem)}" }<c:if test="${!relStatus.last}">,</c:if>
        </c:forEach>
    ],
      </c:if>
      </c:when>
      <c:otherwise>
    "@type": "Article",
      </c:otherwise>
    </c:choose>
    "headline": "${fn:escapeXml(wikiPageName)}",
    <c:if test="${!empty effectiveDescription}">
    "description": "${fn:escapeXml(effectiveDescription)}",
    </c:if>
    <c:if test="${!empty effectiveKeywords}">
    "keywords": "${fn:escapeXml(effectiveKeywords)}",
    </c:if>
    "author": {
      "@type": "Person",
      "name": "${fn:escapeXml(wikiAuthor)}"
    },
    <c:if test="${!empty wikiLastModified}">
    "dateModified": "${wikiLastModified}",
    </c:if>
    <c:if test="${!empty pageDate}">
    "datePublished": "${pageDate}",
    </c:if>
    <c:if test="${!empty pageCluster}">
    "articleSection": "${fn:escapeXml(pageCluster)}",
    </c:if>
    "publisher": {
      "@type": "Organization",
      "name": "${fn:escapeXml(wikiAppName)}"
    },
    "mainEntityOfPage": {
      "@type": "WebPage",
      "@id": "${canonicalUrl}"
    }<c:if test="${pageType != 'hub' && !empty pageCluster}">,
    "isPartOf": {
      "@type": "CollectionPage",
      "name": "${fn:escapeXml(pageCluster)}"
    }</c:if><c:if test="${!empty pageRelated && pageType != 'hub'}">,
    "relatedLink": [
      <c:set var="relatedLinks" value="${fn:split(pageRelated, ',')}" />
      <c:forEach var="relLink" items="${relatedLinks}" varStatus="relLinkStatus">
      "${wikiBaseUrl}wiki/${fn:trim(relLink)}"<c:if test="${!relLinkStatus.last}">,</c:if>
      </c:forEach>
    ]</c:if>
  }
  </script>

  <%-- BreadcrumbList JSON-LD for clustered pages --%>
  <c:if test="${!empty pageCluster && pageType != 'hub'}">
  <script type="application/ld+json">
  {
    "@context": "https://schema.org",
    "@type": "BreadcrumbList",
    "itemListElement": [
      { "@type": "ListItem", "position": 1, "name": "Home", "item": "${wikiBaseUrl}" },
      { "@type": "ListItem", "position": 2, "name": "${fn:escapeXml(pageCluster)}", "item": "${wikiBaseUrl}wiki/${fn:escapeXml(pageCluster)}" },
      { "@type": "ListItem", "position": 3, "name": "${fn:escapeXml(wikiPageName)}", "item": "${canonicalUrl}" }
    ]
  }
  </script>
  </c:if>
</wiki:PageExists>


<%-- COOKIE read client preferences --%>
<%
   Preferences.setupPreferences(pageContext);
%>

<%-- Localized JS; must come before any css, to avoid blocking immediate execution --%>
<%-- var LocalizedStrings= { "javascript.<xx>":"...", etc. } --%>
<script type="text/javascript">//<![CDATA[
<wiki:IncludeResources type="jslocalizedstrings"/>
String.I18N = LocalizedStrings;
String.I18N.PREFIX = "javascript.";
//]]></script>

<link rel="stylesheet" type="text/css" media="screen, projection, print" id="main-stylesheet"
     href="<wiki:Link format='url' templatefile='haddock.css'/>" />

<c:if test="${prefs.Appearance }">
<link rel="stylesheet" type="text/css" media="screen, projection, print" id="main-stylesheet"
     href="<wiki:Link format='url' templatefile='haddock-dark.css'/>"/>
</c:if>

<wiki:IncludeResources type="stylesheet"/>
<wiki:IncludeResources type="inlinecss" />

<%-- JAVASCRIPT --%>

<script src="<wiki:Link format='url' jsp='scripts/haddock.js'/>"></script>

<wiki:IncludeResources type="script"/>


<meta name="wikiContext" content='<wiki:Variable var="requestcontext" />' />
<wiki:Permission permission="edit"><meta name="wikiEditPermission" content="true"/></wiki:Permission>
<meta name="wikiBaseUrl" content='<wiki:BaseURL />' />
<meta name="wikiPageUrl" content='<wiki:Link format="url"  page="#$%"/>' />
<meta name="wikiEditUrl" content='<wiki:EditLink format="url" page="#$%"/>' />
<meta name="wikiCloneUrl" content='<wiki:EditLink format="url" page="#$%"/>&clone=<wiki:Variable var="pagename" />' />
<meta name="wikiJsonUrl" content='<%= Context.findContext(pageContext).getURL( ContextEnum.PAGE_NONE.getRequestContext(), "ajax" ) %>' /><%--unusual pagename--%>
<meta name="wikiPageName" content='<wiki:Variable var="pagename" />' /><%--pagename without blanks--%>
<meta name="wikiUserName" content="<wiki:UserName />" />
<meta name="wikiTemplateUrl" content='<wiki:Link format="url" templatefile="" />' />
<meta name="wikiApplicationName" content='<wiki:Variable var="ApplicationName" />' />
<wiki:CsrfProtection format="meta" />
<%--CHECKME
    <wiki:link> seems not to lookup the right jsp from the right template directory
    EG when a templatefile is not present, the generated link should point to the default template.
    Solution for now: manually force the relevant links back to the default template
--%>
<meta name="wikiXHRSearch" content='<wiki:Link format="url" templatefile="../default/AJAXSearch.jsp" />' />
<meta name="wikiXHRPreview" content='<wiki:Link format="url" templatefile="../default/AJAXPreview.jsp" />' />
<meta name="wikiXHRCategories" content='<wiki:Link format="url" templatefile="../default/AJAXCategories.jsp" />' />

<script type="text/javascript">//<![CDATA[
<wiki:IncludeResources type="jsfunction"/>
//]]></script>

<link rel="search" href="<wiki:LinkTo format='url' page='Search'/>"
    title='Search <wiki:Variable var="ApplicationName" />' />
<link rel="help"   href="<wiki:LinkTo format='url' page='TextFormattingRules'/>"
    title="Help" />
<c:set var="frontpage"><wiki:Variable var="wikantik.frontPage" /></c:set>
<link rel="start"  href="<wiki:Link page='${frontpage}' format='url' />" title="Front page" />
<link rel="alternate stylesheet" type="text/css" href="<wiki:Link format='url' templatefile='haddock.css'/>"
    title="Standard" />

<%-- Favicons --%>
<link rel="apple-touch-icon" sizes="180x180" href="<wiki:Link format='url' jsp='favicons/apple-touch-icon.png'/>">
<link rel="icon" type="image/png" sizes="32x32" href="<wiki:Link format='url' jsp='favicons/favicon-32x32.png'/>">
<link rel="icon" type="image/png" sizes="16x16" href="<wiki:Link format='url' jsp='favicons/favicon-16x16.png'/>">
<link rel="manifest" href="<wiki:Link format='url' jsp='favicons/site.webmanifest'/>">
<link rel="mask-icon" href="<wiki:Link format='url' jsp='favicons/safari-pinned-tab.svg'/>" color="#1B3A57">
<link rel="shortcut icon" href="<wiki:Link format='url' jsp='favicons/favicon.ico'/>">
<meta name="msapplication-TileColor" content="#1B3A57">
<meta name="msapplication-config" content="<wiki:Link format='url' jsp='favicons/browserconfig.xml'/>">
<meta name="theme-color" content="#9CAF88">

<%-- Support for the universal edit button (www.universaleditbutton.org) --%>
<wiki:CheckRequestContext context='view|info|diff|upload'>
  <wiki:Permission permission="edit">
    <wiki:PageType type="page">
      <link rel="alternate" type="application/x-wiki"
           href="<wiki:EditLink format='url' />"
          title="<fmt:message key='actions.edit.title'/>" />
    </wiki:PageType>
  </wiki:Permission>
</wiki:CheckRequestContext>


<%-- SKINS : extra stylesheets, extra javascript --%>
<c:if test='${(!empty prefs.SkinName) && (prefs.SkinName!="PlainVanilla") }'>
<link rel="stylesheet" type="text/css" media="screen, projection, print"
     href="<wiki:Link format='url' templatefile='skins/' /><c:out value='${prefs.SkinName}/skin.css' />" />
<script type="text/javascript"
         src="<wiki:Link format='url' templatefile='skins/' /><c:out value='${prefs.SkinName}/skin.js' />" ></script>
</c:if>

<wiki:Include page="localheader.jsp"/>