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
package org.apache.wiki.tags;

import org.apache.commons.lang3.StringUtils;
import org.apache.wiki.api.exceptions.ProviderException;

import java.io.IOException;

/**
 *  Includes body, if the request context matches.  To understand more about
 *  RequestContexts, please look at the WikiContext class.
 *
 *  @since 2.0
 *  @see org.apache.wiki.WikiContext
 */
public class CheckRequestContextTag extends WikiTagBase {

    private static final long serialVersionUID = 0L;
    
    private String context;
    private String[] contextList = {};

    /**
     *  {@inheritDoc}
     */
    @Override
    public void initTag() {
        super.initTag();
        context = null;
        contextList = new String[0];
    }
    
    /**
     *  Returns the context.
     *  
     *  @return Return the context.
     */
    public String getContext()
    {
        return context;
    }

    /**
     *  Set the context to check for.
     *  
     *  @param arg One of the RequestsContexts.
     */
    public void setContext( final String arg ) {
        context = arg;
        contextList = StringUtils.split( arg,'|' );
    }

    /**
     *  {@inheritDoc}
     */
    @Override
    public final int doWikiStartTag() throws IOException, ProviderException {
        for ( final String checkedCtx : contextList ) {
            final String ctx = wikiContext.getRequestContext();
            if ( !checkedCtx.isEmpty() ) {
                if ( checkedCtx.charAt( 0 ) == '!' ) {
                    if ( !ctx.equalsIgnoreCase( checkedCtx.substring( 1 ) ) ) {
                        return EVAL_BODY_INCLUDE;
                    }
                } else if ( ctx.equalsIgnoreCase( checkedCtx ) ) {
                    return EVAL_BODY_INCLUDE;
                }
            }
        }
        return SKIP_BODY;
    }

}
