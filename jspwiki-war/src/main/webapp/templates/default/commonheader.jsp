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

<%@ taglib uri="http://jspwiki.apache.org/tags" prefix="wiki" %>
<%@ page import="org.apache.wiki.api.core.*" %>
<%@ page import="org.apache.wiki.ui.*" %>
<%@ page import="org.apache.wiki.util.*" %>
<%@ page import="org.apache.wiki.preferences.Preferences" %>
<%@ page import="java.util.*" %>
<%@ taglib prefix="c" uri="jakarta.tags.core" %>
<%@ taglib prefix="fmt" uri="jakarta.tags.fmt" %>
<%@ taglib prefix="fn" uri="jakarta.tags.functions" %>
<fmt:setLocale value="${prefs.Language}" />
<fmt:setBundle basename="templates.default"/>
<%--
   This file provides a common header which includes the important JSPWiki scripts and other files.
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
  <c:set var="wikiPageName"><wiki:Variable var="pagename" /></c:set>
  <c:set var="wikiAuthor"><wiki:Author format='plain'/></c:set>
  <c:set var="wikiPageVersion"><wiki:PageVersion /></c:set>
  <c:set var="wikiLastModified"><wiki:PageDate format='yyyy-MM-dd'/></c:set>
  <c:set var="wikiAppName"><wiki:Variable var="ApplicationName" /></c:set>
  <c:set var="wikiBaseUrl"><wiki:BaseURL /></c:set>
  <%-- Build canonical URL: baseURL ends with / (e.g., http://host/JSPWiki/), append wiki/PageName --%>
  <c:set var="canonicalUrl">${wikiBaseUrl}wiki/${wikiPageName}</c:set>

  <%-- Canonical URL to prevent duplicate content issues --%>
  <link rel="canonical" href="${canonicalUrl}" />

  <meta name="author" content="${wikiAuthor}">
  <%-- Improved meta description with actual content --%>
  <c:set var="pageDescription"><wiki:Variable var='description' default='' /></c:set>
  <c:choose>
    <c:when test="${!empty pageDescription}">
      <meta name="description" content="${pageDescription}" />
    </c:when>
    <c:when test="${!empty wikiPageVersion}">
      <meta name="description" content="${wikiPageName} - ${wikiAppName} wiki page. Last modified by ${wikiAuthor}." />
    </c:when>
    <c:otherwise>
      <meta name="description" content="${wikiPageName} - ${wikiAppName} wiki page." />
    </c:otherwise>
  </c:choose>

  <c:set var="keywords"><wiki:Variable var='keywords' default='' /></c:set>
  <c:if test="${!empty keywords}">
    <meta name="keywords" content="${keywords}" />
  </c:if>

  <%-- Open Graph meta tags for social sharing --%>
  <meta property="og:title" content="${wikiPageName} - ${wikiAppName}" />
  <meta property="og:type" content="article" />
  <meta property="og:url" content="${canonicalUrl}" />
  <c:choose>
    <c:when test="${!empty pageDescription}">
      <meta property="og:description" content="${pageDescription}" />
    </c:when>
    <c:otherwise>
      <meta property="og:description" content="${wikiPageName} - A page on ${wikiAppName}." />
    </c:otherwise>
  </c:choose>
  <meta property="og:site_name" content="${wikiAppName}" />
  <c:if test="${!empty wikiLastModified}">
    <meta property="article:modified_time" content="${wikiLastModified}" />
  </c:if>
  <meta property="article:author" content="${wikiAuthor}" />

  <%-- Twitter Card meta tags --%>
  <meta name="twitter:card" content="summary" />
  <meta name="twitter:title" content="${wikiPageName} - ${wikiAppName}" />
  <c:choose>
    <c:when test="${!empty pageDescription}">
      <meta name="twitter:description" content="${pageDescription}" />
    </c:when>
    <c:otherwise>
      <meta name="twitter:description" content="${wikiPageName} - A page on ${wikiAppName}." />
    </c:otherwise>
  </c:choose>

  <%-- JSON-LD Structured Data for SEO --%>
  <script type="application/ld+json">
  {
    "@context": "https://schema.org",
    "@type": "Article",
    "headline": "${wikiPageName}",
    "author": {
      "@type": "Person",
      "name": "${wikiAuthor}"
    },
    <c:if test="${!empty wikiLastModified}">
    "dateModified": "${wikiLastModified}",
    </c:if>
    "publisher": {
      "@type": "Organization",
      "name": "${wikiAppName}"
    },
    "mainEntityOfPage": {
      "@type": "WebPage",
      "@id": "${canonicalUrl}"
    }
  }
  </script>
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
<c:set var="frontpage"><wiki:Variable var="jspwiki.frontPage" /></c:set>
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