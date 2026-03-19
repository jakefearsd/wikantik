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

import com.wikantik.InternalWikiException;
import com.wikantik.api.core.ContextEnum;
import com.wikantik.api.core.Page;

import jakarta.servlet.jsp.JspWriter;
import java.io.IOException;


/**
 *  Writes a comment link.  Body of the link becomes the link text.
 *  <P><B>Attributes</B></P>
 *  <UL>
 *    <LI>page - Page name to refer to.  Default is the current page.
 *    <LI>format - Format, either "anchor" or "url".
 *  </UL>
 *
 *  @since 2.0
 */
public class CommentLinkTag
    extends WikiLinkTag
{
    private static final long serialVersionUID = 0L;
    
    /**
     *  {@inheritDoc}
     */
    @Override
    public final int doWikiStartTag() throws IOException {
        final Page page;
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

        //  Finally, print out the correct link, according to what user commanded.
        final JspWriter out = pageContext.getOut();
        switch( format ) {
        case ANCHOR: out.print( "<a href=\"" + getCommentURL( pageNameToUse ) + "\">" ); break;
        case URL: out.print( getCommentURL( pageNameToUse ) ); break;
        default: throw new InternalWikiException( "Impossible format " + format );
        }

        return EVAL_BODY_INCLUDE;
    }

    private String getCommentURL( final String pageNameParam ) {
        return wikiContext.getURL( ContextEnum.PAGE_COMMENT.getRequestContext(), pageNameParam );
    }

}
