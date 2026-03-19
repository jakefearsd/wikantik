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

import com.wikantik.api.core.Engine;
import com.wikantik.api.core.Page;
import com.wikantik.api.exceptions.ProviderException;
import com.wikantik.pages.PageManager;

import java.io.IOException;

/**
 *  Includes the body in case there is no such page available.
 *
 *  @since 2.0
 */
public class NoSuchPageTag extends WikiTagBase {

    private static final long serialVersionUID = 0L;
    
    private String pageName;

    @Override
    public void initTag() {
        super.initTag();
        pageName = null;
    }

    public void setPage( final String name )
    {
        pageName = name;
    }

    public String getPage()
    {
        return pageName;
    }

    @Override
    public int doWikiStartTag() throws IOException, ProviderException {
        final Engine engine = wikiContext.getEngine();
        final Page page;

        if( pageName == null ) {
            page = wikiContext.getPage();
        } else {
            page = engine.getManager( PageManager.class ).getPage( pageName );
        }

        if( page != null && engine.getManager( PageManager.class ).wikiPageExists( page.getName(), page.getVersion() ) ) {
            return SKIP_BODY;
        }

        return EVAL_BODY_INCLUDE;
    }
}
