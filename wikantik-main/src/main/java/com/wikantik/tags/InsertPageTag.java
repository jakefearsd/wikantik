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

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import com.wikantik.api.core.Engine;
import com.wikantik.api.core.Page;
import com.wikantik.api.exceptions.ProviderException;
import com.wikantik.pages.PageManager;
import com.wikantik.render.RenderingManager;

import jakarta.servlet.jsp.JspWriter;
import java.io.IOException;

/**
 *  Renders WikiPage content.  For InsertPage tag and the InsertPage plugin
 *  the difference is that the tag will always render in the context of the page
 *  which is referenced (i.e. a LeftMenu inserted on a JSP page with the InsertPage tag
 *  will always render in the context of the actual URL, e.g. Main.), whereas
 *  the InsertPage plugin always renders in local context.  This allows this like
 *  ReferringPagesPlugin to really refer to the Main page instead of having to
 *  resort to any trickery.
 *  <p>
 *  This tag sets the "realPage" field of the WikiContext to point at the inserted
 *  page, while the "page" will contain the actual page in which the rendering
 *  is being made.
 *   
 *  <P><B>Attributes</B></P>
 *  <UL>
 *    <LI>page - Page name to refer to.  Default is the current page.
 *    <li>mode - In which format to insert the page.  Can be either "plain" or "html".
 *  </UL>
 *
 *  @since 2.0
 */
public class InsertPageTag extends WikiTagBase {

    private static final long serialVersionUID = 0L;
    private static final Logger LOG = LogManager.getLogger( InsertPageTag.class );
    
    public static final int HTML  = 0;
    public static final int PLAIN = 1;

    protected String pageName;
    private   int    mode = HTML;

    @Override
    public void initTag() {
        super.initTag();
        pageName = null;
        mode = HTML;
    }

    public void setPage( final String page )
    {
        pageName = page;
    }

    public String getPage()
    {
        return pageName;
    }

    public void setMode( final String arg ) {
        if( "plain".equals( arg ) ) {
            mode = PLAIN;
        } else {
            mode = HTML;
        }
    }

    @Override
    public final int doWikiStartTag() throws IOException, ProviderException {
        final Engine engine = wikiContext.getEngine();
        final Page insertedPage;

        //
        //  NB: The page might not really exist if the user is currently
        //      creating it (i.e. it is not yet in the cache or providers), 
        //      AND we got the page from the wikiContext.
        //

        if( pageName == null ) {
            insertedPage = wikiContext.getPage();
            if( !engine.getManager( PageManager.class ).wikiPageExists(insertedPage) ) return SKIP_BODY;
        } else {
            insertedPage = engine.getManager( PageManager.class ).getPage( pageName );
        }

        if( insertedPage != null ) {
            // FIXME: Do version setting later.
            // page.setVersion( WikiProvider.LATEST_VERSION );

            LOG.debug("Inserting page "+insertedPage);

            final JspWriter out = pageContext.getOut();
            final Page oldPage = wikiContext.setRealPage( insertedPage );
            
            switch( mode ) {
              case HTML: out.print( engine.getManager( RenderingManager.class ).getHTML( wikiContext, insertedPage ) ); break;
              case PLAIN: out.print( engine.getManager( PageManager.class ).getText( insertedPage ) ); break;
            }
            
            wikiContext.setRealPage( oldPage );
        }

        return SKIP_BODY;
    }

}
