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
import com.wikantik.api.exceptions.ProviderException;
import com.wikantik.ui.TemplateManager;
import com.wikantik.util.TextUtil;

import jakarta.servlet.ServletException;
import jakarta.servlet.jsp.JspException;
import java.io.IOException;

/**
 *  Includes an another JSP page, making sure that we actually pass the WikiContext correctly.
 *
 *  @since 2.0
 */
// FIXME: Perhaps unnecessary?
public class IncludeTag extends WikiTagBase {

    private static final long serialVersionUID = 0L;
    private static final Logger LOG = LogManager.getLogger( IncludeTag.class );
    
    protected String page;

    @Override
    public void initTag() {
        super.initTag();
        page = null;
    }

    public void setPage( final String page )
    {
        this.page = page;
    }

    public String getPage()
    {
        return page;
    }

    @Override
    public final int doWikiStartTag() throws IOException, ProviderException {
        return SKIP_BODY;
    }

    @Override
    public final int doEndTag() throws JspException {
        try {
            final String jspPage = wikiContext.getEngine().getManager( TemplateManager.class ).findJSP( pageContext,
                                                                                                 wikiContext.getTemplate(),
                                                                                                 page );

            if( jspPage == null ) {
                pageContext.getOut().println( "No template file called '" + TextUtil.replaceEntities( page ) + "'" );
            } else {
                pageContext.include( jspPage );
            }
        } catch( final ServletException e ) {
            LOG.warn( "Including failed, got a servlet exception from sub-page. Rethrowing the exception to the JSP engine.", e );
            throw new JspException( e.getMessage() );
        } catch( final IOException e ) {
            LOG.warn( "I/O exception - probably the connection was broken. Rethrowing the exception to the JSP engine.", e );
            throw new JspException( e.getMessage() );
        }

        return EVAL_PAGE;
    }

}
