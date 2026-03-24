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
import com.wikantik.api.core.Context;
import com.wikantik.api.core.Engine;
import com.wikantik.diff.DifferenceManager;
import com.wikantik.pages.PageManager;

import jakarta.servlet.jsp.JspWriter;
import jakarta.servlet.jsp.PageContext;
import java.io.IOException;

/**
 *  Writes difference between two pages using a HTML table.  If there is
 *  no difference, includes the body.
 *
 *  <P><B>Attributes</B></P>
 *  <UL>
 *    <LI>page - Page name to refer to.  Default is the current page.
 *  </UL>
 *
 *  @since 2.0
 */
public class InsertDiffTag extends WikiTagBase {

    private static final long serialVersionUID = 0L;
    private static final Logger LOG = LogManager.getLogger( InsertDiffTag.class );
    
    /** Attribute which is used to store the old page content to the Page Context */
    public static final String ATTR_OLDVERSION = "olddiff";

    /** Attribute which is used to store the new page content to the Page Context */
    public static final String ATTR_NEWVERSION = "newdiff";

    protected String pageName;

    /** {@inheritDoc} */
    @Override public void initTag() {
        super.initTag();
        pageName = null;
    }

    /**
     *  Sets the page name.
     *  @param page Page to get diff from.
     */
    public void setPage( final String page )
    {
        pageName = page;
    }

    /**
     *  Gets the page name.
     * @return The page name.
     */
    public String getPage()
    {
        return pageName;
    }

    /** {@inheritDoc} */
    @Override
    public final int doWikiStartTag() throws IOException {
        final Engine engine = wikiContext.getEngine();
        final Context ctx;
        
        if( pageName == null ) {
            ctx = wikiContext;
        } else {
            ctx = wikiContext.clone();
            ctx.setPage( engine.getManager( PageManager.class ).getPage(pageName) );
        }

        final Integer vernew = ( Integer )pageContext.getAttribute( ATTR_NEWVERSION, PageContext.REQUEST_SCOPE );
        final Integer verold = ( Integer )pageContext.getAttribute( ATTR_OLDVERSION, PageContext.REQUEST_SCOPE );

        LOG.debug("Request diff between version {} and {}", verold, vernew);

        if( ctx.getPage() != null ) {
            final JspWriter out = pageContext.getOut();
            final String diff = engine.getManager( DifferenceManager.class ).getDiff( ctx, vernew, verold);

            if( diff.isEmpty() ) {
                return EVAL_BODY_INCLUDE;
            }

            out.write( diff );
        }

        return SKIP_BODY;
    }

}

