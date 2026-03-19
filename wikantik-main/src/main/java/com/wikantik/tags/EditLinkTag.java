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
package com.wikantik.tags;

import com.wikantik.api.core.ContextEnum;
import com.wikantik.api.core.Engine;
import com.wikantik.api.core.Page;
import com.wikantik.pages.PageManager;

import jakarta.servlet.jsp.JspWriter;
import java.io.IOException;

/**
 *  Writes an edit link.  Body of the link becomes the link text.
 *  <P><B>Attributes</B></P>
 *  <UL>
 *    <LI>page - Page name to refer to.  Default is the current page.
 *    <LI>format - Format, either "anchor" or "url".
 *    <LI>version - Version number of the page to refer to.  Possible values
 *        are "this", meaning the version of the current page; or a version
 *        number.  Default is always to point at the latest version of the page.
 *    <LI>title - Is used in page actions to display hover text (tooltip)
 *    <LI>accesskey - Set an accesskey (ALT+[Char])
 *  </UL>
 *
 *  @since 2.0
 */
public class EditLinkTag extends WikiLinkTag {

    private static final long serialVersionUID = 0L;
    
    public String version;
    public String title = "";
    public String accesskey = "";
    
    @Override
    public void initTag() {
        super.initTag();
        version = null;
    }

    public void setVersion( final String vers )
    {
        version = vers;
    }
    
    public void setTitle( final String title )
    {
        this.title = title;
    }

    public void setAccesskey( final String access )
    {
        accesskey = access;
    }

    @Override
    public final int doWikiStartTag() throws IOException {
        final Engine engine   = wikiContext.getEngine();
        Page page = null;
        String versionString = "";
        final String pageNameToUse;

        //  Determine the page and the link.
        if( pageName == null ) {
            page = wikiContext.getPage();
            if( page == null ) {
                // You can't call this on the page itself anyways.
                return SKIP_BODY;
            }

            pageNameToUse = page.getName();
        } else {
            pageNameToUse = pageName;
        }

        //
        //  Determine the latest version, if the version attribute is "this".
        //
        if( version != null ) {
            if( "this".equalsIgnoreCase( version ) ) {
                if( page == null ) {
                    // No page, so go fetch according to page name.
                    page = engine.getManager( PageManager.class ).getPage( pageNameToUse );
                }

                if( page != null ) {
                    versionString = "version=" + page.getVersion();
                }
            } else {
                versionString = "version=" + version;
            }
        }

        //
        //  Finally, print out the correct link, according to what user commanded.
        //
        final JspWriter out = pageContext.getOut();
        switch( format ) {
          case ANCHOR:
            out.print( "<a href=\"" + wikiContext.getURL( ContextEnum.PAGE_EDIT.getRequestContext(), pageNameToUse, versionString ) +
                       "\" accesskey=\"" + accesskey + "\" title=\"" + title + "\">" );
            break;
          case URL:
            out.print( wikiContext.getURL( ContextEnum.PAGE_EDIT.getRequestContext(), pageNameToUse, versionString ) );
            break;
        }

        return EVAL_BODY_INCLUDE;
    }

}
