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
import com.wikantik.i18n.InternationalizationManager;
import com.wikantik.pages.PageManager;
import com.wikantik.parser.MarkupParser;
import com.wikantik.parser.WikiDocument;
import com.wikantik.preferences.Preferences;
import com.wikantik.render.RenderingManager;
import com.wikantik.util.TextUtil;

import java.io.IOException;

/**
 *  Writes the author name of the current page, including a link to that page, if that page exists.
 *
 *  @since 2.0
 */
public class AuthorTag extends WikiTagBase {
    private static final long serialVersionUID = 0L;

    public String format = "";

    public void setFormat( final String format )
    {
        this.format = format;  //empty or "plain"
    }

    /**
     *  {@inheritDoc}
     */
    @Override
    public final int doWikiStartTag() throws IOException {
        final Engine engine = wikiContext.getEngine();
        final Page page = wikiContext.getPage();
        String author = page.getAuthor();

        if( author != null && !author.isEmpty() ) {
            author = TextUtil.replaceEntities(author);

            if( engine.getManager( PageManager.class ).wikiPageExists(author) && !( "plain".equalsIgnoreCase( format ) ) ) {
                // FIXME: It's very boring to have to do this.  Slow, too.
                final RenderingManager mgr = engine.getManager( RenderingManager.class );
                final MarkupParser parser = mgr.getParser( wikiContext, "["+author+"|"+author+"]" );
                final WikiDocument d = parser.parse();
                author = mgr.getHTML( wikiContext, d );
            }

            pageContext.getOut().print( author );
        } else {
            pageContext.getOut().print( Preferences.getBundle( wikiContext, InternationalizationManager.CORE_BUNDLE )
                                                   .getString( "common.unknownauthor" ) );
        }

        return SKIP_BODY;
    }

}
