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
import com.wikantik.api.core.Attachment;
import com.wikantik.api.core.Context;
import com.wikantik.api.core.Engine;
import com.wikantik.api.core.Page;
import com.wikantik.api.exceptions.ProviderException;
import com.wikantik.attachment.AttachmentManager;
import com.wikantik.pages.PageManager;

import jakarta.servlet.jsp.JspWriter;
import jakarta.servlet.jsp.PageContext;
import java.io.IOException;
import java.util.List;


/**
 *  Iterates through the list of attachments one has.
 *
 *  <P><B>Attributes</B></P>
 *  <UL>
 *    <LI>page - Page name to refer to.  Default is the current page.
 *  </UL>
 *
 *  @since 2.0
 */
// FIXME: Too much in common with IteratorTag - REFACTOR
public class AttachmentsIteratorTag extends IteratorTag {
    private static final long serialVersionUID = 0L;
    
    private static final Logger LOG = LogManager.getLogger( AttachmentsIteratorTag.class );

    /**
     *  {@inheritDoc}
     */
    @Override
    public final int doStartTag()  {
        wikiContext = (Context) pageContext.getAttribute( Context.ATTR_CONTEXT, PageContext.REQUEST_SCOPE );
        final Engine engine = wikiContext.getEngine();
        final AttachmentManager mgr = engine.getManager( AttachmentManager.class );
        final Page page;

        page = wikiContext.getPage();

        if( !mgr.attachmentsEnabled() )
        {
            return SKIP_BODY;
        }

        try {
            if( page != null && engine.getManager( PageManager.class ).wikiPageExists(page) ) {
                final List< Attachment > atts = mgr.listAttachments( page );

                if( atts == null ) {
                    LOG.debug("No attachments to display.");
                    // There are no attachments included
                    return SKIP_BODY;
                }

                iterator = atts.iterator();

                if( iterator.hasNext() ) {
                    final Attachment  att = (Attachment) iterator.next();
                    final Context context = wikiContext.clone();
                    context.setPage( att );
                    pageContext.setAttribute( Context.ATTR_CONTEXT, context, PageContext.REQUEST_SCOPE );
                    pageContext.setAttribute( getId(), att );
                } else {
                    return SKIP_BODY;
                }
            } else {
                return SKIP_BODY;
            }

            return EVAL_BODY_BUFFERED;
        } catch( final ProviderException e ) {
            LOG.fatal("Provider failed while trying to iterator through history",e);
            // FIXME: THrow something.
        }

        return SKIP_BODY;
    }

    /**
     *  {@inheritDoc}
     */
    @Override
    public final int doAfterBody() {
        if( bodyContent != null ) {
            try {
                final JspWriter out = getPreviousOut();
                out.print(bodyContent.getString());
                bodyContent.clearBody();
            } catch( final IOException e ) {
                LOG.error("Unable to get inner tag text", e);
                // FIXME: throw something?
            }
        }

        if( iterator != null && iterator.hasNext() ) {
            final Attachment att = ( Attachment )iterator.next();
            final Context context = wikiContext.clone();
            context.setPage( att );
            pageContext.setAttribute( Context.ATTR_CONTEXT,  context, PageContext.REQUEST_SCOPE );
            pageContext.setAttribute( getId(), att );

            return EVAL_BODY_BUFFERED;
        }

        return SKIP_BODY;
    }

}
