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
package org.apache.wiki.htmltowiki;

import org.apache.wiki.api.core.Context;
import org.apache.wiki.api.core.ContextEnum;
import org.jdom2.Element;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;


/**
 * Defines a Wiki configuration to XHtmlToWikiTranslator, including things like URLs.
 */
public class XHtmlToWikiConfig {

    private String outlink = "outlink";
    private String pageInfoJsp = "PageInfo.jsp";
    private String wikiJspPage = "Wiki.jsp?page=";
    private String editJspPage = "Edit.jsp?page=";
    private String attachPage = "attach?page=";
    private String pageName;

    /**
     *  Creates a new, empty config object.
     */
    public XHtmlToWikiConfig() {
    }

    /**
     * The constructor initializes the different internal fields according to the current URLConstructor.
     *
     * @param wikiContext A WikiContext
     */
    public XHtmlToWikiConfig( final Context wikiContext ) {
        setWikiContext( wikiContext );

        //  Figure out the actual URLs.
        //  NB: The logic here will fail if you add something else after the Wiki page name in VIEW or ATTACH
        wikiJspPage = wikiContext.getURL( ContextEnum.PAGE_VIEW.getRequestContext(), "" );
        editJspPage = wikiContext.getURL( ContextEnum.PAGE_EDIT.getRequestContext(), "" );
        attachPage = wikiContext.getURL( ContextEnum.PAGE_ATTACH.getRequestContext(), "" );
        pageInfoJsp = wikiContext.getURL( ContextEnum.PAGE_INFO.getRequestContext(), "" );
    }

    private void setWikiContext( final Context wikiContext ) {
        if( wikiContext.getPage() != null ) {
            setPageName( wikiContext.getPage().getName() + '/' );
        }
    }

    /**
     * Return the URL for the attachments.
     *
     * @return URL for attachments.
     */
    public String getAttachPage() {
        return attachPage;
    }

    /**
     * Set the URL for attachments.
     *
     * @param newAttachPage The attachment URL.
     */
    public void setAttachPage( final String newAttachPage ) {
        this.attachPage = newAttachPage;
    }

    /**
     * Gets the URL of the outlink image.
     *
     * @return The URL of the outlink image.
     */
    public String getOutlink() {
        return outlink;
    }

    /**
     * Set the outlink URL.
     *
     * @param newOutlink The outlink URL.
     */
    public void setOutlink( final String newOutlink ) {
        this.outlink = newOutlink;
    }

    /**
     * Get the PageInfo.jsp URI.
     *
     * @return The URI for the page info display.
     */
    public String getPageInfoJsp() {
        return pageInfoJsp;
    }

    /**
     * Set the URI for the page info display.
     *
     * @param newPageInfoJsp URI for the page info.
     */
    public void setPageInfoJsp( final String newPageInfoJsp ) {
        this.pageInfoJsp = newPageInfoJsp;
    }

    /**
     * Get the page name.
     *
     * @return The Page Name.
     */
    public String getPageName() {
        return pageName;
    }

    /**
     * Set the page name.
     *
     * @param newPageName The name of the page.
     */
    public void setPageName( final String newPageName ) {
        this.pageName = newPageName;
    }

    /**
     * Get the URI to the Wiki.jsp view.
     *
     * @return The URI to the Wiki.jsp.
     */
    public String getWikiJspPage() {
        return wikiJspPage;
    }

    /**
     * Set the URI to the Wiki.jsp.
     *
     * @param newWikiJspPage The URI to the Wiki.jsp.
     */
    public void setWikiJspPage( final String newWikiJspPage ) {
        this.wikiJspPage = newWikiJspPage;
    }

    /**
     * Return the URI to the Edit.jsp page.
     *
     * @return The URI to the Edit.jsp page.
     */
    public String getEditJspPage() {
        return editJspPage;
    }

    /**
     * Set the URI to the Edit.jsp page.
     *
     * @param newEditJspPage The Edit.jsp URI.
     */
    public void setEditJspPage( final String newEditJspPage ) {
        this.editJspPage = newEditJspPage;
    }

    public boolean isNotIgnorableWikiMarkupLink( final Element a ) {
        final String ref = a.getAttributeValue( "href" );
        final String clazz = a.getAttributeValue( "class" );
        return ( ref == null || !ref.startsWith( getPageInfoJsp() ) )
                && ( clazz == null || !clazz.trim().equalsIgnoreCase( getOutlink() ) );
    }

    public String trimLink( String ref ) {
        if( ref == null ) {
            return null;
        }
        ref = URLDecoder.decode( ref, StandardCharsets.UTF_8);
        ref = ref.trim();
        if( ref.startsWith( getAttachPage() ) ) {
            ref = ref.substring( getAttachPage().length() );
        }
        if( ref.startsWith( getWikiJspPage() ) ) {
            ref = ref.substring( getWikiJspPage().length() );

            // Handle links with section anchors.
            // For example, we need to translate the html string "TargetPage#section-TargetPage-Heading2"
            // to this wiki string "TargetPage#Heading2".
            ref = ref.replaceFirst( ".+#section-(.+)-(.+)", "$1#$2" );
        }
        if( ref.startsWith( getEditJspPage() ) ) {
            ref = ref.substring( getEditJspPage().length() );
        }
        if( getPageName() != null ) {
            if( ref.startsWith( getPageName() ) ) {
                ref = ref.substring( getPageName().length() );
            }
        }
        return ref;
    }

}
